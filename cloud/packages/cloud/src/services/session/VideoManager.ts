/**
 * @fileoverview VideoManager manages RTMP streaming within a user session.
 * Simplified from complex multi-timeout system to proven stream-tracker.service.ts patterns.
 */

import WebSocket from "ws";
import {
  CloudToGlassesMessageType,
  CloudToAppMessageType,
  AppToCloudMessageType,
  RtmpStreamStatus, // SDK type for status from glasses
  KeepAliveAck, // SDK type for ACK from glasses
  StartRtmpStream, // SDK type for command to glasses
  StopRtmpStream, // SDK type for command to glasses
  KeepRtmpStreamAlive, // SDK type for command to glasses
  VideoConfig, // SDK type
  AudioConfig, // SDK type
  StreamConfig, // SDK type
  RtmpStreamRequest, // SDK type for App request
  RtmpStreamStopRequest,
  GlassesToCloudMessageType, // SDK type for App request
} from "@mentra/sdk";
import { Logger } from "pino";
import UserSession from "./UserSession";
// session.service no longer needed; using UserSession instance methods

// Constants from the original stream-tracker.service.ts
const KEEP_ALIVE_INTERVAL_MS = 15000; // 15 seconds keep-alive interval
const STREAM_TIMEOUT_MS = 60000; // 60 seconds timeout (should match glasses timeout)
// Increased to tolerate BLE/mobile handoff jitter
const ACK_TIMEOUT_MS = 10000; // 10 seconds to wait for ACK
const MAX_MISSED_ACKS = 3; // Max consecutive missed ACKs before considering connection suspect

/**
 * Simplified stream information based on the proven stream-tracker.service.ts logic
 */
interface SessionStreamInfo {
  streamId: string;
  packageName: string; // Renamed from appId for consistency with App messages
  rtmpUrl: string;
  status: "initializing" | "active" | "stopping" | "stopped" | "timeout";
  startTime: Date;
  lastKeepAlive: Date; // Tracks any activity
  keepAliveTimer?: NodeJS.Timeout; // Single timer for keep-alive interval
  pendingAcks: Map<string, { sentAt: Date; timeout: NodeJS.Timeout }>; // Simplified to match original
  missedAcks: number;
  options: {
    // To store configs passed by App
    video?: VideoConfig;
    audio?: AudioConfig;
    stream?: StreamConfig;
  };
}

export class VideoManager {
  private userSession: UserSession;
  private logger: Logger;
  private activeSessionStreams: Map<string, SessionStreamInfo> = new Map(); // streamId -> SessionStreamInfo

  constructor(userSession: UserSession) {
    this.userSession = userSession;
    this.logger = userSession.logger.child({ service: "VideoManager" });
    this.logger.info("VideoManager initialized");
  }

  /**
   * Start tracking a new RTMP stream (simplified from original stream-tracker logic)
   */
  async startRtmpStream(request: RtmpStreamRequest): Promise<string> {
    const {
      packageName,
      rtmpUrl,
      video,
      audio,
      stream: streamOptions,
    } = request;
    this.logger.info(
      {
        debugKey: "RTMP_STREAM_START_REQUEST",
        packageName,
        rtmpUrl,
        hasVideo: !!video,
        hasAudio: !!audio,
        hasStreamOptions: !!streamOptions,
        currentActiveStreams: this.activeSessionStreams.size,
        sessionId: this.userSession.sessionId,
        userId: this.userSession.userId,
      },
      "RTMP_STREAM_START_REQUEST: VideoManager starting RTMP stream tracking request",
    );

    // Basic validation
    if (!this.userSession.appManager.isAppRunning(packageName)) {
      throw new Error(`App ${packageName} is not running`);
    }
    if (
      !rtmpUrl ||
      (!rtmpUrl.startsWith("rtmp://") && !rtmpUrl.startsWith("rtmps://"))
    ) {
      throw new Error("Invalid RTMP URL");
    }
    if (
      !this.userSession.websocket ||
      this.userSession.websocket.readyState !== WebSocket.OPEN
    ) {
      throw new Error("Glasses WebSocket not connected");
    }

    // Check for managed stream conflicts and stop if exists
    if (
      this.userSession.managedStreamingExtension.checkUnmanagedStreamConflict(
        this.userSession.userId,
      )
    ) {
      // Stop the managed stream instead of throwing error
      this.logger.info(
        {
          userId: this.userSession.userId,
          packageName,
          debugKey: "STOPPING_MANAGED_STREAM_FOR_UNMANAGED",
        },
        "STOPPING_MANAGED_STREAM_FOR_UNMANAGED: Stopping existing managed stream to start new unmanaged stream",
      );

      // Get current managed stream viewers for this user and stop them
      const activeViewers =
        this.userSession.managedStreamingExtension.getManagedStreamViewers(
          this.userSession.userId,
        );
      for (const viewerPackageName of activeViewers) {
        await this.userSession.managedStreamingExtension.stopManagedStream(
          this.userSession,
          {
            type: AppToCloudMessageType.MANAGED_STREAM_STOP,
            packageName: viewerPackageName,
          },
        );
      }
    }

    // Shorter streamId for BLE efficiency
    const streamId = `s${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 6)}`;

    // Stop ALL existing unmanaged streams for this user (not just for this app)
    this.logger.info(
      {
        userId: this.userSession.userId,
        packageName,
        activeStreams: this.activeSessionStreams.size,
        debugKey: "STOPPING_ALL_UNMANAGED_STREAMS",
      },
      "STOPPING_ALL_UNMANAGED_STREAMS: Stopping all existing unmanaged streams before starting new one",
    );

    // Stop all active streams for this session
    for (const [existingStreamId, stream] of this.activeSessionStreams) {
      if (["initializing", "active"].includes(stream.status)) {
        this.logger.debug(
          {
            streamId: existingStreamId,
            packageName: stream.packageName,
            status: stream.status,
          },
          "Stopping existing unmanaged stream",
        );
        await this.updateStatus(existingStreamId, "stopped");
      }
    }

    const now = new Date();
    const streamInfo: SessionStreamInfo = {
      streamId,
      packageName,
      rtmpUrl,
      status: "initializing",
      startTime: now,
      lastKeepAlive: now,
      pendingAcks: new Map(),
      missedAcks: 0,
      options: { video, audio, stream: streamOptions },
    };

    this.activeSessionStreams.set(streamId, streamInfo);

    // Don't start keep-alives yet - wait for first status from glasses
    // This prevents keep-alives from arriving before the glasses are ready
    this.logger.debug(
      { streamId },
      "Stream created, waiting for first status before starting keep-alives",
    );

    // Send start command to glasses
    const startMessage: StartRtmpStream = {
      type: CloudToGlassesMessageType.START_RTMP_STREAM,
      sessionId: this.userSession.sessionId,
      rtmpUrl,
      appId: packageName,
      streamId,
      video: video || {},
      audio: audio || {},
      stream: streamOptions || {},
      timestamp: now,
    };

    try {
      const messageSize = JSON.stringify(startMessage).length;
      this.logger.debug(
        {
          debugKey: "RTMP_STREAM_SEND_START_CMD",
          streamId,
          messageSize,
          packageName,
          rtmpUrl,
          sessionId: this.userSession.sessionId,
        },
        "RTMP_STREAM_SEND_START_CMD: VideoManager sending START_RTMP_STREAM message to glasses",
      );

      this.userSession.websocket.send(JSON.stringify(startMessage));
      this.logger.info(
        {
          debugKey: "RTMP_STREAM_START_CMD_SENT",
          streamId,
          packageName,
          rtmpUrl,
          sessionId: this.userSession.sessionId,
        },
        "RTMP_STREAM_START_CMD_SENT: VideoManager ✅ START_RTMP_STREAM successfully sent to glasses",
      );

      // Tell App we're starting (but not active yet)
      this.logger.debug(
        {
          debugKey: "RTMP_STREAM_NOTIFY_APP_INIT",
          streamId,
          sessionId: this.userSession.sessionId,
        },
        "RTMP_STREAM_NOTIFY_APP_INIT: VideoManager notifying App that stream is initializing",
      );
      await this.sendStreamStatusToApp(streamId, "initializing");
    } catch (error) {
      this.logger.error(
        {
          debugKey: "RTMP_STREAM_START_CMD_FAIL",
          error,
          streamId,
          packageName,
          sessionId: this.userSession.sessionId,
        },
        "RTMP_STREAM_START_CMD_FAIL: VideoManager ❌ Failed to send START_RTMP_STREAM to glasses",
      );
      this.stopTracking(streamId);
      throw error;
    }

    this.logger.info(
      {
        debugKey: "RTMP_STREAM_TRACKING_STARTED",
        streamId,
        packageName,
        rtmpUrl,
        sessionId: this.userSession.sessionId,
      },
      "RTMP_STREAM_TRACKING_STARTED: VideoManager 🎬 RTMP stream tracking started successfully",
    );
    return streamId;
  }

  /**
   * Update stream status (simplified from original)
   */
  async updateStatus(
    streamId: string,
    status: SessionStreamInfo["status"],
  ): Promise<void> {
    const stream = this.activeSessionStreams.get(streamId);
    if (stream) {
      this.logger.info(
        { streamId, oldStatus: stream.status, newStatus: status },
        "Updating stream status",
      );
      stream.status = status;
      stream.lastKeepAlive = new Date();

      // Tell App about status change
      await this.sendStreamStatusToApp(streamId, status);

      // If stream becomes active, ensure keep-alive is running
      if (status === "active" && !stream.keepAliveTimer) {
        this.scheduleKeepAlive(streamId);
      }

      // If stream is stopped or timed out, stop tracking
      if (status === "stopped" || status === "timeout") {
        this.stopTracking(streamId);
      }
    } else {
      this.logger.warn(
        { streamId },
        "Attempted to update status for unknown stream",
      );
    }
  }

  /**
   * Stop tracking a stream and clean up resources (from original)
   */
  stopTracking(streamId: string): void {
    const stream = this.activeSessionStreams.get(streamId);
    if (stream) {
      this.logger.info({ streamId }, "Stopping stream tracking");

      // Cancel keep-alive timer
      if (stream.keepAliveTimer) {
        clearInterval(stream.keepAliveTimer);
      }

      // Cancel all pending ACK timeouts
      for (const [, ackInfo] of stream.pendingAcks) {
        clearTimeout(ackInfo.timeout);
      }
      stream.pendingAcks.clear();

      this.activeSessionStreams.delete(streamId);
    }
  }

  /**
   * Stop streams by package name
   */
  stopStreamsByPackageName(packageName: string): void {
    for (const [streamId, stream] of this.activeSessionStreams) {
      if (stream.packageName === packageName) {
        this.updateStatus(streamId, "stopped");
      }
    }
  }

  /**
   * Check if a stream is active (from original)
   */
  isStreamActive(streamId: string): boolean {
    const stream = this.activeSessionStreams.get(streamId);
    return stream ? ["initializing", "active"].includes(stream.status) : false;
  }

  /**
   * Get information about any active unmanaged streams for this session
   * Returns the first active stream found (there should only be one)
   */
  getActiveStreamInfo(): SessionStreamInfo | undefined {
    for (const [streamId, stream] of this.activeSessionStreams) {
      if (["initializing", "active"].includes(stream.status)) {
        return stream;
      }
    }
    return undefined;
  }

  /**
   * Schedule keep-alive message for a stream (from original)
   */
  private scheduleKeepAlive(streamId: string): void {
    const stream = this.activeSessionStreams.get(streamId);
    if (!stream) {
      this.logger.warn(
        { streamId },
        "Cannot schedule keep-alive for unknown stream",
      );
      return;
    }

    // Cancel existing timer
    if (stream.keepAliveTimer) {
      clearInterval(stream.keepAliveTimer);
    }

    // Schedule periodic keep-alive
    stream.keepAliveTimer = setInterval(() => {
      this.sendKeepAlive(streamId);
    }, KEEP_ALIVE_INTERVAL_MS);

    this.logger.debug(
      { streamId },
      `Scheduled keep-alive every ${KEEP_ALIVE_INTERVAL_MS}ms`,
    );
  }

  /**
   * Send keep-alive message to glasses (from original logic)
   */
  private sendKeepAlive(streamId: string): void {
    const stream = this.activeSessionStreams.get(streamId);
    if (!stream) {
      this.logger.warn(
        { streamId },
        "Cannot send keep-alive for unknown stream",
      );
      return;
    }

    // Check if stream is still active
    if (!["initializing", "active"].includes(stream.status)) {
      this.logger.debug(
        { streamId, status: stream.status },
        "Skipping keep-alive for inactive stream",
      );
      this.stopTracking(streamId);
      return;
    }

    // If glasses are temporarily disconnected, do NOT stop the stream.
    // Skip sending keep-alive and wait for reconnection.
    if (
      !this.userSession.websocket ||
      this.userSession.websocket.readyState !== WebSocket.OPEN
    ) {
      this.logger.warn(
        { streamId, pendingCount: stream.pendingAcks.size },
        "Glasses disconnected, skipping keep-alive until reconnect",
      );
      return;
    }

    // Check if we've missed too many ACKs (but don't stop sending keep-alives immediately)
    const timeSinceLastActivity = Date.now() - stream.lastKeepAlive.getTime();
    if (
      timeSinceLastActivity > STREAM_TIMEOUT_MS &&
      stream.missedAcks >= MAX_MISSED_ACKS
    ) {
      this.logger.warn(
        { streamId, timeSinceLastActivity, missedAcks: stream.missedAcks },
        "Stream has timed out after missing multiple ACKs",
      );
      this.updateStatus(streamId, "timeout");
      return;
    }

    // Log warning if we haven't received ACKs but continue sending keep-alives
    if (timeSinceLastActivity > KEEP_ALIVE_INTERVAL_MS * 2) {
      this.logger.warn(
        {
          streamId,
          timeSinceLastActivity,
          missedAcks: stream.missedAcks,
          pendingAcks: stream.pendingAcks.size,
        },
        "No ACKs received recently, but continuing to send keep-alives",
      );
    }

    // Generate short ACK ID for BLE efficiency
    const ackId = `a${Date.now().toString(36).slice(-5)}`;
    this.logger.debug(
      { streamId, ackId, pendingBefore: stream.pendingAcks.size },
      "Tracking keep-alive ACK",
    );
    this.trackKeepAliveAck(streamId, ackId);

    // Send keep-alive message (minimal size for BLE)
    const keepAliveMsg: KeepRtmpStreamAlive = {
      type: CloudToGlassesMessageType.KEEP_RTMP_STREAM_ALIVE,
      streamId,
      ackId,
    };

    try {
      this.userSession.websocket.send(JSON.stringify(keepAliveMsg));
      this.logger.debug(
        { streamId, ackId, pendingAfter: stream.pendingAcks.size },
        "KEEP_RTMP_STREAM_ALIVE sent",
      );
    } catch (error) {
      this.logger.error({ error, streamId }, "Failed to send keep-alive");
      this.updateStatus(streamId, "stopped");
    }
  }

  /**
   * Track a sent keep-alive ACK (from original)
   */
  private trackKeepAliveAck(streamId: string, ackId: string): void {
    const stream = this.activeSessionStreams.get(streamId);
    if (!stream) return;

    const createdAt = new Date();
    const timeout = setTimeout(() => {
      this.handleMissedKeepAliveAck(streamId, ackId, createdAt);
    }, ACK_TIMEOUT_MS);

    stream.pendingAcks.set(ackId, {
      sentAt: new Date(),
      timeout,
    });
    this.logger.debug(
      {
        streamId,
        ackId,
        pendingCount: stream.pendingAcks.size,
        ackTimeoutMs: ACK_TIMEOUT_MS,
      },
      "ACK tracking stored",
    );
  }

  /**
   * Handle missed keep-alive ACK (from original)
   */
  private handleMissedKeepAliveAck(
    streamId: string,
    ackId: string,
    createdAt: Date,
  ): void {
    const stream = this.activeSessionStreams.get(streamId);
    if (!stream || !["initializing", "active"].includes(stream.status)) return;

    // If websocket is not open anymore, ignore this timeout (it will be retried on reconnect)
    if (
      !this.userSession.websocket ||
      this.userSession.websocket.readyState !== WebSocket.OPEN
    ) {
      stream.pendingAcks.delete(ackId);
      this.logger.warn(
        { streamId, ackId },
        "Ignoring ACK timeout due to websocket closed",
      );
      return;
    }

    if (stream.pendingAcks.has(ackId)) {
      stream.pendingAcks.delete(ackId);
      stream.missedAcks++;
      const ageMs = Date.now() - createdAt.getTime();
      this.logger.warn(
        { streamId, ackId, missedAcks: stream.missedAcks, ageMs },
        "Keep-alive ACK timeout",
      );

      if (stream.missedAcks >= MAX_MISSED_ACKS) {
        // Don't immediately timeout - let sendKeepAlive handle the timeout logic
        // which considers both missed ACKs AND time since last activity
        this.logger.warn(
          { streamId, missedAcks: stream.missedAcks },
          "Too many missed ACKs, stream may timeout if no activity soon",
        );
      }
    }
  }

  /**
   * Handle keep-alive ACK from glasses (from original)
   */
  handleKeepAliveAck(ackMessage: KeepAliveAck): void {
    const { streamId, ackId } = ackMessage;
    this.logger.debug(
      { ackMessage, debugKey: "KEEP_ALIVE_ACK_RECEIVED" },
      "KEEP_ALIVE_ACK_RECEIVED Handling keep-alive ACK from glasses",
    );
    const stream = this.activeSessionStreams.get(streamId);
    if (!stream) {
      this.logger.warn({ streamId, ackId }, "Received ACK for unknown stream");
      return;
    }

    const pendingAck = stream.pendingAcks.get(ackId);
    if (pendingAck) {
      clearTimeout(pendingAck.timeout);
      stream.pendingAcks.delete(ackId);
      stream.missedAcks = 0; // Reset on successful ACK
      stream.lastKeepAlive = new Date(); // Update activity time
      const ageMs = Date.now() - pendingAck.sentAt.getTime();
      this.logger.debug(
        { streamId, ackId, pendingAfter: stream.pendingAcks.size, ageMs },
        "Keep-alive ACK received",
      );
    } else {
      this.logger.warn({ streamId, ackId }, "Received unknown or late ACK");
    }
  }

  /**
   * Handle stream status update from glasses (simplified)
   */
  handleRtmpStreamStatus(statusMessage: RtmpStreamStatus): void {
    const { streamId, status } = statusMessage;
    this.logger.debug(
      { streamId, status, debugKey: "RTMP_STREAM_STATUS" },
      "RTMP_STREAM_STATUS Handling RTMP stream status update",
    );

    if (!streamId) {
      this.logger.warn(
        { statusMessage },
        "Received status message without streamId",
      );
      return;
    }

    const stream = this.activeSessionStreams.get(streamId);
    if (!stream) {
      this.logger.warn(
        { streamId, status },
        "Received status for unknown stream",
      );
      return;
    }

    // Update last activity time
    stream.lastKeepAlive = new Date();

    // Start keep-alives on first real status (not initializing)
    if (
      !stream.keepAliveTimer &&
      (status === "streaming" ||
        status === "active" ||
        status === "reconnected")
    ) {
      this.logger.info(
        { streamId, status },
        "Received first active status, starting keep-alive timer",
      );
      this.scheduleKeepAlive(streamId);
      // Send first keep-alive after a short delay
      setTimeout(() => this.sendKeepAlive(streamId), 1000);
    }

    // Map glasses status to our status types
    let mappedStatus: SessionStreamInfo["status"];
    if (!status) {
      this.logger.warn(
        { streamId },
        "Received status message without status value",
      );
      return;
    }

    switch (status) {
      case "initializing":
      case "connecting":
      case "reconnecting":
        mappedStatus = "initializing";
        break;
      case "active":
      case "streaming":
        mappedStatus = "active";
        break;
      case "stopping":
        mappedStatus = "stopping";
        break;
      case "stopped":
      case "disconnected":
        mappedStatus = "stopped";
        break;
      case "error":
        // For errors, we should stop the stream, not timeout
        mappedStatus = "stopped";
        this.logger.error(
          { streamId, status },
          "Stream error received from glasses",
        );
        break;
      case "timeout":
        // Only map to timeout if it's actually a timeout from glasses
        mappedStatus = "timeout";
        break;
      case "reconnected":
        // Successfully reconnected after disconnection - stream is active again
        mappedStatus = "active";
        this.logger.info(
          { streamId, status },
          "Stream reconnected successfully",
        );
        break;
      case "reconnect_failed":
        // Failed to reconnect after max attempts - stream is dead
        mappedStatus = "stopped";
        this.logger.warn({ streamId, status }, "Stream reconnection failed");
        break;
      default:
        // Unknown status - log warning but don't ignore
        // Default to stopped to prevent state limbo
        this.logger.warn(
          { streamId, status },
          "Received unknown status from glasses, defaulting to stopped",
        );
        mappedStatus = "stopped";
        break;
    }

    // Update status based on glasses feedback
    this.updateStatus(streamId, mappedStatus);
  }

  /**
   * Handles a request from a App to stop an RTMP stream.
   */
  async stopRtmpStream(request: RtmpStreamStopRequest): Promise<void> {
    const { packageName, streamId } = request;
    this.logger.info(
      { packageName, streamId },
      "Processing stop RTMP stream request",
    );

    if (streamId) {
      // Stop specific stream
      const stream = this.activeSessionStreams.get(streamId);
      if (stream && stream.packageName === packageName) {
        this.updateStatus(streamId, "stopped");
      } else if (stream) {
        throw new Error(
          `App ${packageName} cannot stop stream ${streamId} owned by ${stream.packageName}`,
        );
      } else {
        this.logger.warn(
          { streamId, packageName },
          "Request to stop non-existent stream",
        );
      }
    } else {
      // Stop all streams for this package
      this.stopStreamsByPackageName(packageName);
    }

    // Send stop command to glasses if WebSocket is connected
    if (
      this.userSession.websocket &&
      this.userSession.websocket.readyState === WebSocket.OPEN
    ) {
      const stopMessage: StopRtmpStream = {
        type: CloudToGlassesMessageType.STOP_RTMP_STREAM,
        sessionId: this.userSession.sessionId,
        appId: packageName,
        streamId: streamId || "",
        timestamp: new Date(),
      };

      try {
        this.userSession.websocket.send(JSON.stringify(stopMessage));
        this.logger.info(
          { packageName, streamId },
          "STOP_RTMP_STREAM sent to glasses",
        );
      } catch (error) {
        this.logger.error(
          { error, packageName, streamId },
          "Failed to send stop command to glasses",
        );
      }
    }
  }

  /**
   * Sends stream status to the owning App and broadcasts to other subscribers.
   */
  private async sendStreamStatusToApp(
    streamId: string,
    status: RtmpStreamStatus["status"], // This is the status string from SDK
    errorDetails?: string,
    stats?: RtmpStreamStatus["stats"],
  ): Promise<void> {
    const streamInfo = this.activeSessionStreams.get(streamId);
    // It's possible streamInfo is gone if cleanup happened due to rapid events.
    const packageName = streamInfo
      ? streamInfo.packageName
      : "unknown_package_owner";

    // Direct message to the App that owns the stream
    const appOwnerMessage = {
      type: CloudToAppMessageType.RTMP_STREAM_STATUS,
      sessionId: `${this.userSession.sessionId}-${packageName}`,
      streamId,
      status, // The SDK status string
      errorDetails,
      stats,
      appId: packageName, // Clarify which app this status pertains to
      timestamp: new Date(),
    };

    // Send status to owning App using centralized messaging
    try {
      const result = await this.userSession.appManager.sendMessageToApp(
        packageName,
        appOwnerMessage,
      );

      if (result.sent) {
        this.logger.debug(
          {
            streamId,
            status,
            target: packageName,
            resurrectionTriggered: result.resurrectionTriggered,
          },
          `Sent RTMP status to owning App ${packageName}${result.resurrectionTriggered ? " after resurrection" : ""}`,
        );
      } else {
        this.logger.warn(
          {
            streamId,
            status,
            target: packageName,
            resurrectionTriggered: result.resurrectionTriggered,
            error: result.error,
          },
          `Failed to send RTMP status to owning App ${packageName}`,
        );
      }
    } catch (error) {
      this.logger.error(
        {
          error: error instanceof Error ? error.message : String(error),
          streamId,
          target: packageName,
        },
        `Error sending RTMP status to owning App ${packageName}`,
      );
    }

    // Broadcast DataStream to other subscribed Apps
    const broadcastPayload: RtmpStreamStatus = {
      type: GlassesToCloudMessageType.RTMP_STREAM_STATUS,
      sessionId: this.userSession.sessionId,
      streamId,
      status,
      errorDetails,
      appId: packageName,
      stats,
      timestamp: new Date(),
    };

    // Relay to Apps who subscribed to this RTMP stream
    this.userSession.relayMessageToApps(broadcastPayload);

    this.logger.debug(
      { streamId, status },
      "Broadcast RTMP status via DataStream",
    );
  }

  /**
   * Called when the UserSession is ending.
   */
  dispose(): void {
    this.logger.info(
      "Disposing VideoManager, stopping all active streams for this session",
    );
    const streamIdsToStop = Array.from(this.activeSessionStreams.keys());
    streamIdsToStop.forEach((streamId) => {
      this.stopTracking(streamId);
    });
    this.activeSessionStreams.clear();
  }
}

export default VideoManager;
