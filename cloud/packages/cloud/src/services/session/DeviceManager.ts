// MentraOS/cloud/packages/cloud/src/services/session/DeviceManager.ts
/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * DeviceManager
 *
 * Session-scoped manager that owns the effective device model and capabilities.
 * It centralizes model/capability updates from:
 *  - Legacy WS: GLASSES_CONNECTION_STATE (authoritative physical connection event)
 *  - New REST: default_wearable (user preference that should take effect immediately)
 *
 * Responsibilities:
 *  - Maintain currentGlassesModel (on UserSession) and capabilities
 *  - Broadcast CAPABILITIES_UPDATE to Apps when capabilities change
 *  - Update User.glassesModels history and PostHog analytics
 *  - Keep legacy WebSocket behavior identical (event names, payload semantics)
 *
 * NOTE:
 *  - This manager does NOT rely on deprecated session flags (phoneConnected/glassesConnected/glassesModel).
 *  - Stopping incompatible Apps is logged as a TODO; the legacy implementation lives on UserSession as a private method.
 */

import WebSocket from "ws";
import type { Logger } from "pino";
import type UserSession from "./UserSession";
import {
  Capabilities,
  CloudToAppMessageType,
  GlassesToCloudMessageType,
} from "@mentra/sdk";
import {
  getCapabilitiesForModel,
  isModelSupported,
} from "../../config/hardware-capabilities";
import { User } from "../../models/user.model";
import { PosthogService } from "../logging/posthog.service";
import appService from "../core/app.service";
import { HardwareCompatibilityService } from "./HardwareCompatibilityService";

const SERVICE_NAME = "DeviceManager";
const FALLBACK_MODEL = "Even Realities G1";

export class DeviceManager {
  private readonly userSession: UserSession;
  private readonly logger: Logger;
  private capabilities: Capabilities | null = null;
  public currentGlassesModel: string | null = null;

  constructor(userSession: UserSession) {
    this.userSession = userSession;
    this.logger = userSession.logger.child({ service: SERVICE_NAME });
    this.logger.info(
      { userId: userSession.userId },
      "DeviceManager initialized",
    );
  }

  // ===== Public API =====

  /**
   * Effective model (from connection event or default_wearable preference).
   */
  getCurrentModel(): string | null {
    return this.currentGlassesModel;
  }

  /**
   * Effective capabilities (derived from current model).
   */
  getCapabilities(): Capabilities | null {
    if (this.capabilities) return this.capabilities;
    const fallback = getCapabilitiesForModel("Even Realities G1");
    return fallback || null;
  }

  /**
   * Check if a specific capability is available
   */
  hasCapability(capability: keyof Capabilities): boolean {
    const caps = this.getCapabilities();
    return caps ? Boolean(caps[capability]) : false;
  }

  /**
   * Handle REST user setting: default_wearable
   * - Updates current model and capabilities immediately
   * - Notifies Apps and stops incompatible Apps (TODO)
   * - Ensures User.glassesModels includes the model
   * - Updates PostHog person properties
   * - Emits "preference_model_changed" event (distinct from connection events)
   */
  async setCurrentModel(modelName: string): Promise<void> {
    const model = String(modelName || "").trim();
    if (!model) {
      this.logger.warn(
        { userId: this.userSession.userId },
        "Ignored empty default_wearable model",
      );
      return;
    }

    this.logger.info(
      { userId: this.userSession.userId, model },
      "Applying default_wearable model preference",
    );

    // Update model + capabilities
    await this.updateModelAndCapabilities(model);

    // Notify Apps and enforce compatibility
    this.sendCapabilitiesUpdateToApps();
    await this.stopIncompatibleApps(/* reason */ "default_wearable_update");

    // Update user model history (append once per unique) and PostHog person properties
    try {
      const user = await User.findOrCreateUser(this.userSession.userId);
      const before = user.getGlassesModels();
      if (!before.includes(model)) {
        await user.addGlassesModel(model);
      }
      const after = user.getGlassesModels();

      await PosthogService.setPersonProperties(this.userSession.userId, {
        current_glasses_model: model,
        glasses_models_used: after,
        glasses_models_count: after.length,
        glasses_preference_last_changed: new Date().toISOString(),
      });

      await PosthogService.trackEvent(
        "preference_model_changed",
        this.userSession.userId,
        {
          sessionId: (this.userSession as any).sessionId,
          modelName: model,
          timestamp: new Date().toISOString(),
        },
      );
    } catch (error) {
      this.logger.error(
        error,
        "Error updating user model history or PostHog for default_wearable",
      );
    }
  }

  /**
   * Handle legacy WS: GLASSES_CONNECTION_STATE
   * - status: "CONNECTED" | "DISCONNECTED" | string
   * - modelName: the physical device model when connected
   */
  async handleGlassesConnectionState(
    modelName: string | null,
    status: string,
  ): Promise<void> {
    const isConnected = status === "CONNECTED";
    const model = modelName ? String(modelName).trim() : null;

    this.logger.info(
      { userId: this.userSession.userId, status, model },
      "Handling GLASSES_CONNECTION_STATE",
    );

    // Maintain microphone connection semantics (legacy behavior)
    try {
      this.userSession.microphoneManager?.handleConnectionStateChange(status);
    } catch (error) {
      this.logger.warn(
        { error, status },
        "MicrophoneManager connection state handler error (continuing)",
      );
    }

    if (isConnected && model) {
      // Update model + capabilities
      await this.updateModelAndCapabilities(model);

      // Notify Apps and enforce compatibility
      this.sendCapabilitiesUpdateToApps();
      await this.stopIncompatibleApps(/* reason */ "glasses_connected");

      // Update user model history + PostHog analytics (preserve legacy semantics)
      try {
        const user = await User.findOrCreateUser(this.userSession.userId);
        const isNewModel = !user.getGlassesModels().includes(model);

        // Append once per unique
        await user.addGlassesModel(model);

        await PosthogService.setPersonProperties(this.userSession.userId, {
          current_glasses_model: model,
          glasses_models_used: user.getGlassesModels(),
          glasses_models_count: user.getGlassesModels().length,
          glasses_last_connected: new Date().toISOString(),
          glasses_current_connected: true,
        });

        if (isNewModel) {
          await PosthogService.trackEvent(
            "glasses_model_first_connect",
            this.userSession.userId,
            {
              sessionId: (this.userSession as any).sessionId,
              modelName: model,
              totalModelsUsed: user.getGlassesModels().length,
              timestamp: new Date().toISOString(),
            },
          );
        }
      } catch (error) {
        this.logger.error(
          error,
          "Error updating user model history or PostHog for GLASSES_CONNECTION_STATE CONNECTED",
        );
      }
    } else if (!isConnected) {
      // PostHog disconnection property update (preserve legacy semantics)
      try {
        await PosthogService.setPersonProperties(this.userSession.userId, {
          glasses_current_connected: false,
        });
      } catch (error) {
        this.logger.error(
          error,
          "Error updating PostHog on GLASSES_CONNECTION_STATE DISCONNECTED",
        );
      }
    }

    // Track the connection state event (legacy event naming)
    try {
      await PosthogService.trackEvent(
        GlassesToCloudMessageType.GLASSES_CONNECTION_STATE,
        this.userSession.userId,
        {
          sessionId: (this.userSession as any).sessionId,
          eventType: GlassesToCloudMessageType.GLASSES_CONNECTION_STATE,
          timestamp: new Date().toISOString(),
          connectionState: { modelName: model, status },
          modelName: model,
          isConnected,
        },
      );
    } catch (error) {
      this.logger.error(
        error,
        "Error tracking GLASSES_CONNECTION_STATE event in PostHog",
      );
    }
  }

  /**
   * Dispose any internal state (none currently).
   */
  dispose(): void {
    // No timers or background tasks at this time.
  }

  // ===== Internal helpers =====

  /**
   * Update currentGlassesModel and capabilities on the session using capability profiles.
   * - Falls back to a known default if the model is unknown.
   */
  private async updateModelAndCapabilities(modelName: string): Promise<void> {
    const model = String(modelName || "").trim();
    if (!model) return;

    if (this.currentGlassesModel === model) {
      this.logger.debug(
        { model },
        "Model unchanged; skipping capability refresh",
      );
      return;
    }

    this.logger.info(
      {
        previousModel: this.currentGlassesModel,
        newModel: model,
        userId: this.userSession.userId,
      },
      "Updating currentGlassesModel",
    );

    // Update current model
    this.currentGlassesModel = model;

    // Derive capabilities
    let caps: Capabilities | null = getCapabilitiesForModel(model);
    if (!caps) {
      this.logger.warn(
        { model },
        "No capabilities found for model; applying fallback",
      );
      const fallback = isModelSupported(FALLBACK_MODEL)
        ? getCapabilitiesForModel(FALLBACK_MODEL)
        : null;
      if (fallback) {
        caps = fallback;
        this.logger.info(
          { model, fallback: FALLBACK_MODEL },
          "Applied fallback capabilities for unknown model",
        );
      }
    }

    this.capabilities = caps || null;
  }

  /**
   * Broadcast CAPABILITIES_UPDATE to all connected Apps with current capabilities and model.
   */
  private sendCapabilitiesUpdateToApps(): void {
    try {
      const capabilities = this.getCapabilities();
      const modelName = this.currentGlassesModel;

      const message = {
        type: CloudToAppMessageType.CAPABILITIES_UPDATE,
        capabilities,
        modelName,
      };

      // Broadcast to all connected App websockets
      for (const [
        packageName,
        ws,
      ] of this.userSession.appWebsockets.entries()) {
        if (ws && ws.readyState === WebSocket.OPEN) {
          try {
            ws.send(JSON.stringify(message));
          } catch (sendError) {
            const _logger = this.logger.child({ packageName, message });
            this.logger.error(
              sendError,
              "Error sending CAPABILITIES_UPDATE to App",
            );
          }
        }
      }

      this.logger.info(
        {
          userId: this.userSession.userId,
          modelName,
          hasCapabilities: Boolean(capabilities),
          appCount: this.userSession.appWebsockets.size,
        },
        "Broadcasted CAPABILITIES_UPDATE to Apps",
      );
    } catch (error) {
      this.logger.error(error, "Error broadcasting CAPABILITIES_UPDATE");
    }
  }

  /**
   * Stop any running apps that are incompatible with the current capabilities.
   * Fully implemented here using HardwareCompatibilityService and appService.
   * Preserves legacy logging semantics and uses AppManager to stop apps.
   */
  public async stopIncompatibleApps(
    reason: string = "capabilities_changed",
  ): Promise<void> {
    try {
      const capabilities = this.getCapabilities();
      if (!capabilities) {
        this.logger.debug(
          "[DeviceManager:stopIncompatibleApps] No capabilities available, skipping compatibility check",
        );
        return;
      }

      const runningAppPackages = Array.from(this.userSession.runningApps || []);
      if (runningAppPackages.length === 0) {
        this.logger.debug(
          "[DeviceManager:stopIncompatibleApps] No running apps to check for compatibility",
        );
        return;
      }

      this.logger.info(
        `[DeviceManager:stopIncompatibleApps] Checking compatibility for ${runningAppPackages.length} running apps with current capabilities`,
      );

      const incompatibleApps: string[] = [];

      for (const packageName of runningAppPackages) {
        try {
          const app = await appService.getApp(packageName);
          if (!app) {
            this.logger.warn(
              `[DeviceManager:stopIncompatibleApps] Could not find app details for ${packageName}, keeping it running`,
            );
            continue;
          }

          const compatibilityResult =
            HardwareCompatibilityService.checkCompatibility(app, capabilities);

          if (!compatibilityResult.isCompatible) {
            incompatibleApps.push(packageName);
            this.logger.warn(
              {
                packageName,
                missingHardware: compatibilityResult.missingRequired,
                capabilities,
                modelName: this.currentGlassesModel,
              },
              `[DeviceManager:stopIncompatibleApps] App ${packageName} is now incompatible with ${this.currentGlassesModel} - missing required hardware: ${compatibilityResult.missingRequired
                .map((req) => req.type)
                .join(", ")}`,
            );
          }
        } catch (error) {
          this.logger.error(
            error as Error,
            `[DeviceManager:stopIncompatibleApps] Error checking compatibility for app ${packageName}`,
          );
        }
      }

      if (incompatibleApps.length > 0) {
        this.logger.info(
          {
            incompatibleApps,
            modelName: this.currentGlassesModel,
            reason,
          },
          `[DeviceManager:stopIncompatibleApps] Stopping ${incompatibleApps.length} incompatible apps due to device capability change`,
        );

        const stopPromises = incompatibleApps.map(async (packageName) => {
          try {
            await this.userSession.appManager.stopApp(packageName);
            this.logger.info(
              `[DeviceManager:stopIncompatibleApps] Successfully stopped incompatible app ${packageName}`,
            );
          } catch (error) {
            this.logger.error(
              error as Error,
              `[DeviceManager:stopIncompatibleApps] Failed to stop incompatible app ${packageName}`,
            );
          }
        });

        await Promise.allSettled(stopPromises);

        this.logger.info(
          `[DeviceManager:stopIncompatibleApps] Completed stopping incompatible apps. Device change to ${this.currentGlassesModel} processed.`,
        );
      } else {
        this.logger.info(
          `[DeviceManager:stopIncompatibleApps] All running apps are compatible with ${this.currentGlassesModel}`,
        );
      }
    } catch (error) {
      this.logger.error(
        error as Error,
        "[DeviceManager:stopIncompatibleApps] Error during incompatible app cleanup",
      );
    }
  }
}

export default DeviceManager;
