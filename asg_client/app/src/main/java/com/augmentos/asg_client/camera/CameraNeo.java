package com.augmentos.asg_client.camera;

import com.augmentos.asg_client.io.media.core.CircularVideoBufferInternal;
import com.augmentos.asg_client.io.hardware.interfaces.IHardwareManager;
import com.augmentos.asg_client.service.utils.ServiceUtils;
import com.augmentos.asg_client.io.hardware.core.HardwareManagerFactory;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;

import com.augmentos.asg_client.settings.VideoSettings;
import android.view.WindowManager;

import com.augmentos.asg_client.utils.WakeLockManager;
import com.augmentos.asg_client.io.storage.StorageManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.augmentos.asg_client.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraNeo extends LifecycleService {
    private static final String TAG = "CameraNeo";
    private static final String CHANNEL_ID = "CameraNeoServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    // =======================================================================
    // STATIC STATE MANAGEMENT FOR TRUE SINGLETON PATTERN
    // =======================================================================
    
    // Static state flags - set IMMEDIATELY to prevent race conditions
    private static volatile boolean isServiceStarting = false;
    private static volatile boolean isServiceRunning = false;
    private static volatile boolean isCameraReady = false;
    private static final Object SERVICE_LOCK = new Object();
    
    // Global request queue - survives service lifecycle
    private static final Queue<PhotoRequest> globalRequestQueue = new LinkedList<>();
    
    // Callback registry - maintains callbacks across requests
    private static final Map<String, PhotoCaptureCallback> callbackRegistry = new HashMap<>();
    
    // Service state for debugging
    private static enum ServiceState { 
        IDLE,        // No service exists
        STARTING,    // Service created but camera not initialized  
        RUNNING,     // Camera initialized and ready
        STOPPING     // Service is shutting down
    }
    private static volatile ServiceState serviceState = ServiceState.IDLE;
    
    // =======================================================================

    // Camera variables
    private CameraDevice cameraDevice = null;
    private CaptureRequest.Builder previewBuilder; // Separate builder for preview
    private CameraCaptureSession cameraCaptureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Semaphore cameraOpenCloseLock = new Semaphore(1);
    private Size jpegSize;
    private String cameraId;

    // Target photo resolution (4:3 landscape orientation)
    private static final int TARGET_WIDTH = 1440;
    private static final int TARGET_HEIGHT = 1080;
    private static final int TARGET_WIDTH_SMALL = 800;
    private static final int TARGET_HEIGHT_SMALL = 600;
    private static final int TARGET_WIDTH_LARGE = 3200;
    private static final int TARGET_HEIGHT_LARGE = 2400;

    // Auto-exposure settings for better photo quality - now dynamic
    private static final int JPEG_QUALITY = 90; // High quality JPEG
    
    // Dynamic JPEG orientation mapping based on device rotation
    private static final SparseIntArray JPEG_ORIENTATION = new SparseIntArray();
    
    static {
        JPEG_ORIENTATION.append(0, 90);
        JPEG_ORIENTATION.append(90, 0);
        JPEG_ORIENTATION.append(180, 270);
        JPEG_ORIENTATION.append(270, 180);
    }
    
    // Camera keep-alive settings
    private static final long CAMERA_KEEP_ALIVE_MS = 3000; // Keep camera open for 3 seconds after photo
    private Timer cameraKeepAliveTimer;
    private boolean isCameraKeptAlive = false;
    private String pendingPhotoPath = null;
    
    // LED control - tied to camera lifecycle
    private static volatile boolean pendingLedEnabled = false;  // LED state for current/pending requests
    private IHardwareManager hardwareManager;

    // Camera characteristics for dynamic auto-exposure and autofocus
    private int[] availableAeModes;
    private Range<Integer> exposureCompensationRange;
    private Rational exposureCompensationStep;
    private Range<Integer>[] availableFpsRanges;
    private Range<Integer> selectedFpsRange;

    // Autofocus capabilities
    private int[] availableAfModes;
    private float minimumFocusDistance;
    private boolean hasAutoFocus;
    
    /**
     * Get the current display rotation in degrees
     * Uses device-specific rotation mapping for K900 variants
     * @return Display rotation (0, 90, 180, or 270 degrees)
     */
    private int getDisplayRotation() {
        // Use device-specific default rotation for K900 variants
        int deviceDefaultRotation = ServiceUtils.determineDefaultRotationForDevice(this);
        String deviceType = ServiceUtils.getDeviceTypeString(this);
        
        Log.d(TAG, "📱 Device type: " + deviceType + ", Default rotation: " + deviceDefaultRotation + "°");
        
        // For K900 devices, use the device-specific rotation
        if (ServiceUtils.isK900Device(this)) {
            Log.d(TAG, "🔄 Using K900-specific rotation: " + deviceDefaultRotation + "°");
            return deviceDefaultRotation;
        }
        
        // For standard Android devices, use system display rotation
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            Display display = windowManager.getDefaultDisplay();
            switch (display.getRotation()) {
                case Surface.ROTATION_0:
                    Log.d(TAG, "🔄 System display rotation: 0°");
                    return 0;
                case Surface.ROTATION_90:
                    Log.d(TAG, "🔄 System display rotation: 90°");
                    return 90;
                case Surface.ROTATION_180:
                    Log.d(TAG, "🔄 System display rotation: 180°");
                    return 180;
                case Surface.ROTATION_270:
                    Log.d(TAG, "🔄 System display rotation: 270°");
                    return 270;
                default:
                    Log.d(TAG, "🔄 System display rotation: default 0°");
                    return 0;
            }
        }
        
        Log.w(TAG, "⚠️ WindowManager unavailable - using device default: " + deviceDefaultRotation + "°");
        return deviceDefaultRotation; // Fallback to device-specific rotation
    }

    /**
     * SIMPLIFIED AUTOEXPOSURE SYSTEM
     *
     * 1. WAITING_AE: Trigger AE precapture, wait up to 0.5 seconds for convergence
     *    - Waits for AE_STATE_CONVERGED/FLASH_REQUIRED/LOCKED
     *    - CONTINUOUS_PICTURE autofocus runs automatically in background
     *
     * 2. SHOOTING: Capture the photo immediately with high quality settings
     *    - Relies on Camera2 API auto-exposure and continuous autofocus
     */

    // Simplified AE system - autofocus runs automatically
    private enum ShotState { IDLE, WAITING_AE, SHOOTING }
    private volatile ShotState shotState = ShotState.IDLE;
    private long aeStartTimeNs;
    private static final long AE_WAIT_NS = 500_000_000L; // 0.5 second max wait for AE

    // Simple AE callback - autofocus handled automatically
    private final SimplifiedAeCallback aeCallback = new SimplifiedAeCallback();

    // User-settable exposure compensation (apply BEFORE capture, not during)
    private int userExposureCompensation = 0;

    // Callback and execution handling
    private final Executor executor = Executors.newSingleThreadExecutor();

    // Intent action definitions (MOVED TO TOP)
    public static final String ACTION_TAKE_PHOTO = "com.augmentos.camera.ACTION_TAKE_PHOTO";
    public static final String EXTRA_PHOTO_FILE_PATH = "com.augmentos.camera.EXTRA_PHOTO_FILE_PATH";
    public static final String ACTION_START_VIDEO_RECORDING = "com.augmentos.camera.ACTION_START_VIDEO_RECORDING";
    public static final String ACTION_STOP_VIDEO_RECORDING = "com.augmentos.camera.ACTION_STOP_VIDEO_RECORDING";
    public static final String EXTRA_VIDEO_FILE_PATH = "com.augmentos.camera.EXTRA_VIDEO_FILE_PATH";
    public static final String EXTRA_VIDEO_ID = "com.augmentos.camera.EXTRA_VIDEO_ID";
    public static final String EXTRA_VIDEO_SETTINGS = "com.augmentos.camera.EXTRA_VIDEO_SETTINGS";

    // Buffer recording actions
    public static final String ACTION_START_BUFFER = "com.augmentos.camera.ACTION_START_BUFFER";
    public static final String ACTION_STOP_BUFFER = "com.augmentos.camera.ACTION_STOP_BUFFER";
    public static final String ACTION_SAVE_BUFFER = "com.augmentos.camera.ACTION_SAVE_BUFFER";
    public static final String EXTRA_BUFFER_SECONDS = "com.augmentos.camera.EXTRA_BUFFER_SECONDS";
    public static final String EXTRA_BUFFER_REQUEST_ID = "com.augmentos.camera.EXTRA_BUFFER_REQUEST_ID";

    // Callback interface for photo capture
    public interface PhotoCaptureCallback {
        void onPhotoCaptured(String filePath);
        void onPhotoError(String errorMessage);
    }

    // Static callback for photo capture
    private static PhotoCaptureCallback sPhotoCallback;
    
    // Photo request queue for rapid capture
    private static class PhotoRequest {
        String requestId;
        String filePath;
        String size;
        PhotoCaptureCallback callback;
        boolean enableLed;  // Whether to use LED flash for this photo
        long timestamp;
        int retryCount;
        
        PhotoRequest(String filePath, String size, boolean enableLed, PhotoCaptureCallback callback) {
            this.requestId = "photo_" + System.currentTimeMillis() + "_" + filePath.hashCode();
            this.filePath = filePath;
            this.size = size;
            this.enableLed = enableLed;
            this.callback = callback;
            this.timestamp = System.currentTimeMillis();
            this.retryCount = 0;
        }
    }
    // Instance-level queue is deprecated - use globalRequestQueue instead
    @Deprecated
    private final Queue<PhotoRequest> photoRequestQueue = new LinkedList<>();

    // For compatibility with CameraRecordingService
    private static String lastPhotoPath;

    // Video recording components
    private MediaRecorder mediaRecorder;
    private Surface recorderSurface;
    private boolean isRecording = false;
    private String currentVideoId;
    private String currentVideoPath;
    private static VideoRecordingCallback sVideoCallback;
    private long recordingStartTime;
    private Timer recordingTimer;
    private Size videoSize; // To store selected video size
    private VideoSettings pendingVideoSettings; // Settings for next recording

    // Buffer recording components
    private enum RecordingMode {
        SINGLE_VIDEO,  // Current behavior - record once and stop
        BUFFER         // Continuous buffer recording
    }
    private RecordingMode currentMode = RecordingMode.SINGLE_VIDEO;
    private CircularVideoBufferInternal bufferManager;
    private Handler segmentSwitchHandler;
    private static final long SEGMENT_DURATION_MS = 5000; // 5 seconds
    private boolean isInBufferMode = false;
    private static BufferCallback sBufferCallback;

    // Static instance for checking camera status
    private static CameraNeo sInstance;

    /**
     * Interface for video recording callbacks
     */
    public interface VideoRecordingCallback {
        void onRecordingStarted(String videoId);

        void onRecordingProgress(String videoId, long durationMs);

        void onRecordingStopped(String videoId, String filePath);

        void onRecordingError(String videoId, String errorMessage);
    }

    /**
     * Interface for buffer recording callbacks
     */
    public interface BufferCallback {
        void onBufferStarted();
        void onBufferStopped();
        void onBufferSaved(String filePath, int durationSeconds);
        void onBufferError(String error);
    }

    /**
     * Get the path to the most recently captured photo
     * Added for compatibility with CameraRecordingService
     */
    public static String getLastPhotoPath() {
        return lastPhotoPath;
    }

    /**
     * Check if the camera is currently in use for photo capture or video recording.
     * This relies on the service instance being available.
     * 
     * IMPORTANT: This returns false when camera is only kept alive for rapid photos,
     * allowing the kept-alive camera to be closed if needed for other operations.
     *
     * @return true if the camera is actively busy, false if idle or just kept alive.
     */
    public static boolean isCameraInUse() {
        if (sInstance != null) {
            // If camera is kept alive but idle (waiting for next photo), don't block other operations
            if (sInstance.isCameraKeptAlive && sInstance.shotState == ShotState.IDLE) {
                // Camera is kept alive but not actively taking a photo
                // This allows other operations to close the camera if needed
                return false;
            }
            
            // Check if a photo capture session is active (actively taking a photo)
            boolean photoSessionActive = (sInstance.cameraDevice != null && sInstance.imageReader != null && 
                                         !sInstance.isRecording && sInstance.shotState != ShotState.IDLE);
            
            // Return true if actively recording video, buffering, or taking a photo
            return photoSessionActive || sInstance.isRecording || sInstance.isInBufferMode;
        }
        return false; // Service not running or instance not set
    }

    /**
     * Check if buffer recording mode is active
     */
    public static boolean isInBufferMode() {
        if (sInstance != null) {
            return sInstance.isInBufferMode;
        }
        return false;
    }
    
    /**
     * Force close the camera if it's only kept alive (not actively in use).
     * This is called when other operations like video/streaming need the camera.
     * @return true if camera was closed, false if camera was busy or not open
     */
    public static boolean closeKeptAliveCamera() {
        if (sInstance != null && sInstance.isCameraKeptAlive && sInstance.shotState == ShotState.IDLE) {
            Log.d(TAG, "Force closing kept-alive camera for other operation");
            sInstance.cancelKeepAliveTimer();
            sInstance.isCameraKeptAlive = false;
            sInstance.closeCamera();
            sInstance.stopSelf();
            return true;
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (SERVICE_LOCK) {
            Log.d(TAG, "CameraNeo Camera2 service created - Setting state to RUNNING");
            isServiceStarting = false;
            isServiceRunning = true;
            serviceState = ServiceState.RUNNING;
            sInstance = this;
        }
        // Initialize hardware manager for LED control
        hardwareManager = HardwareManagerFactory.getInstance(this);
        createNotificationChannel();
        showNotification("Camera Service", "Service is running");
        startBackgroundThread();
    }

    /**
     * Primary entry point for photo requests - uses global queue to prevent race conditions
     * This method immediately queues the request and ensures only one service instance exists
     * 
     * @param context Application context
     * @param filePath File path to save the photo
     * @param size Photo size (small/medium/large)
     * @param enableLed Whether to enable LED flash for this photo
     * @param callback Callback to be notified when photo is captured
     */
    public static void enqueuePhotoRequest(Context context, String filePath, String size, boolean enableLed, PhotoCaptureCallback callback) {
        synchronized (SERVICE_LOCK) {
            // Create and queue the request immediately
            PhotoRequest request = new PhotoRequest(filePath, size, enableLed, callback);
            globalRequestQueue.offer(request);
            
            // Store callback in registry for later retrieval
            if (callback != null) {
                callbackRegistry.put(request.requestId, callback);
            }
            
            Log.d(TAG, "📸 Enqueued photo request: " + request.requestId + 
                      " | Queue size: " + globalRequestQueue.size() + 
                      " | Service state: " + serviceState);
            
            // Check current service state and act accordingly
            if (isServiceRunning && isCameraReady && sInstance != null) {
                // Fast path - camera is ready, check if idle
                if (sInstance.shotState == ShotState.IDLE) {
                    Log.d(TAG, "Camera ready and idle - processing request immediately");
                    // Don't call processNextPhotoRequest as it might try to reopen camera
                    // Instead, directly process the request we just queued
                    PhotoRequest queuedRequest = globalRequestQueue.poll();
                    if (queuedRequest != null) {
                        sInstance.sPhotoCallback = queuedRequest.callback;
                        sInstance.pendingPhotoPath = queuedRequest.filePath;
                        sInstance.pendingRequestedSize = queuedRequest.size;
                        sInstance.shotState = ShotState.WAITING_AE;
                        
                        if (sInstance.backgroundHandler != null) {
                            sInstance.backgroundHandler.post(sInstance::startPrecaptureSequence);
                        } else {
                            sInstance.startPrecaptureSequence();
                        }
                    }
                } else {
                    Log.d(TAG, "Camera ready but busy (state: " + sInstance.shotState + ") - request queued");
                }
            } else if (isServiceStarting) {
                // Service is already starting, request will be processed when ready
                Log.d(TAG, "Service is starting - request will be processed when camera ready");
            } else {
                // Need to start the service
                Log.d(TAG, "Starting service to process photo request");
                isServiceStarting = true;
                serviceState = ServiceState.STARTING;
                
                Intent intent = new Intent(context, CameraNeo.class);
                intent.setAction(ACTION_TAKE_PHOTO);
                intent.putExtra("USE_GLOBAL_QUEUE", true);
                context.startForegroundService(intent);
            }
        }
    }

    /**
     * Legacy method - redirects to enqueuePhotoRequest for backward compatibility
     * 
     * @deprecated Use enqueuePhotoRequest instead
     */
    @Deprecated
    public static void takePictureWithCallback(Context context, String filePath, PhotoCaptureCallback callback) {
        enqueuePhotoRequest(context, filePath, null, false, callback);
    }

    /**
     * Legacy method with size parameter - redirects to enqueuePhotoRequest
     * 
     * @deprecated Use enqueuePhotoRequest instead
     */
    @Deprecated
    public static void takePictureWithCallback(Context context, String filePath, PhotoCaptureCallback callback, String size) {
        enqueuePhotoRequest(context, filePath, size, false, callback);
    }

    /**
     * Start video recording and get notified through callback
     *
     * @param context  Application context
     * @param videoId  Unique ID for this video recording session
     * @param filePath File path to save the video
     * @param callback Callback for recording events
     */
    public static void startVideoRecording(Context context, String videoId, String filePath, VideoRecordingCallback callback) {
        startVideoRecording(context, videoId, filePath, null, callback);
    }
    
    /**
     * Start video recording with custom settings
     *
     * @param context  Application context
     * @param videoId  Unique ID for this video recording session
     * @param filePath File path to save the video
     * @param settings Video settings (resolution, fps) or null for defaults
     * @param callback Callback for recording events
     */
    public static void startVideoRecording(Context context, String videoId, String filePath, VideoSettings settings, VideoRecordingCallback callback) {
        sVideoCallback = callback;

        Intent intent = new Intent(context, CameraNeo.class);
        intent.setAction(ACTION_START_VIDEO_RECORDING);
        intent.putExtra(EXTRA_VIDEO_ID, videoId);
        intent.putExtra(EXTRA_VIDEO_FILE_PATH, filePath);
        if (settings != null) {
            intent.putExtra(EXTRA_VIDEO_SETTINGS + "_width", settings.width);
            intent.putExtra(EXTRA_VIDEO_SETTINGS + "_height", settings.height);
            intent.putExtra(EXTRA_VIDEO_SETTINGS + "_fps", settings.fps);
        }
        context.startForegroundService(intent);
    }

    /**
     * Stop the current video recording session
     *
     * @param context Application context
     * @param videoId ID of the video recording session to stop (must match active session)
     */
    public static void stopVideoRecording(Context context, String videoId) {
        Intent intent = new Intent(context, CameraNeo.class);
        intent.setAction(ACTION_STOP_VIDEO_RECORDING);
        intent.putExtra(EXTRA_VIDEO_ID, videoId);
        context.startForegroundService(intent);
    }

    /**
     * Start buffer recording
     * @param context Application context
     * @param callback Callback for buffer events
     */
    public static void startBufferRecording(Context context, BufferCallback callback) {
        sBufferCallback = callback;
        Intent intent = new Intent(context, CameraNeo.class);
        intent.setAction(ACTION_START_BUFFER);
        context.startForegroundService(intent);
    }

    /**
     * Stop buffer recording
     * @param context Application context
     */
    public static void stopBufferRecording(Context context) {
        Intent intent = new Intent(context, CameraNeo.class);
        intent.setAction(ACTION_STOP_BUFFER);
        context.startForegroundService(intent);
    }

    /**
     * Save buffer video
     * @param context Application context
     * @param seconds Number of seconds to save
     * @param requestId Request ID for tracking
     */
    public static void saveBufferVideo(Context context, int seconds, String requestId) {
        Intent intent = new Intent(context, CameraNeo.class);
        intent.setAction(ACTION_SAVE_BUFFER);
        intent.putExtra(EXTRA_BUFFER_SECONDS, seconds);
        intent.putExtra(EXTRA_BUFFER_REQUEST_ID, requestId);
        context.startForegroundService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "CameraNeo received action: " + action);

            switch (action) {
                case ACTION_TAKE_PHOTO:
                    // Check if we should use the global queue
                    boolean useGlobalQueue = intent.getBooleanExtra("USE_GLOBAL_QUEUE", false);
                    
                    if (useGlobalQueue) {
                        // Process from global queue
                        Log.d(TAG, "Processing photo requests from global queue");
                        processQueuedPhotoRequests();
                    } else {
                        // Legacy path - still supported but deprecated
                        String photoFilePath = intent.getStringExtra(EXTRA_PHOTO_FILE_PATH);
                        String requestedSize = intent.getStringExtra("PHOTO_SIZE");
                        Log.d(TAG, "Legacy photo request - path: " + photoFilePath);
                        if (photoFilePath == null || photoFilePath.isEmpty()) {
                            Log.d(TAG, "Photo file path is empty, using default");
                            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                            photoFilePath = getExternalFilesDir(null) + File.separator + "IMG_" + timeStamp + ".jpg";
                        }
                        setupCameraAndTakePicture(photoFilePath, requestedSize);
                    }
                    break;
                case ACTION_START_VIDEO_RECORDING:
                    currentVideoId = intent.getStringExtra(EXTRA_VIDEO_ID);
                    currentVideoPath = intent.getStringExtra(EXTRA_VIDEO_FILE_PATH);
                    if (currentVideoPath == null || currentVideoPath.isEmpty()) {
                        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                        currentVideoPath = getExternalFilesDir(null) + File.separator + "VID_" + timeStamp + ".mp4";
                    }
                    // Extract video settings if provided
                    int width = intent.getIntExtra(EXTRA_VIDEO_SETTINGS + "_width", 0);
                    int height = intent.getIntExtra(EXTRA_VIDEO_SETTINGS + "_height", 0);
                    int fps = intent.getIntExtra(EXTRA_VIDEO_SETTINGS + "_fps", 0);
                    if (width > 0 && height > 0 && fps > 0) {
                        pendingVideoSettings = new VideoSettings(width, height, fps);
                        Log.d(TAG, "Using custom video settings: " + pendingVideoSettings);
                    } else {
                        pendingVideoSettings = null; // Will use defaults
                    }
                    setupCameraAndStartRecording(currentVideoId, currentVideoPath);
                    break;
                case ACTION_STOP_VIDEO_RECORDING:
                    String videoIdToStop = intent.getStringExtra(EXTRA_VIDEO_ID);
                    stopCurrentVideoRecording(videoIdToStop);
                    break;

                case ACTION_START_BUFFER:
                    startBufferMode();
                    break;

                case ACTION_STOP_BUFFER:
                    stopBufferMode();
                    break;

                case ACTION_SAVE_BUFFER:
                    int seconds = intent.getIntExtra(EXTRA_BUFFER_SECONDS, 30);
                    String requestId = intent.getStringExtra(EXTRA_BUFFER_REQUEST_ID);
                    saveBufferVideo(seconds, requestId);
                    break;
            }
        }
        return START_STICKY;
    }

    private String pendingRequestedSize;

    /**
     * Process all queued photo requests from the global queue
     * This is called when the service has USE_GLOBAL_QUEUE flag as true
     * This also processes any queued photo requests after completing current photo.
     */
    private void processQueuedPhotoRequests() {
        // First check the global queue (primary)
        synchronized (SERVICE_LOCK) {
            PhotoRequest firstRequest = globalRequestQueue.peek();
            if (firstRequest == null) {
                Log.d(TAG, "No photo requests in global queue");
                return;
            }
            Log.d(TAG, "Processing " + globalRequestQueue.size() + " queued photo requests");
            if (!isCameraKeptAlive || cameraDevice == null) {
                // Open camera from scratch
                Log.d(TAG, "Opening camera for photo capture");
                wakeUpScreen();
                openCameraInternal(firstRequest.filePath, false);
                return;
            }
            if (shotState == ShotState.IDLE) {
                // Camera open taking photo.
                PhotoRequest nextRequest = globalRequestQueue.poll();
                if (nextRequest != null) {
                    Log.d(TAG, "Processing queued photo from GLOBAL queue: " + nextRequest.filePath);

                    // Retrieve callback from registry if needed
                    if (nextRequest.callback == null && callbackRegistry.containsKey(nextRequest.requestId)) {
                        nextRequest.callback = callbackRegistry.remove(nextRequest.requestId);
                    }

                    // Update the callback for this request
                    sPhotoCallback = nextRequest.callback;

                    boolean sizeChanged = false;
                    if (pendingRequestedSize != null && nextRequest.size != null) {
                        sizeChanged = !pendingRequestedSize.equals(nextRequest.size);
                    }
                    if (sizeChanged) {
                        Log.d(TAG, "Photo size changed, reopening camera");
                        cancelKeepAliveTimer();
                        closeCamera();
                        openCameraInternal(nextRequest.filePath, false);
                    } else {
                        // Cancel any pending keep-alive timer
                        cancelKeepAliveTimer();

                        // Process the queued request
                        pendingPhotoPath = nextRequest.filePath;
                        pendingRequestedSize = nextRequest.size;

                        // Update LED state if this request needs LED
                        if (nextRequest.enableLed) {
                            pendingLedEnabled = true;
                        }

                        // IMPORTANT: Only start capture if camera is ready
                        // Don't try to open camera again if it's already open
                        if (cameraDevice != null && cameraCaptureSession != null) {
                            // Start new capture sequence
                            shotState = ShotState.WAITING_AE;
                            if (backgroundHandler != null) {
                                backgroundHandler.post(() -> startPrecaptureSequence());
                            } else {
                                startPrecaptureSequence();
                            }
                        } else {
                            // Camera not ready yet, re-queue the request
                            Log.d(TAG, "Camera not ready yet, re-queuing request");
                            globalRequestQueue.offer(nextRequest);
                        }
                    }
                    return;
                }
            }
        }

        // Fallback to instance queue for legacy compatibility
        if (!photoRequestQueue.isEmpty() && shotState == ShotState.IDLE) {
            PhotoRequest nextRequest = photoRequestQueue.poll();
            if (nextRequest != null) {
                Log.d(TAG, "Processing queued photo from INSTANCE queue: " + nextRequest.filePath);

                // Update the callback for this request
                sPhotoCallback = nextRequest.callback;

                // Cancel any pending keep-alive timer
                cancelKeepAliveTimer();

                // Process the queued request
                pendingPhotoPath = nextRequest.filePath;
                pendingRequestedSize = nextRequest.size;

                // Start new capture sequence
                shotState = ShotState.WAITING_AE;
                if (backgroundHandler != null) {
                    backgroundHandler.post(() -> startPrecaptureSequence());
                } else {
                    startPrecaptureSequence();
                }
            }
        } else if (photoRequestQueue.isEmpty() && globalRequestQueue.isEmpty()) {
            // No more requests in either queue, start keep-alive timer
            startKeepAliveTimer();
        }
    }


    @Deprecated
    private void setupCameraAndTakePicture(String filePath, String requestedSize) {
        // Check if size has changed from the current configuration
        boolean sizeChanged = false;
        if (isCameraKeptAlive && pendingRequestedSize != null && requestedSize != null) {
            sizeChanged = !pendingRequestedSize.equals(requestedSize);
        }
        
        // Store the requested size for use in openCameraInternal
        pendingRequestedSize = requestedSize;
        
        // Check if camera is already open and kept alive AND size hasn't changed
        if (isCameraKeptAlive && cameraDevice != null && !sizeChanged) {
            Log.d(TAG, "Camera is already open (kept alive), processing photo request");
            
            // Check if camera is currently busy taking a photo
            if (shotState != ShotState.IDLE) {
                Log.d(TAG, "Camera is busy (state: " + shotState + "), queuing photo request");
                // Queue this request to be processed after current photo completes
                photoRequestQueue.offer(new PhotoRequest(filePath, pendingRequestedSize, false, sPhotoCallback));
                return;
            }
            
            // Cancel the keep-alive timer since we're taking a new photo
            cancelKeepAliveTimer();
            
            // Update the pending photo path for the new capture
            pendingPhotoPath = filePath;
            
            // Camera is already open with AE likely converged, trigger new capture
            // Start from WAITING_AE to ensure proper capture sequence
            shotState = ShotState.WAITING_AE;
            
            // Use background handler to ensure proper thread
            if (backgroundHandler != null) {
                backgroundHandler.post(() -> {
                    startPrecaptureSequence();
                });
            } else {
                startPrecaptureSequence();
            }
        } else {
            // Normal flow - open camera from scratch (either not kept alive or size changed)
            if (sizeChanged) {
                Log.d(TAG, "Photo size changed from " + pendingRequestedSize + " to " + requestedSize + ", reopening camera");
                cancelKeepAliveTimer();
                closeCamera();
            }
            wakeUpScreen();
            openCameraInternal(filePath, false); // false indicates not for video
        }
    }

    private void setupCameraAndStartRecording(String videoId, String filePath) {
        if (isRecording) {
            notifyVideoError(videoId, "Already recording another video.");
            return;
        }
        wakeUpScreen();
        currentVideoId = videoId;
        currentVideoPath = filePath;
        openCameraInternal(filePath, true); // true indicates for video
    }

    private void stopCurrentVideoRecording(String videoIdToStop) {
        if (!isRecording) {
            Log.w(TAG, "Stop recording requested, but not currently recording.");
            // Optionally notify error or just ignore if it's a common race condition
            if (sVideoCallback != null && videoIdToStop != null) {
                sVideoCallback.onRecordingError(videoIdToStop, "Not recording");
            }
            return;
        }
        if (videoIdToStop == null || !videoIdToStop.equals(currentVideoId)) {
            Log.w(TAG, "Stop recording requested for ID " + videoIdToStop + " but current is " + currentVideoId);
            if (sVideoCallback != null && videoIdToStop != null) {
                sVideoCallback.onRecordingError(videoIdToStop, "Video ID mismatch");
            }
            return;
        }

        try {
            if (mediaRecorder != null) {
                // Check minimum recording duration to prevent corruption
                long recordingDuration = System.currentTimeMillis() - recordingStartTime;
                if (recordingDuration < 500) {
                    Log.w(TAG, "Recording duration too short (" + recordingDuration + "ms), file may be corrupted");
                    // Still try to stop, but warn about potential corruption
                    if (sVideoCallback != null) {
                        Log.w(TAG, "Warning: Video recording was very short, file may be corrupted");
                    }
                }
                
                mediaRecorder.stop();
                mediaRecorder.reset();
            }
            Log.d(TAG, "Video recording stopped for: " + currentVideoId);
            if (sVideoCallback != null) {
                sVideoCallback.onRecordingStopped(currentVideoId, currentVideoPath);
            }
        } catch (RuntimeException stopErr) {
            Log.e(TAG, "MediaRecorder.stop() failed", stopErr);
            if (sVideoCallback != null) {
                sVideoCallback.onRecordingError(currentVideoId, "Failed to stop recorder: " + stopErr.getMessage());
            }
            // Still try to clean up even if stop failed
        } finally {
            isRecording = false;
            if (recordingTimer != null) {
                recordingTimer.cancel();
                recordingTimer = null;
            }
            closeCamera();
            conditionalStopSelf(); // Changed to conditional stop
        }
    }

    /**
     * Start buffer recording mode
     */
    private void startBufferRecording() {
        if (isInBufferMode) {
            Log.w(TAG, "Already in buffer mode");
            return;
        }

        // Check if camera is already in use
        if (isCameraInUse()) {
            Log.e(TAG, "Cannot start buffer - camera already in use");
            if (sBufferCallback != null) {
                sBufferCallback.onBufferError("Camera is busy");
            }
            return;
        }

        Log.d(TAG, "Starting buffer recording mode");

        // Initialize buffer manager
        bufferManager = new CircularVideoBufferInternal(this);
        bufferManager.setCallback(new CircularVideoBufferInternal.SegmentSwitchCallback() {
            @Override
            public void onSegmentSwitch(int newSegmentIndex, Surface newSurface) {
                Log.d(TAG, "Buffer requesting segment switch to index " + newSegmentIndex);
                // Handle segment switch - recreate camera session with new surface
                switchToNewSegment(newSurface);
            }

            @Override
            public void onBufferError(String error) {
                Log.e(TAG, "Buffer error: " + error);
                if (sBufferCallback != null) {
                    sBufferCallback.onBufferError(error);
                }
            }

            @Override
            public void onSegmentReady(int segmentIndex, String filePath) {
                Log.d(TAG, "Buffer segment " + segmentIndex + " ready: " + filePath);
            }
        });

        try {
            // Prepare all MediaRecorder instances
            bufferManager.prepareAllRecorders();

            // Set mode and open camera
            currentMode = RecordingMode.BUFFER;
            isInBufferMode = true;

            // Open camera for buffer recording
            wakeUpScreen();
            openCameraInternal(null, true); // true for video mode

            // Notify callback
            if (sBufferCallback != null) {
                sBufferCallback.onBufferStarted();
            }

            // Start segment switch timer
            startSegmentSwitchTimer();

        } catch (IOException e) {
            Log.e(TAG, "Failed to start buffer recording", e);
            if (sBufferCallback != null) {
                sBufferCallback.onBufferError("Failed to start: " + e.getMessage());
            }
            isInBufferMode = false;
            currentMode = RecordingMode.SINGLE_VIDEO;
        }
    }

    /**
     * Stop buffer recording mode
     */
    private void stopBufferRecording() {
        if (!isInBufferMode) {
            Log.w(TAG, "Not in buffer mode");
            return;
        }

        Log.d(TAG, "Stopping buffer recording");

        // Clear buffer mode flag
        isInBufferMode = false;
        currentMode = RecordingMode.SINGLE_VIDEO;

        // Stop segment timer
        stopSegmentSwitchTimer();

        // Stop buffer manager
        if (bufferManager != null) {
            bufferManager.stopBuffering();
            bufferManager = null;
        }

        // Close camera
        closeCamera();

        // Notify callback
        if (sBufferCallback != null) {
            sBufferCallback.onBufferStopped();
        }

        // Now we can stop the service
        stopSelf();
    }

    /**
     * Save buffer video
     */
    private void saveBufferVideo(int seconds, String requestId) {
        if (!isInBufferMode || bufferManager == null) {
            Log.e(TAG, "Cannot save buffer - not in buffer mode");
            if (sBufferCallback != null) {
                sBufferCallback.onBufferError("Not buffering");
            }
            return;
        }

        // Generate output path
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String outputPath = getExternalFilesDir(null) + File.separator +
                          "BUFFER_" + timeStamp + "_" + requestId + ".mp4";

        try {
            Log.d(TAG, "Saving last " + seconds + " seconds to " + outputPath);
            bufferManager.saveLastNSeconds(seconds, outputPath);

            // Notify callback
            if (sBufferCallback != null) {
                sBufferCallback.onBufferSaved(outputPath, seconds);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save buffer", e);
            if (sBufferCallback != null) {
                sBufferCallback.onBufferError("Save failed: " + e.getMessage());
            }
        }
    }

    /**
     * Start timer for segment switching
     */
    private void startSegmentSwitchTimer() {
        segmentSwitchHandler = new Handler();
        segmentSwitchHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isInBufferMode && bufferManager != null) {
                    Log.d(TAG, "Segment timer triggered - switching to next segment");
                    bufferManager.switchToNextSegment();
                    // Schedule next switch
                    segmentSwitchHandler.postDelayed(this, SEGMENT_DURATION_MS);
                }
            }
        }, SEGMENT_DURATION_MS);
    }

    /**
     * Stop segment switch timer
     */
    private void stopSegmentSwitchTimer() {
        if (segmentSwitchHandler != null) {
            segmentSwitchHandler.removeCallbacksAndMessages(null);
            segmentSwitchHandler = null;
        }
    }

    /**
     * Switch camera session to new segment surface
     */
    private void switchToNewSegment(Surface newSurface) {
        if (cameraCaptureSession != null) {
            try {
                // Stop current session
                cameraCaptureSession.stopRepeating();
                cameraCaptureSession.close();
                cameraCaptureSession = null;

                // Recreate session with new surface
                recorderSurface = newSurface; // Update the surface
                createCameraSessionInternal(true); // Recreate for video

            } catch (CameraAccessException e) {
                Log.e(TAG, "Error switching to new segment", e);
                if (sBufferCallback != null) {
                    sBufferCallback.onBufferError("Segment switch failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Conditional stop self - only stops if not in buffer mode
     */
    private void conditionalStopSelf() {
        if (currentMode != RecordingMode.BUFFER) {
            stopSelf();
        }
    }

    @SuppressLint("MissingPermission")
    private void openCameraInternal(String filePath, boolean forVideo) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG, "Could not get camera manager");
            if (forVideo) notifyVideoError(currentVideoId, "Camera service unavailable");
            else notifyPhotoError("Camera service unavailable");
            conditionalStopSelf();
            return;
        }

        try {
            // First check if camera permission is granted
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                int cameraPermission = checkSelfPermission(android.Manifest.permission.CAMERA);
                if (cameraPermission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Camera permission not granted");
                    if (forVideo) notifyVideoError(currentVideoId, "Camera permission not granted");
                    else notifyPhotoError("Camera permission not granted");
                    conditionalStopSelf();
                    return;
                }
            }

            String[] cameraIds = manager.getCameraIdList();

            // Find the back camera (primary camera)
            for (String id : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    this.cameraId = id;
                    break;
                }
            }

            // If no back camera found, use the first available camera
            if (this.cameraId == null && cameraIds.length > 0) {
                this.cameraId = cameraIds[0];
                Log.d(TAG, "No back camera found, using camera ID: " + this.cameraId);
            }

            // Verify that we have a valid camera ID
            if (this.cameraId == null) {
                if (forVideo) notifyVideoError(currentVideoId, "No suitable camera found");
                else notifyPhotoError("No suitable camera found");
                conditionalStopSelf();
                return;
            }

            // Get characteristics for the selected camera
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(this.cameraId);

            // Query camera capabilities for dynamic auto-exposure
            queryCameraCapabilities(characteristics);

            // Check if this camera supports JPEG format
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                if (forVideo)
                    notifyVideoError(currentVideoId, "Camera " + this.cameraId + " doesn't support configuration maps");
                else
                    notifyPhotoError("Camera " + this.cameraId + " doesn't support configuration maps");
                stopSelf();
                return;
            }

            // Find the closest available JPEG size to our target
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (jpegSizes == null || jpegSizes.length == 0) {
                if (forVideo)
                    notifyVideoError(currentVideoId, "Camera doesn't support JPEG format");
                else notifyPhotoError("Camera doesn't support JPEG format");
                stopSelf();
                return;
            }

            int desiredW = TARGET_WIDTH;
            int desiredH = TARGET_HEIGHT;
            if (pendingRequestedSize != null) {
                switch (pendingRequestedSize) {
                    case "small":
                        desiredW = TARGET_WIDTH_SMALL;
                        desiredH = TARGET_HEIGHT_SMALL;
                        break;
                    case "large":
                        desiredW = TARGET_WIDTH_LARGE;
                        desiredH = TARGET_HEIGHT_LARGE;
                        break;
                    case "medium":
                    default:
                        desiredW = TARGET_WIDTH;
                        desiredH = TARGET_HEIGHT;
                        break;
                }
            }
            jpegSize = chooseOptimalSize(jpegSizes, desiredW, desiredH);
            Log.d(TAG, "Selected JPEG size: " + jpegSize.getWidth() + "x" + jpegSize.getHeight());

            // If this is for video, set up video size too
            if (forVideo) {
                // Find a suitable video size
                Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);

                if (videoSizes == null || videoSizes.length == 0) {
                    notifyVideoError(currentVideoId, "Camera doesn't support MediaRecorder");
                    conditionalStopSelf();
                    return;
                }

                // Log available video sizes
                Log.d(TAG, "Available video sizes for camera " + this.cameraId + ":");
                for (Size size : videoSizes) {
                    Log.d(TAG, "  " + size.getWidth() + "x" + size.getHeight());
                }

                // Use pending video settings if available, otherwise default to 720p
                int targetVideoWidth;
                int targetVideoHeight;
                if (pendingVideoSettings != null && pendingVideoSettings.isValid()) {
                    targetVideoWidth = pendingVideoSettings.width;
                    targetVideoHeight = pendingVideoSettings.height;
                    Log.d(TAG, "Using requested video settings: " + pendingVideoSettings);
                } else {
                    targetVideoWidth = 1280;
                    targetVideoHeight = 720;
                    Log.d(TAG, "Using default video settings: 1280x720@30fps");
                }
                videoSize = chooseOptimalSize(videoSizes, targetVideoWidth, targetVideoHeight);
                Log.d(TAG, "Selected video size: " + videoSize.getWidth() + "x" + videoSize.getHeight());

                // Initialize MediaRecorder only for single video mode
                // In buffer mode, CircularVideoBufferInternal handles its own MediaRecorders
                if (currentMode != RecordingMode.BUFFER) {
                    setupMediaRecorder(currentVideoPath);
                }
            }

            // Setup ImageReader for JPEG data
            imageReader = ImageReader.newInstance(
                    jpegSize.getWidth(), jpegSize.getHeight(),
                    ImageFormat.JPEG, 2);

            imageReader.setOnImageAvailableListener(reader -> {
                // Only process images when we're actually shooting, not during precapture metering
                if (shotState != ShotState.SHOOTING) {
                    // Suppress logging to prevent logcat overflow
                    // Only log errors or important state changes
                    // Consume the image to prevent backing up the queue
                    try (Image image = reader.acquireLatestImage()) {
                        // Just consume and discard
                    }
                    return;
                }

                // Process the captured JPEG (only when in SHOOTING state)
                Log.d(TAG, "Processing final photo capture...");
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) {
                        Log.e(TAG, "Acquired image is null");
                        notifyPhotoError("Failed to acquire image data");
                        shotState = ShotState.IDLE;
                        closeCamera();
                        stopSelf();
                        return;
                    }

                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    // Use pending photo path if available (from queued requests), otherwise use the original path
                    String targetPath = (pendingPhotoPath != null) ? pendingPhotoPath : filePath;
                    
                    // Save the image data to the file
                    boolean success = saveImageDataToFile(bytes, targetPath);

                    if (success) {
                        lastPhotoPath = targetPath;
                        notifyPhotoCaptured(targetPath);
                        Log.d(TAG, "Photo saved successfully: " + targetPath);
                        // Clear pending photo path and size after successful capture
                        pendingPhotoPath = null;
                        pendingRequestedSize = null;
                    } else {
                        notifyPhotoError("Failed to save image");
                    }

                    // Reset state
                    shotState = ShotState.IDLE;

                    // Check if there are queued photo requests
                    processQueuedPhotoRequests();
                } catch (Exception e) {
                    Log.e(TAG, "Error handling image data", e);
                    notifyPhotoError("Error processing photo: " + e.getMessage());
                    shotState = ShotState.IDLE;
                    
                    // Check if there are queued photo requests even after error
                    if (!photoRequestQueue.isEmpty()) {
                        processQueuedPhotoRequests();
                    } else {
                        // On error with no queued requests, close immediately without keep-alive
                        cancelKeepAliveTimer();
                        pendingPhotoPath = null;
                        pendingRequestedSize = null;
                        closeCamera();
                        stopSelf();
                    }
                }
            }, backgroundHandler);

            // Open the camera
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            Log.d(TAG, "Opening camera ID: " + this.cameraId);
            manager.openCamera(this.cameraId, forVideo ? videoStateCallback : photoStateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            // Handle camera access exceptions more specifically
            Log.e(TAG, "Camera access exception: " + e.getReason(), e);
            String errorMsg = "Could not access camera";

            // Check for specific error reasons
            if (e.getReason() == CameraAccessException.CAMERA_DISABLED) {
                errorMsg = "Camera disabled by policy - please check camera permissions in Settings";
                // Try to recover by restarting the camera service
                Log.d(TAG, "Attempting to restart camera service in safe mode");
                restartCameraServiceIfNeeded();
            } else if (e.getReason() == CameraAccessException.CAMERA_ERROR) {
                errorMsg = "Camera device encountered an error";
            } else if (e.getReason() == CameraAccessException.CAMERA_IN_USE) {
                errorMsg = "Camera is already in use by another app";
                // Try to close other camera sessions
                releaseCameraResources();
            }

            if (forVideo) notifyVideoError(currentVideoId, errorMsg);
            else notifyPhotoError(errorMsg);
            stopSelf();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while trying to lock camera", e);
            notifyPhotoError("Camera operation interrupted");
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Error setting up camera", e);
            notifyPhotoError("Error setting up camera: " + e.getMessage());
            stopSelf();
        }
    }

    /**
     * Setup MediaRecorder for video recording
     */
    private void setupMediaRecorder(String filePath) {
        try {
            // Check storage space before setting up recorder
            StorageManager storageManager = new StorageManager(this);
            if (!storageManager.canRecordVideo()) {
                throw new IOException("Insufficient storage space for video recording");
            }
            
            if (mediaRecorder == null) {
                mediaRecorder = new MediaRecorder();
            } else {
                mediaRecorder.reset();
            }

            // Set up media recorder sources and formats
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

            // Set output format
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // Set output file
            mediaRecorder.setOutputFile(filePath);

            // Set video encoding parameters
            // Use higher bitrate for better reliability and to prevent encoder issues
            int bitRate = (videoSize.getWidth() >= 1920) ? 8000000 : 5000000; // 8Mbps for 1080p, 5Mbps for 720p
            mediaRecorder.setVideoEncodingBitRate(bitRate);
            
            // Use fps from settings if available
            int frameRate = (pendingVideoSettings != null) ? pendingVideoSettings.fps : 30;
            mediaRecorder.setVideoFrameRate(frameRate);
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            
            Log.d(TAG, "MediaRecorder configured: " + videoSize.getWidth() + "x" + videoSize.getHeight() + 
                      "@" + frameRate + "fps, bitrate: " + bitRate);

            // Set audio encoding parameters
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

            // Set dynamic orientation based on device rotation
            int displayOrientation = getDisplayRotation();
            int videoOrientation = JPEG_ORIENTATION.get(displayOrientation, 0);
            mediaRecorder.setOrientationHint(videoOrientation);
            
            // Set maximum file size and duration based on available storage
            long maxFileSize = storageManager.getMaxVideoFileSize();
            int maxDuration = storageManager.getMaxVideoDuration(bitRate);
            
            try {
                mediaRecorder.setMaxFileSize(maxFileSize);
                Log.d(TAG, "Set max file size: " + (maxFileSize / (1024 * 1024)) + " MB");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Failed to set max file size: " + e.getMessage());
            }
            
            try {
                mediaRecorder.setMaxDuration(maxDuration);
                Log.d(TAG, "Set max duration: " + (maxDuration / 1000) + " seconds");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Failed to set max duration: " + e.getMessage());
            }
            
            // Set error listener to handle recording failures
            mediaRecorder.setOnErrorListener((mr, what, extra) -> {
                Log.e(TAG, "MediaRecorder error: what=" + what + ", extra=" + extra);
                isRecording = false;
                String errorMsg = "Recording error: " + what;
                if (what == MediaRecorder.MEDIA_ERROR_SERVER_DIED) {
                    errorMsg = "Media server died during recording";
                } else if (what == MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN) {
                    errorMsg = "Unknown recording error occurred";
                }
                notifyVideoError(currentVideoId, errorMsg);
                // Try to clean up
                try {
                    if (mediaRecorder != null) {
                        mediaRecorder.reset();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error resetting MediaRecorder after error", e);
                }
            });
            
            // Set info listener for recording events
            mediaRecorder.setOnInfoListener((mr, what, extra) -> {
                Log.d(TAG, "MediaRecorder info: what=" + what + ", extra=" + extra);
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.w(TAG, "Max duration reached, stopping recording");
                    stopCurrentVideoRecording(currentVideoId);
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    Log.w(TAG, "Max file size reached, stopping recording");
                    stopCurrentVideoRecording(currentVideoId);
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING) {
                    Log.w(TAG, "Approaching max file size limit");
                }
            });

            // Prepare the recorder
            mediaRecorder.prepare();

            // Get the surface from the recorder
            recorderSurface = mediaRecorder.getSurface();

            Log.d(TAG, "MediaRecorder setup complete for: " + filePath);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up MediaRecorder", e);
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            notifyVideoError(currentVideoId, "Failed to set up video recorder: " + e.getMessage());
        }
    }

    /**
     * Save image data to file
     */
    private boolean saveImageDataToFile(byte[] data, String filePath) {
        try {
            File file = new File(filePath);

            // Ensure parent directory exists
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Write image data to file
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(data);
            }

            Log.d(TAG, "Saved image to: " + filePath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving image", e);
            return false;
        }
    }

    /**
     * Camera state callback for Camera2 API
     */
    private final CameraDevice.StateCallback photoStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device opened successfully");
            cameraOpenCloseLock.release();
            cameraDevice = camera;
            
            // Turn on LED if enabled for photo flash
            if (pendingLedEnabled && hardwareManager != null && hardwareManager.supportsRecordingLed()) {
                Log.d(TAG, "📸 Turning on camera LED (camera opened)");
                hardwareManager.setRecordingLedOn();
            }
            
            // Mark camera as ready
            synchronized (SERVICE_LOCK) {
                isCameraReady = true;
                Log.d(TAG, "Camera marked as ready - processing any queued requests");
            }
            
            createCameraSessionInternal(false); // false for photo
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device disconnected");
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            notifyPhotoError("Camera disconnected");
            stopSelf();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera device error: " + error);
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            notifyPhotoError("Camera device error: " + error);
            stopSelf();
        }
    };

    private final CameraDevice.StateCallback videoStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device opened successfully");
            cameraOpenCloseLock.release();
            cameraDevice = camera;
            createCameraSessionInternal(true); // true for video
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device disconnected");
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            notifyVideoError(currentVideoId, "Camera disconnected");
            stopSelf();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera device error: " + error);
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            notifyVideoError(currentVideoId, "Camera device error: " + error);
            stopSelf();
        }
    };

    private void createCameraSessionInternal(boolean forVideo) {
        try {
            if (cameraDevice == null) {
                Log.e(TAG, "Camera device is null in createCameraSessionInternal");
                if (forVideo) notifyVideoError(currentVideoId, "Camera not initialized");
                else notifyPhotoError("Camera not initialized");
                stopSelf();
                return;
            }

            List<Surface> surfaces = new ArrayList<>();
            if (forVideo) {
                // Handle buffer mode or regular video
                Surface surfaceToUse = null;
                if (currentMode == RecordingMode.BUFFER && bufferManager != null) {
                    // Use buffer manager's current surface
                    surfaceToUse = bufferManager.getCurrentSurface();
                } else {
                    // Use regular recorder surface
                    surfaceToUse = recorderSurface;
                }

                if (surfaceToUse == null) {
                    notifyVideoError(currentVideoId, "Recorder surface null");
                    conditionalStopSelf();
                    return;
                }
                surfaces.add(surfaceToUse);
                recorderSurface = surfaceToUse; // Store for later use
                previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                previewBuilder.addTarget(surfaceToUse);
            } else {
                if (imageReader == null || imageReader.getSurface() == null) {
                    notifyPhotoError("ImageReader surface null");
                    stopSelf();
                    return;
                }
                surfaces.add(imageReader.getSurface());
                previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                previewBuilder.addTarget(imageReader.getSurface());
            }

            // Configure auto-exposure settings for better photo quality
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            // Use dynamic FPS range to prevent long exposure times that cause overexposure
            previewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange);

            // Apply user exposure compensation BEFORE capture (not during)
            previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, userExposureCompensation);

            // Use center-weighted metering for better subject exposure
            previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{
                new MeteringRectangle(0, 0, jpegSize.getWidth(), jpegSize.getHeight(), MeteringRectangle.METERING_WEIGHT_MAX)
            });

            // Enable autofocus with center-weighted focus region for better subject focus
            if (hasAutoFocus) {
                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                // Add center-weighted AF region for better subject focus
                int centerX = jpegSize.getWidth() / 2;
                int centerY = jpegSize.getHeight() / 2;
                int regionSize = Math.min(jpegSize.getWidth(), jpegSize.getHeight()) / 3; // 1/3 of image size
                int left = Math.max(0, centerX - regionSize / 2);
                int top = Math.max(0, centerY - regionSize / 2);
                int right = Math.min(jpegSize.getWidth() - 1, centerX + regionSize / 2);
                int bottom = Math.min(jpegSize.getHeight() - 1, centerY + regionSize / 2);

                previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{
                    new MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX)
                });

                Log.d(TAG, "AF region set to center area: " + left + "," + top + " -> " + right + "," + bottom);
            } else {
                Log.d(TAG, "Autofocus not available, using fixed focus");
            }

            // Set auto white balance
            previewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            // Enhanced image quality settings
            previewBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            previewBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);

            if (!forVideo) {
                // Photo-specific settings
                previewBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY);
                int displayOrientation = getDisplayRotation();
                int jpegOrientation = JPEG_ORIENTATION.get(displayOrientation, 90);
                previewBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
                Log.d(TAG, "Setting JPEG orientation: " + jpegOrientation + " for display orientation: " + displayOrientation);
            }

            CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    // Store the session atomically
                    synchronized (SERVICE_LOCK) {
                        cameraCaptureSession = session;
                    }
                    
                    if (forVideo) {
                        if (currentMode == RecordingMode.BUFFER) {
                            startBufferRecordingInternal();
                        } else {
                            startRecordingInternal();
                        }
                    } else {
                        // Mark camera as fully ready
                        synchronized (SERVICE_LOCK) {
                            isCameraReady = true;
                            Log.d(TAG, "Camera session configured and ready");
                        }
                        
                        // Check if we have any pending global queue requests to process
                        synchronized (SERVICE_LOCK) {
                            if (!globalRequestQueue.isEmpty()) {
                                Log.d(TAG, "Camera ready, processing " + globalRequestQueue.size() + " queued requests");
                                // Don't call processNextPhotoRequest here as it might try to reopen camera
                                // Instead, start the preview and then trigger the first photo
                                PhotoRequest firstRequest = globalRequestQueue.poll(); // Changed from peek() to poll() to remove from queue
                                if (firstRequest != null) {
                                    // Set up for the first queued photo
                                    if (firstRequest.callback == null && callbackRegistry.containsKey(firstRequest.requestId)) {
                                        firstRequest.callback = callbackRegistry.get(firstRequest.requestId);
                                    }
                                    sPhotoCallback = firstRequest.callback;
                                    pendingPhotoPath = firstRequest.filePath;
                                    pendingRequestedSize = firstRequest.size;
                                    // Store LED state from request
                                    pendingLedEnabled = firstRequest.enableLed;
                                }
                            }
                        }
                        
                        // Start proper preview for photos with AE state monitoring
                        startPreviewWithAeMonitoring();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure camera session for " + (forVideo ? "video" : "photo"));
                    if (forVideo)
                        notifyVideoError(currentVideoId, "Failed to configure camera for video");
                    else notifyPhotoError("Failed to configure camera for photo");
                    conditionalStopSelf();
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                List<OutputConfiguration> outputConfigurations = new ArrayList<>();
                for (Surface surface : surfaces) {
                    outputConfigurations.add(new OutputConfiguration(surface));
                }
                SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigurations, executor, sessionStateCallback);
                cameraDevice.createCaptureSession(config);
            } else {
                cameraDevice.createCaptureSession(surfaces, sessionStateCallback, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception in createCameraSessionInternal", e);
            if (forVideo) notifyVideoError(currentVideoId, "Camera access error");
            else notifyPhotoError("Camera access error");
            conditionalStopSelf();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Illegal state in createCameraSessionInternal", e);
            if (forVideo) notifyVideoError(currentVideoId, "Camera illegal state");
            else notifyPhotoError("Camera illegal state");
            conditionalStopSelf();
        }
    }

    private void startRecordingInternal() {
        if (cameraDevice == null || cameraCaptureSession == null || mediaRecorder == null) {
            notifyVideoError(currentVideoId, "Cannot start recording, camera not ready.");
            return;
        }
        try {
            cameraCaptureSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
            
            // Add small delay to ensure camera surface is connected and first frames are captured
            // This helps prevent audio-only recordings
            backgroundHandler.postDelayed(() -> {
                try {
                    if (cameraCaptureSession == null || recorderSurface == null || !recorderSurface.isValid()) {
                        Log.e(TAG, "Camera not ready for recording - surface invalid");
                        notifyVideoError(currentVideoId, "Camera not ready for recording");
                        return;
                    }
                    
                    mediaRecorder.start();
                    isRecording = true;
                    recordingStartTime = System.currentTimeMillis();
                    
                    // Clear pending settings after use
                    pendingVideoSettings = null;
                    if (sVideoCallback != null) {
                        sVideoCallback.onRecordingStarted(currentVideoId);
                    }
                    // Start progress timer if callback is interested
                    if (sVideoCallback != null) {
                        recordingTimer = new Timer();
                        recordingTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                if (isRecording && sVideoCallback != null) {
                                    long duration = System.currentTimeMillis() - recordingStartTime;
                                    sVideoCallback.onRecordingProgress(currentVideoId, duration);
                                }
                            }
                        }, 1000, 1000); // Update every second
                    }
                    Log.d(TAG, "Video recording started for: " + currentVideoId);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start recording after delay", e);
                    notifyVideoError(currentVideoId, "Failed to start recording: " + e.getMessage());
                    isRecording = false;
                }
            }, 100); // 100ms delay to ensure surface is ready
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to start video recording", e);
            notifyVideoError(currentVideoId, "Failed to start recording: " + e.getMessage());
            isRecording = false;
        }
    }

    /**
     * Start buffer recording internally after camera session is configured
     */
    private void startBufferRecordingInternal() {
        if (cameraDevice == null || cameraCaptureSession == null || bufferManager == null) {
            Log.e(TAG, "Cannot start buffer recording, camera not ready");
            if (sBufferCallback != null) {
                sBufferCallback.onBufferError("Camera not ready");
            }
            return;
        }

        try {
            // Start repeating request for continuous recording
            cameraCaptureSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);

            // Start recording on current segment
            bufferManager.startCurrentSegment();

            Log.d(TAG, "Buffer recording started on segment");
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to start buffer recording", e);
            if (sBufferCallback != null) {
                sBufferCallback.onBufferError("Failed to start: " + e.getMessage());
            }
        }
    }

        /**
     * Choose the optimal size from available choices based on desired dimensions.
     * Finds the size with the smallest total difference between requested and available dimensions.
     *
     * @param choices Available size options
     * @param desiredWidth Target width
     * @param desiredHeight Target height
     * @return The closest matching size, or null if no choices available
     */
    private Size chooseOptimalSize(Size[] choices, int desiredWidth, int desiredHeight) {
        if (choices == null || choices.length == 0) {
            Log.w(TAG, "No size choices available");
            return null;
        }

        // First, try to find an exact match
        for (Size option : choices) {
            if (option.getWidth() == desiredWidth && option.getHeight() == desiredHeight) {
                Log.d(TAG, "Found exact size match: " + option.getWidth() + "x" + option.getHeight());
                return option;
            }
        }

        // No exact match found, find the size with smallest total dimensional difference
        Log.d(TAG, "No exact match found, finding closest size to " + desiredWidth + "x" + desiredHeight);

        Size bestSize = choices[0];
        int smallestDifference = Integer.MAX_VALUE;

        for (Size option : choices) {
            int widthDiff = Math.abs(option.getWidth() - desiredWidth);
            int heightDiff = Math.abs(option.getHeight() - desiredHeight);
            int totalDifference = widthDiff + heightDiff;

            if (totalDifference < smallestDifference) {
                smallestDifference = totalDifference;
                bestSize = option;
            }
        }

        Log.d(TAG, "Selected optimal size: " + bestSize.getWidth() + "x" + bestSize.getHeight() +
              " (total difference: " + smallestDifference + ")");

        return bestSize;
    }

    private void notifyVideoError(String videoId, String errorMessage) {
        if (sVideoCallback != null && videoId != null) {
            executor.execute(() -> sVideoCallback.onRecordingError(videoId, errorMessage));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (SERVICE_LOCK) {
            Log.d(TAG, "CameraNeo service destroying - Setting state to IDLE");
            serviceState = ServiceState.STOPPING;
            
            // Cancel keep-alive timer if it's running
            cancelKeepAliveTimer();
            if (isRecording) {
                stopCurrentVideoRecording(currentVideoId);
            }
            closeCamera();
            stopBackgroundThread();
            releaseWakeLocks();
            
            // Update static state
            isServiceRunning = false;
            isServiceStarting = false;
            isCameraReady = false;
            serviceState = ServiceState.IDLE;
            sInstance = null;
            
            // Process any remaining queued requests with error callbacks
            while (!globalRequestQueue.isEmpty()) {
                PhotoRequest request = globalRequestQueue.poll();
                if (request != null && request.callback != null) {
                    Log.w(TAG, "Service destroyed with pending request: " + request.requestId);
                    request.callback.onPhotoError("Camera service terminated unexpectedly");
                }
            }
        }
    }

    private void notifyPhotoCaptured(String filePath) {
        if (sPhotoCallback != null) {
            executor.execute(() -> sPhotoCallback.onPhotoCaptured(filePath));
        }
    }

    private void notifyPhotoError(String errorMessage) {
        if (sPhotoCallback != null) {
            executor.execute(() -> sPhotoCallback.onPhotoError(errorMessage));
        }
    }

    /**
     * Start background thread
     */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraNeoBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * Stop background thread
     */
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted when stopping background thread", e);
            }
        }
    }

    /**
     * Close camera resources
     */
    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            if (recorderSurface != null) {
                recorderSurface.release();
                recorderSurface = null;
            }
            // Reset keep-alive flag when camera is actually closed
            isCameraKeptAlive = false;
            
            // Turn off LED when camera closes
            if (pendingLedEnabled && hardwareManager != null && hardwareManager.supportsRecordingLed()) {
                Log.d(TAG, "📸 Turning off camera LED (camera closed)");
                hardwareManager.setRecordingLedOff();
                pendingLedEnabled = false;  // Reset LED state
            }
            
            releaseWakeLocks();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while closing camera", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /**
     * Start the keep-alive timer to keep camera open for rapid successive shots
     */
    private void startKeepAliveTimer() {
        Log.d(TAG, "Starting camera keep-alive timer for " + CAMERA_KEEP_ALIVE_MS + "ms");
        
        // Cancel any existing timer first
        cancelKeepAliveTimer();
        
        // Mark camera as kept alive
        isCameraKeptAlive = true;
        
        // Create new timer
        cameraKeepAliveTimer = new Timer();
        cameraKeepAliveTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "Camera keep-alive timer expired, closing camera");
                // Run on background handler to ensure proper thread
                if (backgroundHandler != null) {
                    backgroundHandler.post(() -> {
                        isCameraKeptAlive = false;
                        closeCamera();
                        stopSelf();
                    });
                } else {
                    // Fallback if handler is not available
                    isCameraKeptAlive = false;
                    closeCamera();
                    stopSelf();
                }
            }
        }, CAMERA_KEEP_ALIVE_MS);
    }
    
    /**
     * Cancel the keep-alive timer
     */
    private void cancelKeepAliveTimer() {
        if (cameraKeepAliveTimer != null) {
            Log.d(TAG, "Cancelling camera keep-alive timer");
            cameraKeepAliveTimer.cancel();
            cameraKeepAliveTimer = null;
        }
    }

    /**
     * Release wake locks to avoid battery drain
     */
    private void releaseWakeLocks() {
        // Use the WakeLockManager to release all wake locks
        WakeLockManager.releaseAllWakeLocks();
    }

    /**
     * Force the screen to turn on so camera can be accessed
     */
    private void wakeUpScreen() {
        Log.d(TAG, "Waking up screen for camera access");
        // Use the WakeLockManager to acquire both CPU and screen wake locks
        WakeLockManager.acquireFullWakeLockAndBringToForeground(this, 180000, 5000);
    }

    /**
     * Attempt to restart the camera service with different parameters if needed
     */
    private void restartCameraServiceIfNeeded() {
        try {
            // First, release all current camera resources
            releaseCameraResources();

            Log.d(TAG, "Camera service restart attempt made - waiting for system to release camera");

            // Implement retry mechanism with delay to handle policy-disabled errors
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "Attempting camera restart with delayed retry");

                // Try with a different camera ID if available
                CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                if (manager != null) {
                    try {
                        String[] cameraIds = manager.getCameraIdList();
                        // If we were using camera "0", try a different one if available
                        if (cameraIds.length > 1 && "0".equals(cameraId)) {
                            this.cameraId = "1";
                            Log.d(TAG, "Switching to alternate camera ID: " + this.cameraId);
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error accessing camera during retry", e);
                    }
                }

                // Request camera focus - this can help on some devices by signaling
                // to the system that camera is needed
                wakeUpScreen();

                // Try releasing all app camera resources forcibly
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }

                if (cameraCaptureSession != null) {
                    cameraCaptureSession.close();
                    cameraCaptureSession = null;
                }

                System.gc(); // Request garbage collection
            }, 1000); // Short delay before retry
        } catch (Exception e) {
            Log.e(TAG, "Error in camera service restart", e);
        }
    }

    /**
     * Release all camera system resources
     */
    private void releaseCameraResources() {
        try {
            // Request to release system-wide camera resources
            closeCamera();

            // For policy-based restrictions, we need to ensure camera resources are fully released
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // On newer Android versions, encourage system resource release
                CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                if (manager != null) {
                    // Nothing we can directly do to force release, but we can
                    // make sure our resources are gone
                    if (cameraDevice != null) {
                        cameraDevice.close();
                        cameraDevice = null;
                    }
                    System.gc();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing camera resources", e);
        }
    }

    // -----------------------------------------------------------------------------------
    // Notification handling
    // -----------------------------------------------------------------------------------

    private void showNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(false);

        // Start in foreground
        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Camera Neo Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Query camera capabilities for dynamic auto-exposure
     */
    private void queryCameraCapabilities(CameraCharacteristics characteristics) {
        // Get available AE modes
        availableAeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if (availableAeModes == null) {
            availableAeModes = new int[]{CaptureRequest.CONTROL_AE_MODE_ON};
        }

        // Get exposure compensation range and step
        exposureCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (exposureCompensationRange == null) {
            exposureCompensationRange = Range.create(-2, 2); // Default range
        }

        exposureCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (exposureCompensationStep == null) {
            exposureCompensationStep = new Rational(1, 6); // Default 1/6 EV step
        }

        // Get available FPS ranges
        availableFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (availableFpsRanges == null || availableFpsRanges.length == 0) {
            selectedFpsRange = Range.create(30, 30); // Default to 30fps
        } else {
            // Choose optimal FPS range - prefer 30fps for photos, allow higher max for flexibility
            selectedFpsRange = chooseOptimalFpsRange(availableFpsRanges);
        }

        // Autofocus capabilities
        availableAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

        // Check if continuous picture autofocus is available
        hasAutoFocus = false;
        if (availableAfModes != null) {
            for (int mode : availableAfModes) {
                if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                    hasAutoFocus = true;
                    break;
                }
            }
        }

        // Handle potential null value for minimum focus distance
        Float minimumFocusDistanceBoxed = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        minimumFocusDistance = (minimumFocusDistanceBoxed != null) ? minimumFocusDistanceBoxed : 0.0f;

        Log.d(TAG, "Camera capabilities - AE modes: " + java.util.Arrays.toString(availableAeModes));
        Log.d(TAG, "Exposure compensation range: " + exposureCompensationRange + ", step: " + exposureCompensationStep);
        Log.d(TAG, "Selected FPS range: " + selectedFpsRange);
        Log.d(TAG, "Autofocus available: " + hasAutoFocus + ", min focus distance: " + minimumFocusDistance);
    }

    /**
     * Choose optimal FPS range for photo capture
     */
    private Range<Integer> chooseOptimalFpsRange(Range<Integer>[] ranges) {
        // Prefer ranges that include 30fps and don't go too low (prevents long exposure times)
        for (Range<Integer> range : ranges) {
            if (range.contains(30) && range.getLower() >= 15) {
                return range;
            }
        }

        // Fallback: choose range with highest minimum FPS
        Range<Integer> best = ranges[0];
        for (Range<Integer> range : ranges) {
            if (range.getLower() > best.getLower()) {
                best = range;
            }
        }
        return best;
    }

    /**
     * Start preview with AE monitoring - called when camera session is ready
     */
    private void startPreviewWithAeMonitoring() {
        try {
            // Check if session is still valid before using it
            if (cameraCaptureSession == null) {
                Log.e(TAG, "Camera capture session is null in startPreviewWithAeMonitoring");
                notifyPhotoError("Camera session not ready");
                closeCamera();
                stopSelf();
                return;
            }
            
            // Start repeating preview request with AE monitoring
            cameraCaptureSession.setRepeatingRequest(previewBuilder.build(),
                aeCallback, backgroundHandler);

            // Trigger the capture sequence immediately
            startPrecaptureSequence();

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error starting preview with AE monitoring", e);
            notifyPhotoError("Error starting preview: " + e.getMessage());
            cancelKeepAliveTimer();
            closeCamera();
            stopSelf();
        }
    }

    /**
     * Start simplified AE convergence sequence
     */
    private void startPrecaptureSequence() {
        try {
            shotState = ShotState.WAITING_AE;
            aeStartTimeNs = System.nanoTime();

            // Start AE convergence - autofocus runs automatically in CONTINUOUS_PICTURE mode
            Log.d(TAG, "Starting AE convergence" + (hasAutoFocus ? " (autofocus runs automatically)" : "") + "...");

            // Trigger only AE precapture - no manual AF trigger needed for CONTINUOUS_PICTURE
            previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            cameraCaptureSession.capture(previewBuilder.build(), aeCallback, backgroundHandler);

            Log.d(TAG, "Triggered AE precapture, waiting for convergence...");

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error starting AE convergence", e);
            notifyPhotoError("Error starting AE convergence: " + e.getMessage());
            shotState = ShotState.IDLE;
            cancelKeepAliveTimer();
            closeCamera();
            stopSelf();
        }
    }

    /**
     * Simplified AE callback that waits briefly for exposure convergence
     * Autofocus runs automatically in CONTINUOUS_PICTURE mode
     */
    private class SimplifiedAeCallback extends CameraCaptureSession.CaptureCallback {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                     @NonNull CaptureRequest request,
                                     @NonNull TotalCaptureResult result) {

            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            // Suppress verbose AE logging to prevent logcat overflow
            // Only log important state transitions

            if (aeState == null) {
                Log.w(TAG, "AE_STATE is null, proceeding with capture anyway");
                if (shotState == ShotState.WAITING_AE) {
                    Log.d(TAG, "No AE state available, capturing immediately");
                    capturePhoto();
                }
                return;
            }

            switch (shotState) {
                case WAITING_AE:
                    // Simple AE convergence check - no AF state checking needed
                    boolean aeConverged = (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                         aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                         aeState == CaptureResult.CONTROL_AE_STATE_LOCKED);

                    boolean timeout = (System.nanoTime() - aeStartTimeNs) > AE_WAIT_NS;

                    if (aeConverged || timeout) {
                        Log.d(TAG, "AE ready (AE: " + getAeStateName(aeState) +
                             (timeout ? " - timeout)" : ")") + ", capturing photo...");
                        capturePhoto();
                    } else {
                        // Suppress convergence logging - too verbose
                    }
                    break;

                case SHOOTING:
                    // Photo capture in progress - suppressed log
                    break;

                case IDLE:
                default:
                    // Ignore callbacks when idle
                    break;
            }
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                  @NonNull CaptureRequest request,
                                  @NonNull CaptureFailure failure) {
            Log.e(TAG, "Capture failed during AE sequence: " + failure.getReason());
            notifyPhotoError("AE sequence failed: " + failure.getReason());
            shotState = ShotState.IDLE;
            cancelKeepAliveTimer();
            closeCamera();
            stopSelf();
        }
    }

    /**
     * Simplified photo capture - relies on AE convergence and automatic CONTINUOUS_PICTURE autofocus
     */
    private void capturePhoto() {
        try {
            shotState = ShotState.SHOOTING;

            // Create still capture request with high quality settings
            CaptureRequest.Builder stillBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            stillBuilder.addTarget(imageReader.getSurface());

            // Copy settings from preview
            stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            stillBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            stillBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, userExposureCompensation);
            stillBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange);

            // Set up continuous autofocus (no manual triggers needed)
            if (hasAutoFocus) {
                stillBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                // Add center-weighted AF region for better subject focus
                int centerX = jpegSize.getWidth() / 2;
                int centerY = jpegSize.getHeight() / 2;
                int regionSize = Math.min(jpegSize.getWidth(), jpegSize.getHeight()) / 3;
                int left = Math.max(0, centerX - regionSize / 2);
                int top = Math.max(0, centerY - regionSize / 2);
                int right = Math.min(jpegSize.getWidth() - 1, centerX + regionSize / 2);
                int bottom = Math.min(jpegSize.getHeight() - 1, centerY + regionSize / 2);

                stillBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{
                    new MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX)
                });

                // Also add center-weighted AE region for consistent exposure
                stillBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{
                    new MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX)
                });
            }

            // High quality settings
            stillBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            stillBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            stillBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY);
            int displayOrientation = getDisplayRotation();
            int jpegOrientation = JPEG_ORIENTATION.get(displayOrientation, 90);
            stillBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
            Log.d(TAG, "Capturing photo with JPEG orientation: " + jpegOrientation + " for display orientation: " + displayOrientation);

            // Capture the photo immediately
            cameraCaptureSession.capture(stillBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                             @NonNull CaptureRequest request,
                                             @NonNull TotalCaptureResult result) {
                    Log.i(TAG, "Photo capture completed successfully");  // Keep as INFO level
                    // Image processing will happen in ImageReader callback
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                          @NonNull CaptureRequest request,
                                          @NonNull CaptureFailure failure) {
                    Log.e(TAG, "Photo capture failed: " + failure.getReason());
                    notifyPhotoError("Photo capture failed: " + failure.getReason());
                    shotState = ShotState.IDLE;
                    cancelKeepAliveTimer();
                    closeCamera();
                    stopSelf();
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error during photo capture", e);
            notifyPhotoError("Error capturing photo: " + e.getMessage());
            shotState = ShotState.IDLE;
            cancelKeepAliveTimer();
            closeCamera();
            stopSelf();
        }
    }

    /**
     * Get human-readable AE state name for logging
     */
    private String getAeStateName(int aeState) {
        switch (aeState) {
            case CaptureResult.CONTROL_AE_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AE_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AE_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AE_STATE_LOCKED: return "LOCKED";
            case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: return "FLASH_REQUIRED";
            case CaptureResult.CONTROL_AE_STATE_PRECAPTURE: return "PRECAPTURE";
            default: return "UNKNOWN(" + aeState + ")";
        }
    }

    /**
     * Get human-readable AF state name for logging
     */
    private String getAfStateName(int afState) {
        switch (afState) {
            case CaptureResult.CONTROL_AF_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN: return "PASSIVE_SCAN";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: return "PASSIVE_FOCUSED";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: return "PASSIVE_UNFOCUSED";
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN: return "ACTIVE_SCAN";
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED: return "FOCUSED_LOCKED";
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: return "NOT_FOCUSED_LOCKED";
            default: return "UNKNOWN(" + afState + ")";
        }
    }

    // ========== BUFFER MODE METHODS ==========

    /**
     * Start buffer recording mode
     */
    private void startBufferMode() {
        Log.d(TAG, "Starting buffer mode");

        if (isInBufferMode) {
            Log.w(TAG, "Already in buffer mode");
            return;
        }

        // Initialize buffer manager
        bufferManager = new CircularVideoBufferInternal(this);
        bufferManager.setCallback(new CircularVideoBufferInternal.SegmentSwitchCallback() {
            @Override
            public void onSegmentSwitch(int newSegmentIndex, Surface newSurface) {
                Log.d(TAG, "Buffer segment switch to index " + newSegmentIndex);
                // Handle segment switch - update camera session with new surface
                if (cameraCaptureSession != null && previewBuilder != null) {
                    try {
                        previewBuilder.removeTarget(recorderSurface);
                        recorderSurface = newSurface;
                        previewBuilder.addTarget(recorderSurface);
                        cameraCaptureSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error switching buffer segment", e);
                    }
                }
            }

            @Override
            public void onBufferError(String error) {
                Log.e(TAG, "Buffer error: " + error);
                if (sBufferCallback != null) {
                    sBufferCallback.onBufferError(error);
                }
            }

            @Override
            public void onSegmentReady(int segmentIndex, String filePath) {
                Log.d(TAG, "Buffer segment " + segmentIndex + " ready: " + filePath);
            }
        });

        try {
            // Prepare all MediaRecorder instances
            bufferManager.prepareAllRecorders();

            // Set mode and open camera
            currentMode = RecordingMode.BUFFER;
            isInBufferMode = true;

            // Wake up screen and open camera
            wakeUpScreen();
            openCameraInternal(null, true); // true for video mode

            // Initialize segment switch handler
            segmentSwitchHandler = new Handler(Looper.getMainLooper());

            // Schedule first segment switch
            scheduleNextSegmentSwitch();

            if (sBufferCallback != null) {
                sBufferCallback.onBufferStarted();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start buffer mode", e);
            isInBufferMode = false;
            if (sBufferCallback != null) {
                sBufferCallback.onBufferError("Failed to start: " + e.getMessage());
            }
            stopSelf();
        }
    }

    /**
     * Schedule next segment switch for buffer mode
     */
    private void scheduleNextSegmentSwitch() {
        if (segmentSwitchHandler != null && isInBufferMode) {
            segmentSwitchHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isInBufferMode && bufferManager != null) {
                        Log.d(TAG, "Segment timer triggered - switching to next segment");
                        bufferManager.switchToNextSegment();
                        // Schedule next switch
                        scheduleNextSegmentSwitch();
                    }
                }
            }, SEGMENT_DURATION_MS);
        }
    }

    /**
     * Stop buffer recording mode
     */
    private void stopBufferMode() {
        Log.d(TAG, "Stopping buffer mode");

        if (!isInBufferMode) {
            Log.w(TAG, "Not in buffer mode");
            return;
        }

        isInBufferMode = false;
        currentMode = RecordingMode.SINGLE_VIDEO;

        // Cancel segment switching
        if (segmentSwitchHandler != null) {
            segmentSwitchHandler.removeCallbacksAndMessages(null);
            segmentSwitchHandler = null;
        }

        // Stop buffer manager
        if (bufferManager != null) {
            bufferManager.stopBuffering();
            bufferManager = null;
        }

        // Close camera
        closeCamera();

        if (sBufferCallback != null) {
            sBufferCallback.onBufferStopped();
        }

        stopSelf();
    }

}
