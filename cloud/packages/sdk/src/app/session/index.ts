/**
 * 🎯 App Session Module
 *
 * Manages an active Third Party App session with MentraOS Cloud.
 * Handles real-time communication, event subscriptions, and display management.
 */
import { WebSocket } from "ws";
import { EventManager, EventData } from "./events";
import { LayoutManager } from "./layouts";
import { SettingsManager } from "./settings";
import { LocationManager } from "./modules/location";
import { CameraModule } from "./modules/camera";
import { AudioManager } from "./modules/audio";
import { ResourceTracker } from "../../utils/resource-tracker";
import {
  // Message types
  AppToCloudMessage,
  CloudToAppMessage,
  AppConnectionInit,
  AppSubscriptionUpdate,
  AudioPlayResponse,
  AppToCloudMessageType,
  CloudToAppMessageType,

  // Event data types
  StreamType,
  ExtendedStreamType,
  ButtonPress,
  HeadPosition,
  PhoneNotification,
  PhoneNotificationDismissed,
  TranscriptionData,
  TranslationData,

  // Type guards
  isAppConnectionAck,
  isAppConnectionError,
  isDataStream,
  isAppStopped,
  isSettingsUpdate,
  isDashboardModeChanged,
  isDashboardAlwaysOnChanged,
  isAudioPlayResponse,
  isCapabilitiesUpdate,

  // Other types
  AppSettings,
  AppSetting,
  AppConfig,
  validateAppConfig,
  AudioChunk,
  VpsCoordinates,
  PhotoTaken,
  SubscriptionRequest,
  Capabilities,
  CapabilitiesUpdate,
} from "../../types";
import { DashboardAPI } from "../../types/dashboard";
import { MentraosSettingsUpdate } from "../../types/messages/cloud-to-app";
import { Logger } from "pino";
import { AppServer } from "../server";
import axios from "axios";
import EventEmitter from "events";

// Import the cloud-to-app specific type guards
import {
  isPhotoResponse,
  isRtmpStreamStatus,
  isManagedStreamStatus,
  isStreamStatusCheckResponse,
} from "../../types/messages/cloud-to-app";
import { SimpleStorage } from "./modules/simple-storage";
import { readNotificationWarnLog } from "../../utils/permissions-utils";

/**
 * ⚙️ Configuration options for App Session
 *
 * @example
 * ```typescript
 * const config: AppSessionConfig = {
 *   packageName: 'org.example.myapp',
 *   apiKey: 'your_api_key',
 *   // Auto-reconnection is enabled by default
 *   // autoReconnect: true
 * };
 * ```
 */
export interface AppSessionConfig {
  /** 📦 Unique identifier for your App (e.g., 'org.company.appname') */
  packageName: string;
  /** 🔑 API key for authentication with MentraOS Cloud */
  apiKey: string;
  /** 🔌 WebSocket server URL (default: 'ws://localhost:7002/app-ws') */
  mentraOSWebsocketUrl?: string;
  /** 🔄 Automatically attempt to reconnect on disconnect (default: true) */
  autoReconnect?: boolean;
  /** 🔁 Maximum number of reconnection attempts (default: 3) */
  maxReconnectAttempts?: number;
  /** ⏱️ Base delay between reconnection attempts in ms (default: 1000) */
  reconnectDelay?: number;

  userId: string; // user ID for tracking sessions (email of the user).
  appServer: AppServer; // Optional App server instance for advanced features
}

// List of event types that should never be subscribed to as streams
const APP_TO_APP_EVENT_TYPES = [
  "app_message_received",
  "app_user_joined",
  "app_user_left",
  "app_room_updated",
  "app_direct_message_response",
];

/**
 * 🚀 App Session Implementation
 *
 * Manages a live connection between your App and MentraOS Cloud.
 * Provides interfaces for:
 * - 🎮 Event handling (transcription, head position, etc.)
 * - 📱 Display management in AR view
 * - 🔌 Connection lifecycle
 * - 🔄 Automatic reconnection
 *
 * @example
 * ```typescript
 * const session = new AppSession({
 *   packageName: 'org.example.myapp',
 *   apiKey: 'your_api_key'
 * });
 *
 * // Handle events
 * session.onTranscription((data) => {
 *   session.layouts.showTextWall(data.text);
 * });
 *
 * // Connect to cloud
 * await session.connect('session_123');
 * ```
 */
export class AppSession {
  /** WebSocket connection to MentraOS Cloud */
  private ws: WebSocket | null = null;
  /** Current session identifier */
  private sessionId: string | null = null;
  /** Number of reconnection attempts made */
  private reconnectAttempts = 0;
  /** Active event subscriptions */
  private subscriptions = new Set<ExtendedStreamType>();
  /** Map to store rate options for streams */
  private streamRates = new Map<ExtendedStreamType, string>();
  /** Resource tracker for automatic cleanup */
  private resources = new ResourceTracker();
  /** Internal settings storage - use public settings API instead */
  private settingsData: AppSettings = [];
  /** App configuration loaded from app_config.json */
  private appConfig: AppConfig | null = null;
  /** Whether to update subscriptions when settings change */
  private shouldUpdateSubscriptionsOnSettingsChange = false;
  /** Custom subscription handler for settings-based subscriptions */
  private subscriptionSettingsHandler?: (
    settings: AppSettings,
  ) => ExtendedStreamType[];
  /** Settings that should trigger subscription updates when changed */
  private subscriptionUpdateTriggers: string[] = [];
  /** Pending user discovery requests waiting for responses */
  private pendingUserDiscoveryRequests = new Map<
    string,
    {
      resolve: (userList: any) => void;
      reject: (reason: any) => void;
    }
  >();
  /** Pending direct message requests waiting for responses */
  private pendingDirectMessages = new Map<
    string,
    {
      resolve: (success: boolean) => void;
      reject: (reason: any) => void;
    }
  >();

  /** 🎮 Event management interface */
  public readonly events: EventManager;
  /** 📱 Layout management interface */
  public readonly layouts: LayoutManager;
  /** ⚙️ Settings management interface */
  public readonly settings: SettingsManager;
  /** 📊 Dashboard management interface */
  public readonly dashboard: DashboardAPI;
  /** 📍 Location management interface */
  public readonly location: LocationManager;
  /** 📷 Camera interface for photos and streaming */
  public readonly camera: CameraModule;
  /** 🔊 Audio interface for audio playback */
  public readonly audio: AudioManager;
  /** 🔐 Simple key-value storage interface */
  public readonly simpleStorage: SimpleStorage;

  public readonly appServer: AppServer;
  public readonly logger: Logger;
  public readonly userId: string;

  /** 🔧 Device capabilities available for this session */
  public capabilities: Capabilities | null = null;

  /** Dedicated emitter for App-to-App events */
  private appEvents = new EventEmitter();

  constructor(private config: AppSessionConfig) {
    // Set defaults and merge with provided config
    this.config = {
      mentraOSWebsocketUrl: `ws://localhost:8002/app-ws`, // Use localhost as default
      autoReconnect: true, // Enable auto-reconnection by default for better reliability
      maxReconnectAttempts: 3, // Default to 3 reconnection attempts for better resilience
      reconnectDelay: 1000, // Start with 1 second delay (uses exponential backoff)
      ...config,
    };

    this.appServer = this.config.appServer;
    this.logger = this.appServer.logger.child({
      userId: this.config.userId,
      service: "app-session",
    });
    this.userId = this.config.userId;

    // Make sure the URL is correctly formatted to prevent double protocol issues
    if (this.config.mentraOSWebsocketUrl) {
      try {
        const url = new URL(this.config.mentraOSWebsocketUrl);
        if (!["ws:", "wss:"].includes(url.protocol)) {
          // Fix URLs with incorrect protocol (e.g., 'ws://http://host')
          const fixedUrl = this.config.mentraOSWebsocketUrl.replace(
            /^ws:\/\/http:\/\//,
            "ws://",
          );
          this.config.mentraOSWebsocketUrl = fixedUrl;
          this.logger.warn(
            `⚠️ [${this.config.packageName}] Fixed malformed WebSocket URL: ${fixedUrl}`,
          );
        }
      } catch (error) {
        this.logger.error(
          error,
          `⚠️ [${this.config.packageName}] Invalid WebSocket URL format: ${this.config.mentraOSWebsocketUrl}`,
        );
      }
    }

    // Log initialization
    this.logger.debug(
      `🚀 [${this.config.packageName}] App Session initialized`,
    );
    this.logger.debug(
      `🚀 [${this.config.packageName}] WebSocket URL: ${this.config.mentraOSWebsocketUrl}`,
    );

    // Validate URL format - give early warning for obvious issues
    // Check URL format but handle undefined case
    if (this.config.mentraOSWebsocketUrl) {
      try {
        const url = new URL(this.config.mentraOSWebsocketUrl);
        if (!["ws:", "wss:"].includes(url.protocol)) {
          this.logger.error(
            { config: this.config },
            `⚠️ [${this.config.packageName}] Invalid WebSocket URL protocol: ${url.protocol}. Should be ws: or wss:`,
          );
        }
      } catch (error) {
        this.logger.error(
          error,
          `⚠️ [${this.config.packageName}] Invalid WebSocket URL format: ${this.config.mentraOSWebsocketUrl}`,
        );
      }
    }

    this.events = new EventManager(
      this.subscribe.bind(this),
      this.unsubscribe.bind(this),
      this.config.packageName,
      this.getHttpsServerUrl() || "",
    );
    this.layouts = new LayoutManager(config.packageName, this.send.bind(this));

    // Initialize settings manager with all necessary parameters, including subscribeFn for MentraOS settings
    this.settings = new SettingsManager(
      this.settingsData,
      this.config.packageName,
      this.config.mentraOSWebsocketUrl,
      this.sessionId ?? undefined,
      async (streams: string[]) => {
        this.logger.debug(
          { streams: JSON.stringify(streams) },
          `[AppSession] subscribeFn called for streams`,
        );
        streams.forEach((stream) => {
          if (!this.subscriptions.has(stream as ExtendedStreamType)) {
            this.subscriptions.add(stream as ExtendedStreamType);
            this.logger.debug(
              `[AppSession] Auto-subscribed to stream '${stream}' for MentraOS setting.`,
            );
          } else {
            this.logger.debug(
              `[AppSession] Already subscribed to stream '${stream}'.`,
            );
          }
        });
        this.logger.debug(
          { subscriptions: JSON.stringify(Array.from(this.subscriptions)) },
          `[AppSession] Current subscriptions after subscribeFn`,
        );
        if (this.ws?.readyState === 1) {
          this.updateSubscriptions();
          this.logger.debug(
            `[AppSession] Sent updated subscriptions to cloud after auto-subscribing to MentraOS setting.`,
          );
        } else {
          this.logger.debug(
            `[AppSession] WebSocket not open, will send subscriptions when connected.`,
          );
        }
      },
    );

    // Initialize dashboard API with this session instance
    // Import DashboardManager dynamically to avoid circular dependency
    const { DashboardManager } = require("./dashboard");
    this.dashboard = new DashboardManager(this, this.send.bind(this));

    // Initialize camera module with session reference
    this.camera = new CameraModule(
      this.config.packageName,
      this.sessionId || "unknown-session-id",
      this.send.bind(this),
      this, // Pass session reference
      this.logger.child({ module: "camera" }),
    );

    // Initialize audio module with session reference
    this.audio = new AudioManager(
      this.config.packageName,
      this.sessionId || "unknown-session-id",
      this.send.bind(this),
      this, // Pass session reference
      this.logger.child({ module: "audio" }),
    );

    this.simpleStorage = new SimpleStorage(this);

    this.location = new LocationManager(this, this.send.bind(this));
  }

  /**
   * Get the current session ID
   * @returns The current session ID or 'unknown-session-id' if not connected
   */
  getSessionId(): string {
    return this.sessionId || "unknown-session-id";
  }

  /**
   * Get the package name for this App
   * @returns The package name
   */
  getPackageName(): string {
    return this.config.packageName;
  }

  // =====================================
  // 🎮 Direct Event Handling Interface
  // =====================================

  /**
   * @deprecated Use session.events.onTranscription() instead
   */
  onTranscription(handler: (data: TranscriptionData) => void): () => void {
    return this.events.onTranscription(handler);
  }

  /**
   * 🌐 Listen for speech transcription events in a specific language
   * @param language - Language code (e.g., "en-US")
   * @param handler - Function to handle transcription data
   * @returns Cleanup function to remove the handler
   * @throws Error if language code is invalid
   * @deprecated Use session.events.onTranscriptionForLanguage() instead
   */
  onTranscriptionForLanguage(
    language: string,
    handler: (data: TranscriptionData) => void,
    disableLanguageIdentification = false,
  ): () => void {
    return this.events.onTranscriptionForLanguage(
      language,
      handler,
      disableLanguageIdentification,
    );
  }

  /**
   * 🌐 Listen for speech translation events for a specific language pair
   * @param sourceLanguage - Source language code (e.g., "es-ES")
   * @param targetLanguage - Target language code (e.g., "en-US")
   * @param handler - Function to handle translation data
   * @returns Cleanup function to remove the handler
   * @throws Error if language codes are invalid
   * @deprecated Use session.events.onTranslationForLanguage() instead
   */
  onTranslationForLanguage(
    sourceLanguage: string,
    targetLanguage: string,
    handler: (data: TranslationData) => void,
  ): () => void {
    return this.events.ontranslationForLanguage(
      sourceLanguage,
      targetLanguage,
      handler,
    );
  }

  /**
   * 👤 Listen for head position changes
   * @param handler - Function to handle head position updates
   * @returns Cleanup function to remove the handler
   * @deprecated Use session.events.onHeadPosition() instead
   */
  onHeadPosition(handler: (data: HeadPosition) => void): () => void {
    return this.events.onHeadPosition(handler);
  }

  /**
   * 🔘 Listen for hardware button press events
   * @param handler - Function to handle button events
   * @returns Cleanup function to remove the handler
   * @deprecated Use session.events.onButtonPress() instead
   */
  onButtonPress(handler: (data: ButtonPress) => void): () => void {
    return this.events.onButtonPress(handler);
  }

  /**
   * 📱 Listen for phone notification events
   * @param handler - Function to handle notifications
   * @returns Cleanup function to remove the handler
   * @deprecated Use session.events.onPhoneNotifications() instead
   */
  onPhoneNotifications(handler: (data: PhoneNotification) => void): () => void {
    readNotificationWarnLog(
      this.getHttpsServerUrl() || "",
      this.getPackageName(),
      "onPhoneNotifications",
    );
    return this.events.onPhoneNotifications(handler);
  }

  /**
   * 📱 Listen for phone notification dismissed events
   * @param handler - Function to handle notification dismissal data
   * @returns Cleanup function to remove the handler
   * @deprecated Use session.events.onPhoneNotificationDismissed() instead
   */
  onPhoneNotificationDismissed(
    handler: (data: PhoneNotificationDismissed) => void,
  ): () => void {
    return this.events.onPhoneNotificationDismissed(handler);
  }

  /**
   * 📡 Listen for VPS coordinates updates
   * @param handler - Function to handle VPS coordinates
   * @returns Cleanup function to remove the handler
   * @deprecated Use session.events.onVpsCoordinates() instead
   */
  onVpsCoordinates(handler: (data: VpsCoordinates) => void): () => void {
    this.subscribe(StreamType.VPS_COORDINATES);
    return this.events.onVpsCoordinates(handler);
  }

  /**
   * 📸 Listen for photo responses
   * @param handler - Function to handle photo response data
   * @returns Cleanup function to remove the handler
   * @deprecated Use session.events.onPhotoTaken() instead
   */
  onPhotoTaken(handler: (data: PhotoTaken) => void): () => void {
    this.subscribe(StreamType.PHOTO_TAKEN);
    return this.events.onPhotoTaken(handler);
  }

  // =====================================
  // 📡 Pub/Sub Interface
  // =====================================

  /**
   * 📬 Subscribe to a specific event stream
   * @param sub - A string or a rich subscription object
   */
  subscribe(sub: SubscriptionRequest): void {
    let type: ExtendedStreamType;
    let rate: string | undefined;

    if (typeof sub === "string") {
      type = sub;
    } else {
      // it's a LocationStreamRequest object
      type = sub.stream;
      rate = sub.rate;
    }

    if (APP_TO_APP_EVENT_TYPES.includes(type as string)) {
      this.logger.warn(
        `[AppSession] Attempted to subscribe to App-to-App event type '${type}', which is not a valid stream. Use the event handler (e.g., onAppMessage) instead.`,
      );
      return;
    }

    this.subscriptions.add(type);
    if (rate) {
      this.streamRates.set(type, rate);
    }

    if (this.ws?.readyState === 1) {
      this.updateSubscriptions();
    }
  }

  /**
   * 📭 Unsubscribe from a specific event stream
   * @param sub - The subscription to remove
   */
  unsubscribe(sub: SubscriptionRequest): void {
    let type: ExtendedStreamType;
    if (typeof sub === "string") {
      type = sub;
    } else {
      type = sub.stream;
    }

    if (APP_TO_APP_EVENT_TYPES.includes(type as string)) {
      this.logger.warn(
        `[AppSession] Attempted to unsubscribe from App-to-App event type '${type}', which is not a valid stream.`,
      );
      return;
    }
    this.subscriptions.delete(type);
    this.streamRates.delete(type); // also remove from our rate map
    if (this.ws?.readyState === 1) {
      this.updateSubscriptions();
    }
  }

  /**
   * 🎯 Generic event listener (pub/sub style)
   * @param event - Event name to listen for
   * @param handler - Event handler function
   */
  on<T extends ExtendedStreamType>(
    event: T,
    handler: (data: EventData<T>) => void,
  ): () => void {
    return this.events.on(event, handler);
  }

  // =====================================
  // 🔌 Connection Management
  // =====================================

  /**
   * 🚀 Connect to MentraOS Cloud
   * @param sessionId - Unique session identifier
   * @returns Promise that resolves when connected
   */
  async connect(sessionId: string): Promise<void> {
    this.sessionId = sessionId;

    // Configure settings API client with the WebSocket URL and session ID
    // This allows settings to be fetched from the correct server
    this.settings.configureApiClient(
      this.config.packageName,
      this.config.mentraOSWebsocketUrl || "",
      sessionId,
    );

    // Update the sessionId in the camera module
    if (this.camera) {
      this.camera.updateSessionId(sessionId);
    }

    // Update the sessionId in the audio module
    if (this.audio) {
      this.audio.updateSessionId(sessionId);
    }

    return new Promise((resolve, reject) => {
      try {
        // Clear previous resources if reconnecting
        if (this.ws) {
          // Don't call full dispose() as that would clear subscriptions
          if (this.ws.readyState !== 3) {
            // 3 = CLOSED
            this.ws.close();
          }
          this.ws = null;
        }

        // Validate WebSocket URL before attempting connection
        if (!this.config.mentraOSWebsocketUrl) {
          this.logger.error("WebSocket URL is missing or undefined");
          reject(new Error("WebSocket URL is required"));
          return;
        }

        // Add debug logging for connection attempts
        this.logger.info(
          `🔌🔌🔌 [${this.config.packageName}] Attempting to connect to: ${this.config.mentraOSWebsocketUrl} for session ${this.sessionId}`,
        );

        // Create connection with error handling
        this.ws = new WebSocket(this.config.mentraOSWebsocketUrl);

        // Track WebSocket for automatic cleanup
        this.resources.track(() => {
          if (this.ws && this.ws.readyState !== 3) {
            // 3 = CLOSED
            this.ws.close();
          }
        });

        this.ws.on("open", () => {
          try {
            this.sendConnectionInit();
          } catch (error: unknown) {
            this.logger.error(error, "Error during connection initialization");
            const errorMessage =
              error instanceof Error ? error.message : String(error);
            this.events.emit(
              "error",
              new Error(`Connection initialization failed: ${errorMessage}`),
            );
            reject(error);
          }
        });

        // Message handler with comprehensive error recovery
        const messageHandler = async (
          data: Buffer | string,
          isBinary: boolean,
        ) => {
          try {
            // Handle binary messages (typically audio data)
            if (isBinary && Buffer.isBuffer(data)) {
              try {
                // Validate buffer before processing
                if (data.length === 0) {
                  this.events.emit(
                    "error",
                    new Error("Received empty binary data"),
                  );
                  return;
                }

                // Convert Node.js Buffer to ArrayBuffer safely
                const arrayBuf: ArrayBufferLike = data.buffer.slice(
                  data.byteOffset,
                  data.byteOffset + data.byteLength,
                );

                // Create AUDIO_CHUNK event message with validation
                const audioChunk: AudioChunk = {
                  type: StreamType.AUDIO_CHUNK,
                  arrayBuffer: arrayBuf,
                  timestamp: new Date(), // Ensure timestamp is present
                };

                this.handleMessage(audioChunk);
                return;
              } catch (error: unknown) {
                this.logger.error(error, "Error processing binary message:");
                const errorMessage =
                  error instanceof Error ? error.message : String(error);
                this.events.emit(
                  "error",
                  new Error(
                    `Failed to process binary message: ${errorMessage}`,
                  ),
                );
                return;
              }
            }

            // Handle ArrayBuffer data type directly
            if (data instanceof ArrayBuffer) {
              return;
            }

            // Handle JSON messages with validation
            try {
              // Convert string data to JSON safely
              let jsonData: string;
              if (typeof data === "string") {
                jsonData = data;
              } else if (Buffer.isBuffer(data)) {
                jsonData = data.toString("utf8");
              } else {
                throw new Error("Unknown message format");
              }

              // Validate JSON before parsing
              if (!jsonData || jsonData.trim() === "") {
                this.events.emit(
                  "error",
                  new Error("Received empty JSON message"),
                );
                return;
              }

              // Parse JSON with error handling
              const message = JSON.parse(jsonData) as CloudToAppMessage;

              // Basic schema validation
              if (
                !message ||
                typeof message !== "object" ||
                !("type" in message)
              ) {
                this.events.emit(
                  "error",
                  new Error("Malformed message: missing type property"),
                );
                return;
              }

              // Process the validated message
              this.handleMessage(message);
            } catch (error: unknown) {
              this.logger.error(error, "JSON parsing error");
              const errorMessage =
                error instanceof Error ? error.message : String(error);
              this.events.emit(
                "error",
                new Error(`Failed to parse JSON message: ${errorMessage}`),
              );
            }
          } catch (error: unknown) {
            // Final catch - should never reach here if individual handlers work correctly
            this.logger.error({ error }, "Unhandled message processing error");
            const errorMessage =
              error instanceof Error ? error.message : String(error);
            this.events.emit(
              "error",
              new Error(`Unhandled message error: ${errorMessage}`),
            );
          }
        };

        this.ws.on("message", messageHandler);

        // Track event handler removal for automatic cleanup
        this.resources.track(() => {
          if (this.ws) {
            this.ws.off("message", messageHandler);
          }
        });

        // Connection closure handler
        const closeHandler = (code: number, reason: string) => {
          const reasonStr = reason ? `: ${reason}` : "";
          const closeInfo = `Connection closed (code: ${code})${reasonStr}`;

          // Emit the disconnected event with structured data for better handling
          this.events.emit("disconnected", {
            message: closeInfo,
            code: code,
            reason: reason || "",
            wasClean: code === 1000 || code === 1001,
          });

          // Only attempt reconnection for abnormal closures
          // https://developer.mozilla.org/en-US/docs/Web/API/CloseEvent/code
          // 1000 (Normal Closure) and 1001 (Going Away) are normal
          // 1002-1015 are abnormal, and reason "App stopped" means intentional closure
          // 1008 usually when the userSession no longer exists on server. i.e user disconnected from cloud.
          const isNormalClosure =
            code === 1000 || code === 1001 || code === 1008;
          const isManualStop = reason && reason.includes("App stopped");
          const isUserSessionEnded =
            reason && reason.includes("User session ended");

          // Log closure details for diagnostics
          this.logger.debug(
            `🔌 [${this.config.packageName}] WebSocket closed with code ${code}${reasonStr}`,
          );
          this.logger.debug(
            `🔌 [${this.config.packageName}] isNormalClosure: ${isNormalClosure}, isManualStop: ${isManualStop}, isUserSessionEnded: ${isUserSessionEnded}`,
          );

          if (!isNormalClosure && !isManualStop) {
            this.logger.warn(
              `🔌 [${this.config.packageName}] Abnormal closure detected, attempting reconnection`,
            );
            this.handleReconnection();
          } else {
            this.logger.debug(
              `🔌 [${this.config.packageName}] Normal closure detected, not attempting reconnection`,
            );
          }

          // if user session ended, then trigger onStop.
          if (isUserSessionEnded) {
            this.logger.info(
              `🛑 [${this.config.packageName}] User session ended - emitting disconnected event with sessionEnded flag`,
            );
            // Emit a disconnected event with a special flag to indicate session end
            // This will be caught by AppServer which will call the onStop callback
            const disconnectInfo = {
              message: "User session ended",
              code: 1000, // Normal closure
              reason: "User session ended",
              wasClean: true,
              permanent: true, // This is permanent - no reconnection
              sessionEnded: true, // Special flag to indicate session disposal
            };
            this.events.emit("disconnected", disconnectInfo);
          }
        };

        this.ws.on("close", closeHandler);

        // Track event handler removal
        this.resources.track(() => {
          if (this.ws) {
            this.ws.off("close", closeHandler);
          }
        });

        // Connection error handler
        const errorHandler = (error: Error) => {
          this.logger.error(error, "WebSocket error");
          this.events.emit("error", error);
        };

        // Enhanced error handler with detailed logging
        this.ws.on("error", (error: Error) => {
          this.logger.error(
            error,
            `⛔️⛔️⛔️ [${this.config.packageName}] WebSocket connection error: ${error.message}`,
          );

          // Try to provide more context
          const errMsg = error.message || "";
          if (errMsg.includes("ECONNREFUSED")) {
            this.logger.error(
              `⛔️⛔️⛔️ [${this.config.packageName}] Connection refused - Check if the server is running at the specified URL`,
            );
          } else if (errMsg.includes("ETIMEDOUT")) {
            this.logger.error(
              `⛔️⛔️⛔️ [${this.config.packageName}] Connection timed out - Check network connectivity and firewall rules`,
            );
          }

          errorHandler(error);
        });

        // Track event handler removal
        this.resources.track(() => {
          if (this.ws) {
            this.ws.off("error", errorHandler);
          }
        });

        // Set up connection success handler
        const connectedCleanup = this.events.onConnected(() => resolve());

        // Track event handler removal
        this.resources.track(connectedCleanup);

        // Connection timeout with configurable duration
        const timeoutMs = 5000; // 5 seconds default
        const connectionTimeout = this.resources.setTimeout(() => {
          // Use tracked timeout that will be auto-cleared
          this.logger.error(
            {
              config: this.config,
              sessionId: this.sessionId,
              timeoutMs,
            },
            `⏱️⏱️⏱️ [${this.config.packageName}] Connection timeout after ${timeoutMs}ms`,
          );

          this.events.emit(
            "error",
            new Error(`Connection timeout after ${timeoutMs}ms`),
          );
          reject(new Error("Connection timeout"));
        }, timeoutMs);

        // Clear timeout on successful connection
        const timeoutCleanup = this.events.onConnected(() => {
          clearTimeout(connectionTimeout);
          resolve();
        });

        // Track event handler removal
        this.resources.track(timeoutCleanup);
      } catch (error: unknown) {
        this.logger.error(error, "Connection setup error");
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        reject(new Error(`Failed to setup connection: ${errorMessage}`));
      }
    });
  }

  /**
   * 👋 Disconnect from MentraOS Cloud
   */
  disconnect(): void {
    // Clean up camera module first
    if (this.camera) {
      this.camera.cancelAllRequests();
    }

    // Clean up audio module
    if (this.audio) {
      this.audio.cancelAllRequests();
    }

    // Use the resource tracker to clean up everything
    this.resources.dispose();

    // Clean up additional resources not handled by the tracker
    this.ws = null;
    this.sessionId = null;
    this.subscriptions.clear();
    this.reconnectAttempts = 0;
  }

  /**
   * 🛠️ Get all current user settings
   * @returns A copy of the current settings array
   * @deprecated Use session.settings.getAll() instead
   */
  getSettings(): AppSettings {
    return this.settings.getAll();
  }

  /**
   * 🔍 Get a specific setting value by key
   * @param key The setting key to look for
   * @returns The setting's value, or undefined if not found
   * @deprecated Use session.settings.get(key) instead
   */
  getSetting<T>(key: string): T | undefined {
    return this.settings.get<T>(key);
  }

  /**
   * ⚙️ Configure settings-based subscription updates
   * This allows Apps to automatically update their subscriptions when certain settings change
   * @param options Configuration options for settings-based subscriptions
   */
  setSubscriptionSettings(options: {
    updateOnChange: string[]; // Setting keys that should trigger subscription updates
    handler: (settings: AppSettings) => ExtendedStreamType[]; // Handler that returns new subscriptions
  }): void {
    this.shouldUpdateSubscriptionsOnSettingsChange = true;
    this.subscriptionUpdateTriggers = options.updateOnChange;
    this.subscriptionSettingsHandler = options.handler;

    // If we already have settings, update subscriptions immediately
    if (this.settingsData.length > 0) {
      this.updateSubscriptionsFromSettings();
    }
  }

  /**
   * 🔄 Update subscriptions based on current settings
   * Called automatically when relevant settings change
   */
  private updateSubscriptionsFromSettings(): void {
    if (!this.subscriptionSettingsHandler) return;

    try {
      // Get new subscriptions from handler
      const newSubscriptions = this.subscriptionSettingsHandler(
        this.settingsData,
      );

      // Update all subscriptions at once
      this.subscriptions.clear();
      newSubscriptions.forEach((subscription) => {
        this.subscriptions.add(subscription);
      });

      // Send subscription update to cloud if connected
      if (this.ws && this.ws.readyState === 1) {
        this.updateSubscriptions();
      }
    } catch (error: unknown) {
      this.logger.error(error, "Error updating subscriptions from settings");
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      this.events.emit(
        "error",
        new Error(`Failed to update subscriptions: ${errorMessage}`),
      );
    }
  }

  /**
   * 🧪 For testing: Update settings locally
   * In normal operation, settings come from the cloud
   * @param newSettings The new settings to apply
   */
  updateSettingsForTesting(newSettings: AppSettings): void {
    this.settingsData = newSettings;

    // Update the settings manager with the new settings
    this.settings.updateSettings(newSettings);

    // Emit update event for backwards compatibility
    this.events.emit("settings_update", this.settingsData);

    // Check if we should update subscriptions
    if (this.shouldUpdateSubscriptionsOnSettingsChange) {
      this.updateSubscriptionsFromSettings();
    }
  }

  /**
   * 📝 Load configuration from a JSON file
   * @param jsonData JSON string containing App configuration
   * @returns The loaded configuration
   * @throws Error if the configuration is invalid
   */
  loadConfigFromJson(jsonData: string): AppConfig {
    try {
      const parsedConfig = JSON.parse(jsonData);

      if (validateAppConfig(parsedConfig)) {
        this.appConfig = parsedConfig;
        return parsedConfig;
      } else {
        throw new Error("Invalid App configuration format");
      }
    } catch (error: unknown) {
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      throw new Error(`Failed to load App configuration: ${errorMessage}`);
    }
  }

  /**
   * 📋 Get the loaded App configuration
   * @returns The current App configuration or null if not loaded
   */
  getConfig(): AppConfig | null {
    return this.appConfig;
  }

  /**
   * 🔌 Get the WebSocket server URL for this session
   * @returns The WebSocket server URL used by this session
   */
  getServerUrl(): string | undefined {
    return this.config.mentraOSWebsocketUrl;
  }

  public getHttpsServerUrl(): string | undefined {
    if (!this.config.mentraOSWebsocketUrl) {
      return undefined;
    }
    return AppSession.convertToHttps(this.config.mentraOSWebsocketUrl);
  }

  private static convertToHttps(rawUrl: string | undefined): string {
    if (!rawUrl) return "";
    // Remove ws:// or wss://
    let url = rawUrl.replace(/^wss?:\/\//, "");
    // Remove trailing /app-ws
    url = url.replace(/\/app-ws$/, "");
    // Prepend https://
    return `https://${url}`;
  }

  /**
   * 🔍 Get default settings from the App configuration
   * @returns Array of settings with default values
   * @throws Error if configuration is not loaded
   */
  getDefaultSettings(): AppSettings {
    if (!this.appConfig) {
      throw new Error(
        "App configuration not loaded. Call loadConfigFromJson first.",
      );
    }

    return this.appConfig.settings
      .filter(
        (s: AppSetting | { type: "group"; title: string }): s is AppSetting =>
          s.type !== "group",
      )
      .map((s: AppSetting) => ({
        ...s,
        value: s.defaultValue, // Set value to defaultValue
      }));
  }

  /**
   * 🔍 Get setting schema from configuration
   * @param key Setting key to look up
   * @returns The setting schema or undefined if not found
   */
  getSettingSchema(key: string): AppSetting | undefined {
    if (!this.appConfig) return undefined;

    const setting = this.appConfig.settings.find(
      (s: AppSetting | { type: "group"; title: string }) =>
        s.type !== "group" && "key" in s && s.key === key,
    );

    return setting as AppSetting | undefined;
  }

  // =====================================
  // 🔧 Private Methods
  // =====================================

  /**
   * 📨 Handle incoming messages from cloud
   */
  private handleMessage(message: CloudToAppMessage): void {
    try {
      // Validate message before processing
      if (!this.validateMessage(message)) {
        this.events.emit("error", new Error("Invalid message format received"));
        return;
      }

      // Handle binary data (audio or video)
      if (message instanceof ArrayBuffer) {
        this.handleBinaryMessage(message);
        return;
      }

      // Using type guards to determine message type and safely handle each case
      try {
        if (isAppConnectionAck(message)) {
          // Get settings from connection acknowledgment
          const receivedSettings = message.settings || [];
          this.settingsData = receivedSettings;

          // Store config if provided
          if (message.config && validateAppConfig(message.config)) {
            this.appConfig = message.config;
          }

          // Use default settings from config if no settings were provided
          if (receivedSettings.length === 0 && this.appConfig) {
            try {
              this.settingsData = this.getDefaultSettings();
            } catch (error) {
              this.logger.warn(
                error,
                "Failed to load default settings from config:",
              );
            }
          }

          // Update the settings manager with the new settings
          this.settings.updateSettings(this.settingsData);

          // Handle MentraOS system settings if provided
          this.logger.debug(
            { mentraosSettings: JSON.stringify(message.mentraosSettings) },
            `[AppSession] CONNECTION_ACK mentraosSettings}`,
          );
          if (message.mentraosSettings) {
            this.logger.info(
              { mentraosSettings: JSON.stringify(message.mentraosSettings) },
              `[AppSession] Calling updatementraosSettings with`,
            );
            this.settings.updateMentraosSettings(message.mentraosSettings);
          } else {
            this.logger.warn(
              `[AppSession] CONNECTION_ACK message missing mentraosSettings field`,
            );
          }

          // Handle device capabilities if provided
          if (message.capabilities) {
            this.capabilities = message.capabilities;
            this.logger.info(
              `[AppSession] Device capabilities loaded for model: ${message.capabilities.modelName}`,
            );
          } else {
            this.logger.debug(
              `[AppSession] No capabilities provided in CONNECTION_ACK`,
            );
          }

          // Emit connected event with settings
          this.events.emit("connected", this.settingsData);

          // Update subscriptions (normal flow)
          this.updateSubscriptions();

          // If settings-based subscriptions are enabled, update those too
          if (
            this.shouldUpdateSubscriptionsOnSettingsChange &&
            this.settingsData.length > 0
          ) {
            this.updateSubscriptionsFromSettings();
          }
        } else if (
          isAppConnectionError(message) ||
          message.type === "connection_error"
        ) {
          // Handle both App-specific connection_error and standard connection_error
          const errorMessage = message.message || "Unknown connection error";
          this.events.emit("error", new Error(errorMessage));
        } else if (message.type === StreamType.AUDIO_CHUNK) {
          if (this.subscriptions.has(StreamType.AUDIO_CHUNK)) {
            // Only process if we're subscribed to avoid unnecessary processing
            this.events.emit(StreamType.AUDIO_CHUNK, message);
          }
        } else if (isDataStream(message)) {
          // Ensure streamType exists before emitting the event
          const messageStreamType = message.streamType as ExtendedStreamType;
          // if (message.streamType === StreamType.TRANSCRIPTION) {
          //   const transcriptionData = message.data as TranscriptionData;
          //   if (transcriptionData.transcribeLanguage) {
          //     messageStreamType = createTranscriptionStream(transcriptionData.transcribeLanguage) as ExtendedStreamType;
          //   }
          // } else if (message.streamType === StreamType.TRANSLATION) {
          //   const translationData = message.data as TranslationData;
          //   if (translationData.transcribeLanguage && translationData.translateLanguage) {
          //     messageStreamType = createTranslationStream(translationData.transcribeLanguage, translationData.translateLanguage) as ExtendedStreamType;
          //   }
          // }

          if (messageStreamType && this.subscriptions.has(messageStreamType)) {
            const sanitizedData = this.sanitizeEventData(
              messageStreamType,
              message.data,
            ) as EventData<typeof messageStreamType>;
            this.events.emit(messageStreamType, sanitizedData);
          }
        } else if (isRtmpStreamStatus(message)) {
          // Emit as a standard stream event if subscribed
          if (this.subscriptions.has(StreamType.RTMP_STREAM_STATUS)) {
            this.events.emit(StreamType.RTMP_STREAM_STATUS, message);
          }

          // Update camera module's internal stream state
          this.camera.updateStreamState(message);
        } else if (isManagedStreamStatus(message)) {
          // Emit as a standard stream event if subscribed
          if (this.subscriptions.has(StreamType.MANAGED_STREAM_STATUS)) {
            this.events.emit(StreamType.MANAGED_STREAM_STATUS, message);
          }

          // Update camera module's managed stream state
          this.camera.handleManagedStreamStatus(message);
        } else if (isStreamStatusCheckResponse(message)) {
          // Handle stream status check response
          // This is a direct response, not a subscription-based event
          this.camera.handleStreamCheckResponse(message);
        } else if (isSettingsUpdate(message)) {
          // Store previous settings to check for changes
          const _prevSettings = [...this.settingsData];

          // Update internal settings storage
          this.settingsData = message.settings || [];

          // Update the settings manager with the new settings
          const changes = this.settings.updateSettings(this.settingsData);

          // Emit settings update event (for backwards compatibility)
          this.events.emit("settings_update", this.settingsData);

          // --- MentraOS settings update logic ---
          // If the message.settings looks like MentraOS settings (object with known keys), update mentraosSettings
          if (message.settings && typeof message.settings === "object") {
            this.settings.updateMentraosSettings(message.settings);
          }

          // Check if we should update subscriptions
          if (this.shouldUpdateSubscriptionsOnSettingsChange) {
            // Check if any subscription trigger settings changed
            const shouldUpdateSubs = this.subscriptionUpdateTriggers.some(
              (key) => {
                return key in changes;
              },
            );

            if (shouldUpdateSubs) {
              this.updateSubscriptionsFromSettings();
            }
          }
        } else if (isCapabilitiesUpdate(message)) {
          // Update device capabilities
          const capabilitiesMessage = message as CapabilitiesUpdate;
          this.capabilities = capabilitiesMessage.capabilities;
          this.logger.info(
            capabilitiesMessage.capabilities,
            `[AppSession] Capabilities updated for model: ${capabilitiesMessage.modelName}`,
          );

          // Emit capabilities update event for applications to handle
          this.events.emit("capabilities_update", {
            capabilities: capabilitiesMessage.capabilities,
            modelName: capabilitiesMessage.modelName,
            timestamp: capabilitiesMessage.timestamp,
          });
        } else if (isAppStopped(message)) {
          const reason = message.reason || "unknown";
          const displayReason = `App stopped: ${reason}`;

          // Don't emit disconnected event here - let the WebSocket close handler do it
          // This prevents duplicate disconnected events when the session is disposed
          this.logger.info(
            `📤 [${this.config.packageName}] Received APP_STOPPED message: ${displayReason}`,
          );

          // Clear reconnection state
          this.reconnectAttempts = 0;
        }
        // Handle dashboard mode changes
        else if (isDashboardModeChanged(message)) {
          try {
            // Use proper type
            const mode = message.mode || "none";

            // Update dashboard state in the API
            if (this.dashboard && "content" in this.dashboard) {
              (this.dashboard.content as any).setCurrentMode(mode);
            }
          } catch (error) {
            this.logger.error(error, "Error handling dashboard mode change");
          }
        }
        // Handle always-on dashboard state changes
        else if (isDashboardAlwaysOnChanged(message)) {
          try {
            // Use proper type
            const enabled = !!message.enabled;

            // Update dashboard state in the API
            if (this.dashboard && "content" in this.dashboard) {
              (this.dashboard.content as any).setAlwaysOnEnabled(enabled);
            }
          } catch (error) {
            this.logger.error(
              error,
              "Error handling dashboard always-on change",
            );
          }
        }
        // Handle custom messages
        else if (message.type === CloudToAppMessageType.CUSTOM_MESSAGE) {
          this.events.emit("custom_message", message);
          return;
        }
        // Handle App-to-App communication messages
        else if ((message as any).type === "app_message_received") {
          this.appEvents.emit("app_message_received", message as any);
        } else if ((message as any).type === "app_user_joined") {
          this.appEvents.emit("app_user_joined", message as any);
        } else if ((message as any).type === "app_user_left") {
          this.appEvents.emit("app_user_left", message as any);
        } else if ((message as any).type === "app_room_updated") {
          this.appEvents.emit("app_room_updated", message as any);
        } else if ((message as any).type === "app_direct_message_response") {
          const response = message as any;
          if (
            response.messageId &&
            this.pendingDirectMessages.has(response.messageId)
          ) {
            const { resolve } = this.pendingDirectMessages.get(
              response.messageId,
            )!;
            resolve(response.success);
            this.pendingDirectMessages.delete(response.messageId);
          }
        } else if (message.type === "augmentos_settings_update") {
          const mentraosMsg = message as MentraosSettingsUpdate;
          if (
            mentraosMsg.settings &&
            typeof mentraosMsg.settings === "object"
          ) {
            this.settings.updateMentraosSettings(mentraosMsg.settings);
          }
        }
        // Handle 'connection_error' as a specific case if cloud sends this string literal
        else if ((message as any).type === "connection_error") {
          // Treat 'connection_error' (string literal) like AppConnectionError
          // This handles cases where the cloud might send the type as a direct string
          // instead of the enum's 'tpa_connection_error' value.
          const errorMessage =
            (message as any).message ||
            "Unknown connection error (type: connection_error)";
          this.logger.warn(
            `Received 'connection_error' type directly. Consider aligning cloud to send 'tpa_connection_error'. Message: ${errorMessage}`,
          );
          this.events.emit("error", new Error(errorMessage));
        } else if (message.type === "permission_error") {
          // Handle permission errors from cloud
          this.logger.warn(
            {
              message: message.message,
              details: message.details,
              detailsCount: message.details?.length || 0,
              rejectedStreams: message.details?.map((d) => d.stream) || [],
            },
            "Permission error received:",
          );

          // Emit permission error event for application handling
          this.events.emit("permission_error", {
            message: message.message,
            details: message.details,
            timestamp: message.timestamp,
          });

          // Optionally emit individual permission denied events for each stream
          message.details?.forEach((detail) => {
            this.events.emit("permission_denied", {
              stream: detail.stream,
              requiredPermission: detail.requiredPermission,
              message: detail.message,
            });
          });
        } else if (isAudioPlayResponse(message)) {
          // Delegate audio play response handling to the audio module
          if (this.audio) {
            this.audio.handleAudioPlayResponse(message as AudioPlayResponse);
          }
        } else if (isPhotoResponse(message)) {
          // Legacy photo response handling - now photos come directly via webhook
          // This branch can be removed in the future as all photos now go through /photo-upload
          this.logger.warn(
            { message },
            "Received legacy photo response - photos should now come via /photo-upload webhook",
          );
        }
        // Handle unrecognized message types gracefully
        else {
          this.logger.warn(
            `Unrecognized message type: ${(message as any).type}`,
          );
          this.events.emit(
            "error",
            new Error(`Unrecognized message type: ${(message as any).type}`),
          );
        }
      } catch (processingError: unknown) {
        // Catch any errors during message processing to prevent App crashes
        this.logger.error(processingError, "Error processing message:");
        const errorMessage =
          processingError instanceof Error
            ? processingError.message
            : String(processingError);
        this.events.emit(
          "error",
          new Error(`Error processing message: ${errorMessage}`),
        );
      }
    } catch (error: unknown) {
      // Final safety net to ensure the App doesn't crash on any unexpected errors
      this.logger.error(error, "Unexpected error in message handler");
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      this.events.emit(
        "error",
        new Error(`Unexpected error in message handler: ${errorMessage}`),
      );
    }
  }

  /**
   * 🧪 Validate incoming message structure
   * @param message - Message to validate
   * @returns boolean indicating if the message is valid
   */
  private validateMessage(message: CloudToAppMessage): boolean {
    // Handle ArrayBuffer case separately
    if (message instanceof ArrayBuffer) {
      return true; // ArrayBuffers are always considered valid at this level
    }

    // Check if message is null or undefined
    if (!message) {
      return false;
    }

    // Check if message has a type property
    if (!("type" in message)) {
      return false;
    }

    // All other message types should be objects with a type property
    return true;
  }

  /**
   * 📦 Handle binary message data (audio or video)
   * @param buffer - Binary data as ArrayBuffer
   */
  private handleBinaryMessage(buffer: ArrayBuffer): void {
    try {
      // Safety check - only process if we're subscribed to avoid unnecessary work
      if (!this.subscriptions.has(StreamType.AUDIO_CHUNK)) {
        return;
      }

      // Validate buffer has content before processing
      if (!buffer || buffer.byteLength === 0) {
        this.events.emit("error", new Error("Received empty binary message"));
        return;
      }

      // Create a safety wrapped audio chunk with proper defaults
      const audioChunk: AudioChunk = {
        type: StreamType.AUDIO_CHUNK,
        timestamp: new Date(),
        arrayBuffer: buffer,
        sampleRate: 16000, // Default sample rate
      };

      // Emit to subscribers
      this.events.emit(StreamType.AUDIO_CHUNK, audioChunk);
    } catch (error: unknown) {
      this.logger.error(error, "Error processing binary message");
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      this.events.emit(
        "error",
        new Error(`Error processing binary message: ${errorMessage}`),
      );
    }
  }

  /**
   * 🧹 Sanitize event data to prevent crashes from malformed data
   * @param streamType - The type of stream data
   * @param data - The potentially unsafe data to sanitize
   * @returns Sanitized data safe for processing
   */
  private sanitizeEventData(
    streamType: ExtendedStreamType,
    data: unknown,
  ): any {
    try {
      // If data is null or undefined, return an empty object to prevent crashes
      if (data === null || data === undefined) {
        return {};
      }

      // For specific stream types, perform targeted sanitization
      switch (streamType) {
        case StreamType.TRANSCRIPTION: {
          // Ensure text field exists and is a string
          if (typeof (data as TranscriptionData).text !== "string") {
            return {
              text: "",
              isFinal: true,
              startTime: Date.now(),
              endTime: Date.now(),
            };
          }
          break;
        }

        case StreamType.HEAD_POSITION: {
          // Ensure position data has required numeric fields
          // Handle HeadPosition - Note the property position instead of x,y,z
          const pos = data as any;
          if (typeof pos?.position !== "string") {
            return { position: "up", timestamp: new Date() };
          }
          break;
        }

        case StreamType.BUTTON_PRESS: {
          // Ensure button type is valid
          const btn = data as any;
          if (!btn.buttonId || !btn.pressType) {
            return {
              buttonId: "unknown",
              pressType: "short",
              timestamp: new Date(),
            };
          }
          break;
        }
      }

      return data;
    } catch (error: unknown) {
      this.logger.error(error, `Error sanitizing ${streamType} data`);
      // Return a safe empty object if something goes wrong
      return {};
    }
  }

  /**
   * 🔐 Send connection initialization message
   */
  private sendConnectionInit(): void {
    const message: AppConnectionInit = {
      type: AppToCloudMessageType.CONNECTION_INIT,
      sessionId: this.sessionId!,
      packageName: this.config.packageName,
      apiKey: this.config.apiKey,
      timestamp: new Date(),
    };
    this.send(message);
  }

  /**
   * 📝 Update subscription list with cloud
   */
  private updateSubscriptions(): void {
    this.logger.info(
      { subscriptions: JSON.stringify(Array.from(this.subscriptions)) },
      `[AppSession] updateSubscriptions: sending subscriptions to cloud`,
    );

    // [MODIFIED] builds the array of SubscriptionRequest objects to send to the cloud
    const subscriptionPayload: SubscriptionRequest[] = Array.from(
      this.subscriptions,
    ).map((stream) => {
      const rate = this.streamRates.get(stream);
      if (rate && stream === StreamType.LOCATION_STREAM) {
        return { stream: "location_stream", rate: rate as any };
      }
      return stream;
    });

    const message: AppSubscriptionUpdate = {
      type: AppToCloudMessageType.SUBSCRIPTION_UPDATE,
      packageName: this.config.packageName,
      subscriptions: subscriptionPayload, // [MODIFIED]
      sessionId: this.sessionId!,
      timestamp: new Date(),
    };
    this.send(message);
  }

  /**
   * 🔄 Handle reconnection with exponential backoff
   */
  private async handleReconnection(): Promise<void> {
    // Check if reconnection is allowed
    if (!this.config.autoReconnect || !this.sessionId) {
      this.logger.debug(
        `🔄 Reconnection skipped: autoReconnect=${
          this.config.autoReconnect
        }, sessionId=${this.sessionId ? "valid" : "invalid"}`,
      );
      return;
    }

    // Check if we've exceeded the maximum attempts
    const maxAttempts = this.config.maxReconnectAttempts || 3;
    if (this.reconnectAttempts >= maxAttempts) {
      this.logger.info(
        `🔄 Maximum reconnection attempts (${maxAttempts}) reached, giving up`,
      );

      // Emit a permanent disconnection event to trigger onStop in the App server
      this.events.emit("disconnected", {
        message: `Connection permanently lost after ${maxAttempts} failed reconnection attempts`,
        code: 4000, // Custom code for max reconnection attempts exhausted
        reason: "Maximum reconnection attempts exceeded",
        wasClean: false,
        permanent: true, // Flag this as a permanent disconnection
      });

      return;
    }

    // Calculate delay with exponential backoff
    const baseDelay = this.config.reconnectDelay || 1000;
    const delay = baseDelay * Math.pow(2, this.reconnectAttempts);
    this.reconnectAttempts++;

    this.logger.debug(
      `🔄 [${this.config.packageName}] Reconnection attempt ${this.reconnectAttempts}/${maxAttempts} in ${delay}ms`,
    );

    // Use the resource tracker for the timeout
    await new Promise<void>((resolve) => {
      this.resources.setTimeout(() => resolve(), delay);
    });

    try {
      this.logger.debug(
        `🔄 [${this.config.packageName}] Attempting to reconnect...`,
      );
      await this.connect(this.sessionId);
      this.logger.debug(
        `✅ [${this.config.packageName}] Reconnection successful!`,
      );
      this.reconnectAttempts = 0;
    } catch (error) {
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      this.logger.error(
        error,
        `❌ [${this.config.packageName}] Reconnection failed for user ${this.userId}`,
      );
      this.events.emit(
        "error",
        new Error(`Reconnection failed: ${errorMessage}`),
      );

      // Check if this was the last attempt
      if (this.reconnectAttempts >= maxAttempts) {
        this.logger.debug(
          `🔄 [${this.config.packageName}] Final reconnection attempt failed, emitting permanent disconnection`,
        );

        // Emit permanent disconnection event after the last failed attempt
        this.events.emit("disconnected", {
          message: `Connection permanently lost after ${maxAttempts} failed reconnection attempts`,
          code: 4000, // Custom code for max reconnection attempts exhausted
          reason: "Maximum reconnection attempts exceeded",
          wasClean: false,
          permanent: true, // Flag this as a permanent disconnection
        });
      }
    }
  }

  /**
   * 📤 Send message to cloud with validation and error handling
   * @throws {Error} If WebSocket is not connected
   */
  private send(message: AppToCloudMessage): void {
    try {
      // Verify WebSocket connection is valid
      if (!this.ws) {
        throw new Error("WebSocket connection not established");
      }

      if (this.ws.readyState !== 1) {
        const stateMap: Record<number, string> = {
          0: "CONNECTING",
          1: "OPEN",
          2: "CLOSING",
          3: "CLOSED",
        };
        const stateName = stateMap[this.ws.readyState] || "UNKNOWN";
        throw new Error(
          `WebSocket not connected (current state: ${stateName})`,
        );
      }

      // Validate message before sending
      if (!message || typeof message !== "object") {
        throw new Error("Invalid message: must be an object");
      }

      if (!("type" in message)) {
        throw new Error('Invalid message: missing "type" property');
      }

      // Ensure message format is consistent
      if (!("timestamp" in message) || !(message.timestamp instanceof Date)) {
        message.timestamp = new Date();
      }

      // Try to send with error handling
      try {
        const serializedMessage = JSON.stringify(message);
        this.ws.send(serializedMessage);
      } catch (sendError: unknown) {
        const errorMessage =
          sendError instanceof Error ? sendError.message : String(sendError);
        throw new Error(`Failed to send message: ${errorMessage}`);
      }
    } catch (error: unknown) {
      // Log the error and emit an event so App developers are aware
      this.logger.error(error, "Message send error");

      // Ensure we always emit an Error object
      if (error instanceof Error) {
        this.events.emit("error", error);
      } else {
        this.events.emit("error", new Error(String(error)));
      }

      // Re-throw to maintain the original function behavior
      throw error;
    }
  }

  /**
   * Fetch the onboarding instructions for this session from the backend.
   * @returns Promise resolving to the instructions string or null
   */
  public async getInstructions(): Promise<string | null> {
    try {
      const baseUrl = this.getServerUrl();
      const response = await axios.get(`${baseUrl}/api/instructions`, {
        params: { userId: this.userId },
      });
      return response.data.instructions || null;
    } catch (err) {
      this.logger.error(err, `Error fetching instructions from backend`);
      return null;
    }
  }
  // =====================================
  // 👥 App-to-App Communication Interface
  // =====================================

  /**
   * 👥 Discover other users currently using the same App
   * @param includeProfiles - Whether to include user profile information
   * @returns Promise that resolves with list of active users
   */
  async discoverAppUsers(
    domain: string,
    includeProfiles = false,
  ): Promise<any> {
    // Use the domain argument as the base URL if provided
    if (!domain) {
      throw new Error("Domain (API base URL) is required for user discovery");
    }
    const url = `${domain}/api/app-communication/discover-users`;
    // Use the user's core token for authentication
    const appApiKey = this.config.apiKey; // This may need to be updated if you store the core token elsewhere

    if (!appApiKey) {
      throw new Error("Core token (apiKey) is required for user discovery");
    }
    const body = {
      packageName: this.config.packageName,
      userId: this.userId,
      includeUserProfiles: includeProfiles,
    };
    const response = await fetch(url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${appApiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(
        `Failed to discover users: ${response.status} ${response.statusText} - ${errorText}`,
      );
    }
    return await response.json();
  }

  /**
   * 🔍 Check if a specific user is currently active
   * @param userId - User ID to check for
   * @returns Promise that resolves with boolean indicating if user is active
   */
  async isUserActive(userId: string): Promise<boolean> {
    try {
      const userList = await this.discoverAppUsers("", false);
      return userList.users.some((user: any) => user.userId === userId);
    } catch (error) {
      this.logger.error({ error, userId }, "Error checking if user is active");
      return false;
    }
  }

  /**
   * 📊 Get user count for this App
   * @returns Promise that resolves with number of active users
   */
  async getUserCount(domain: string): Promise<number> {
    try {
      const userList = await this.discoverAppUsers(domain, false);
      return userList.totalUsers;
    } catch (error) {
      this.logger.error(error, "Error getting user count");
      return 0;
    }
  }

  /**
   * 📢 Send broadcast message to all users with same App active
   * @param payload - Message payload to send
   * @param roomId - Optional room ID for room-based messaging
   * @returns Promise that resolves when message is sent
   */
  async broadcastToAppUsers(payload: any, _roomId?: string): Promise<void> {
    try {
      const messageId = this.generateMessageId();

      const message = {
        type: "app_broadcast_message",
        packageName: this.config.packageName,
        sessionId: this.sessionId!,
        payload,
        messageId,
        senderUserId: this.userId,
        timestamp: new Date(),
      };

      this.send(message as any);
    } catch (error: unknown) {
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      throw new Error(`Failed to broadcast message: ${errorMessage}`);
    }
  }

  /**
   * 📤 Send direct message to specific user
   * @param targetUserId - User ID to send message to
   * @param payload - Message payload to send
   * @returns Promise that resolves with success status
   */
  async sendDirectMessage(
    targetUserId: string,
    payload: any,
  ): Promise<boolean> {
    return new Promise((resolve, reject) => {
      try {
        const messageId = this.generateMessageId();

        // Store promise resolver
        this.pendingDirectMessages.set(messageId, { resolve, reject });

        const message = {
          type: "app_direct_message",
          packageName: this.config.packageName,
          sessionId: this.sessionId!,
          targetUserId,
          payload,
          messageId,
          senderUserId: this.userId,
          timestamp: new Date(),
        };

        this.send(message as any);

        // Set timeout to avoid hanging promises
        const timeoutMs = 15000; // 15 seconds
        this.resources.setTimeout(() => {
          if (this.pendingDirectMessages.has(messageId)) {
            this.pendingDirectMessages
              .get(messageId)!
              .reject(new Error("Direct message timed out"));
            this.pendingDirectMessages.delete(messageId);
          }
        }, timeoutMs);
      } catch (error: unknown) {
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        reject(new Error(`Failed to send direct message: ${errorMessage}`));
      }
    });
  }

  /**
   * 🏠 Join a communication room for group messaging
   * @param roomId - Room ID to join
   * @param roomConfig - Optional room configuration
   * @returns Promise that resolves when room is joined
   */
  async joinAppRoom(
    roomId: string,
    roomConfig?: {
      maxUsers?: number;
      isPrivate?: boolean;
      metadata?: any;
    },
  ): Promise<void> {
    try {
      const message = {
        type: "app_room_join",
        packageName: this.config.packageName,
        sessionId: this.sessionId!,
        roomId,
        roomConfig,
        timestamp: new Date(),
      };

      this.send(message as any);
    } catch (error: unknown) {
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      throw new Error(`Failed to join room: ${errorMessage}`);
    }
  }

  /**
   * 🚪 Leave a communication room
   * @param roomId - Room ID to leave
   * @returns Promise that resolves when room is left
   */
  async leaveAppRoom(roomId: string): Promise<void> {
    try {
      const message = {
        type: "app_room_leave",
        packageName: this.config.packageName,
        sessionId: this.sessionId!,
        roomId,
        timestamp: new Date(),
      };

      this.send(message as any);
    } catch (error: unknown) {
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      throw new Error(`Failed to leave room: ${errorMessage}`);
    }
  }

  /**
   * 📨 Listen for messages from other App users
   * @param handler - Function to handle incoming messages
   * @returns Cleanup function to remove the handler
   */
  onAppMessage(handler: (message: any) => void): () => void {
    this.appEvents.on("app_message_received", handler);
    return () => this.appEvents.off("app_message_received", handler);
  }

  /**
   * 👋 Listen for user join events
   * @param handler - Function to handle user join events
   * @returns Cleanup function to remove the handler
   */
  onAppUserJoined(handler: (data: any) => void): () => void {
    this.appEvents.on("app_user_joined", handler);
    return () => this.appEvents.off("app_user_joined", handler);
  }

  /**
   * 🚪 Listen for user leave events
   * @param handler - Function to handle user leave events
   * @returns Cleanup function to remove the handler
   */
  onAppUserLeft(handler: (data: any) => void): () => void {
    this.appEvents.on("app_user_left", handler);
    return () => this.appEvents.off("app_user_left", handler);
  }

  /**
   * 🏠 Listen for room update events
   * @param handler - Function to handle room updates
   * @returns Cleanup function to remove the handler
   */
  onAppRoomUpdated(handler: (data: any) => void): () => void {
    this.appEvents.on("app_room_updated", handler);
    return () => this.appEvents.off("app_room_updated", handler);
  }

  /**
   * 🔧 Generate unique message ID
   * @returns Unique message identifier
   */
  private generateMessageId(): string {
    return `msg_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
  }
}

/**
 * @deprecated Use `AppSessionConfig` instead. `TpaSessionConfig` is deprecated and will be removed in a future version.
 * This is an alias for backward compatibility only.
 *
 * @example
 * ```typescript
 * // ❌ Deprecated - Don't use this
 * const config: TpaSessionConfig = { ... };
 *
 * // ✅ Use this instead
 * const config: AppSessionConfig = { ... };
 * ```
 */
export type TpaSessionConfig = AppSessionConfig;

/**
 * @deprecated Use `AppSession` instead. `TpaSession` is deprecated and will be removed in a future version.
 * This is an alias for backward compatibility only.
 *
 * @example
 * ```typescript
 * // ❌ Deprecated - Don't use this
 * const session = new TpaSession(config);
 *
 * // ✅ Use this instead
 * const session = new AppSession(config);
 * ```
 */
export class TpaSession extends AppSession {
  constructor(config: TpaSessionConfig) {
    super(config);
    // Emit a deprecation warning to help developers migrate
    console.warn(
      "⚠️  DEPRECATION WARNING: TpaSession is deprecated and will be removed in a future version. " +
        "Please use AppSession instead. " +
        'Simply replace "TpaSession" with "AppSession" in your code.',
    );
  }
}

// Export module types for developers
export {
  CameraModule,
  PhotoRequestOptions,
  RtmpStreamOptions,
} from "./modules/camera";
export {
  AudioManager,
  AudioPlayOptions,
  AudioPlayResult,
  SpeakOptions,
} from "./modules/audio";
export { SimpleStorage } from "./modules/simple-storage";
