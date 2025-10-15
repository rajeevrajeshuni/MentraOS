package com.augmentos.augmentos_core;

import static com.augmentos.augmentos_core.smarterglassesmanager.smartglassescommunicators.EvenRealitiesG1SGC.deleteEvenSharedPreferences;
import static com.augmentos.augmentos_core.smarterglassesmanager.smartglassescommunicators.EvenRealitiesG1SGC.savePreferredG1DeviceId;
import static com.augmentos.augmentos_core.statushelpers.CoreVersionHelper.getCoreVersion;
import static com.augmentos.augmentos_core.statushelpers.JsonHelper.processJSONPlaceholders;
import static com.augmentos.augmentoslib.AugmentOSGlobalConstants.AUGMENTOS_NOTIFICATION_ID;
import static com.augmentos.augmentoslib.AugmentOSGlobalConstants.AugmentOSAsgClientPackageName;
import static com.augmentos.augmentoslib.AugmentOSGlobalConstants.AugmentOSManagerPackageName;
import static com.augmentos.augmentos_core.BatteryOptimizationHelper.handleBatteryOptimization;
import static com.augmentos.augmentos_core.BatteryOptimizationHelper.isSystemApp;
import static com.augmentos.augmentos_core.Constants.notificationFilterKey;
import static com.augmentos.augmentos_core.Constants.newsSummaryKey;


import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;
import androidx.preference.PreferenceManager;

import com.augmentos.augmentos_core.augmentos_backend.AuthHandler;
import com.augmentos.augmentos_core.augmentos_backend.ServerComms;
import com.augmentos.augmentos_core.augmentos_backend.ServerCommsCallback;
import com.augmentos.augmentos_core.augmentos_backend.ThirdPartyCloudApp;
import com.augmentos.augmentos_core.augmentos_backend.WebSocketLifecycleManager;
import com.augmentos.augmentos_core.augmentos_backend.WebSocketManager;
import com.augmentos.augmentos_core.enums.SpeechRequiredDataType;
import com.augmentos.augmentos_core.enums.SpeechRequiredDataType;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.BatteryLevelEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.ButtonPressEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.CaseEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesBluetoothSearchDiscoverEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesBluetoothSearchStopEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesHeadDownEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesHeadUpEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.LocalTranscriptionEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesDisplayPowerEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesWifiScanResultEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesWifiStatusChange;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesHotspotStatusChange;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesGalleryStatusEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.HeadUpAngleEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.KeepAliveAckEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.MicModeChangedEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.RtmpStreamStatusEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.BleCommandReceiver;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.BleCommandSender;
import com.augmentos.augmentos_core.smarterglassesmanager.supportedglasses.SmartGlassesDevice;
import com.augmentos.augmentos_core.smarterglassesmanager.utils.BitmapJavaUtils;
import com.augmentos.augmentos_core.smarterglassesmanager.utils.SmartGlassesConnectionState;
import com.augmentos.augmentos_core.smarterglassesmanager.SmartGlassesManager;
import com.augmentos.augmentos_core.smarterglassesmanager.smartglassescommunicators.MentraNexSGC;
import com.augmentos.augmentoslib.ThirdPartyEdgeApp;
import com.augmentos.augmentos_core.comms.AugmentOsActionsCallback;
import com.augmentos.augmentos_core.comms.AugmentosBlePeripheral;
import com.augmentos.augmentos_core.events.NewScreenImageEvent;
import com.augmentos.augmentos_core.events.ThirdPartyEdgeAppErrorEvent;
import com.augmentos.augmentos_core.events.TriggerSendStatusToAugmentOsManagerEvent;
import com.augmentos.augmentos_core.statushelpers.BatteryStatusHelper;
import com.augmentos.augmentos_core.statushelpers.GsmStatusHelper;
import com.augmentos.augmentos_core.statushelpers.WifiStatusHelper;
import com.augmentos.augmentos_core.app.EdgeAppSystem;


import com.augmentos.augmentoslib.events.GlassesTapOutputEvent;
import com.augmentos.augmentoslib.events.SmartRingButtonOutputEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Collections;
import java.util.List;
import java.util.Map;
//SpeechRecIntermediateOutputEvent

import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.isMicEnabledForFrontendEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.hci.PhoneMicrophoneManager;
import com.augmentos.augmentos_core.smarterglassesmanager.smartglassesconnection.SmartGlassesRepresentative;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesVersionInfoEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.DownloadProgressEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.InstallationProgressEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.GlassesSerialNumberEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.DisplayTextEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.DisplayImageEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.PairFailureEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.ProtobufSchemaVersionEvent;
import com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.ProtocolVersionResponseEvent;

public class AugmentosService extends LifecycleService implements AugmentOsActionsCallback {
    public static final String TAG = "AugmentOSService";

    private final IBinder binder = new LocalBinder();

    private final String notificationAppName = "MentraOS";
    private final String notificationDescription = "";
    private final String myChannelId = "augmentos_core";
    public static final String ACTION_START_CORE = "ACTION_START_CORE";
    public static final String ACTION_STOP_CORE = "ACTION_STOP_CORE";

    public static final String ACTION_START_FOREGROUND_SERVICE = "MY_ACTION_START_FOREGROUND_SERVICE";
    public static final String ACTION_STOP_FOREGROUND_SERVICE = "MY_ACTION_STOP_FOREGROUND_SERVICE";

    private BatteryStatusHelper batteryStatusHelper;
    private WifiStatusHelper wifiStatusHelper;
    private GsmStatusHelper gsmStatusHelper;

    private AuthHandler authHandler;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private final Handler screenCaptureHandler = new Handler();
    private Runnable screenCaptureRunnable;
    private LocationSystem locationSystem;
    private boolean locationSystemBound = false;
    private long currTime = 0;
    private long lastPressed = 0;
    private final long lastTapped = 0;

    // Double clicking constants
    private final long doublePressTimeConst = 420;
    private final long doubleTapTimeConst = 600;

    public EdgeAppSystem edgeAppSystem;

    private String userId;
    public SmartGlassesConnectionState previousSmartGlassesConnectionState = SmartGlassesConnectionState.DISCONNECTED;


    public AugmentosBlePeripheral blePeripheral;

    public SmartGlassesManager smartGlassesManager;
    private boolean smartGlassesManagerBound = false;
    private final TranscriptProcessor transcriptProcessor = new TranscriptProcessor(30, 3); // 30 chars per line, 3 lines max
    private final List<Runnable> smartGlassesReadyListeners = new ArrayList<>();

    private byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Connection to SmartGlassesManager service
     */
    private ServiceConnection smartGlassesServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            SmartGlassesManager.SmartGlassesBinder binder = (SmartGlassesManager.SmartGlassesBinder) service;
            smartGlassesManager = binder.getService();
            smartGlassesManager.setLifecycleOwnerAndEventHandler(AugmentosService.this, smartGlassesEventHandler);
            smartGlassesManagerBound = true;

            // Set it in the EdgeAppSystem
            if (edgeAppSystem != null) {
                edgeAppSystem.setSmartGlassesManager(smartGlassesManager);
            }

            // Execute any pending actions
            for (Runnable action : smartGlassesReadyListeners) {
                action.run();
            }
            smartGlassesReadyListeners.clear();

            Log.d(TAG, "SmartGlassesManager service bound");
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            smartGlassesManager = null;
            smartGlassesManagerBound = false;
            Log.d(TAG, "SmartGlassesManager service unbound");

            // Update EdgeAppSystem
            if (edgeAppSystem != null) {
                edgeAppSystem.setSmartGlassesManager(null);
            }

            // Update connection state
            if (webSocketLifecycleManager != null) {
                webSocketLifecycleManager.updateSmartGlassesState(SmartGlassesConnectionState.DISCONNECTED);
            }
        }
    };

    /**
     * Connection to LocationSystem service
     */
    private ServiceConnection locationServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            LocationSystem.LocationBinder binder = (LocationSystem.LocationBinder) service;
            locationSystem = binder.getService();
            locationSystemBound = true;
            Log.d(TAG, "LocationSystem service bound");
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            locationSystem = null;
            locationSystemBound = false;
            Log.d(TAG, "LocationSystem service unbound");
        }
    };

    private NotificationSystem notificationSystem;
    private CalendarSystem calendarSystem;

    private Integer batteryLevel;
    private Boolean glassesCharging;
    private Integer caseBatteryLevel;
    private Boolean caseCharging;
    private Boolean caseOpen;
    private Boolean caseRemoved;
    private Integer brightnessLevel;
    private Boolean autoBrightness;
    private Integer headUpAngle;
    private Integer dashboardHeight;
    private Integer dashboardDepth;

    // WiFi status for glasses that require WiFi (e.g., Mentra Live)
    private boolean glassesNeedWifiCredentials = false;
    private boolean glassesWifiConnected = false;

    // Track current foreground service type
    private int currentForegroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    private String glassesWifiSsid = "";
    private String glassesWifiLocalIp = "";

    // Hotspot status for glasses that support hotspot functionality
    private boolean glassesHotspotEnabled = false;
    private String glassesHotspotSsid = "";
    private String glassesHotspotPassword = "";
    private String glassesHotspotGatewayIp = "";

    // WiFi scan results
    private List<String> wifiNetworks = new ArrayList<>();
    private String preferredMic;

    private final boolean showingDashboardNow = false;
    private boolean contextualDashboardEnabled;
    private boolean alwaysOnStatusBarEnabled;
    private AsrPlanner asrPlanner;

    JSONObject cachedDashboardDisplayObject;
    private JSONObject cachedDisplayData;
    {
        cachedDisplayData = new JSONObject();
        try {
            JSONObject layout = new JSONObject();
            layout.put("layoutType", "empty");
            cachedDisplayData.put("layout", layout);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct cachedDisplayData JSON", e);
        }
    }

    Runnable cachedDashboardDisplayRunnable;
    private String cachedDashboardTopLine;

    List<ThirdPartyCloudApp> cachedThirdPartyAppList = new ArrayList<>(); // Initialize here to avoid NPE
    private WebSocketManager.IncomingMessageHandler.WebSocketStatus webSocketStatus = WebSocketManager.IncomingMessageHandler.WebSocketStatus.DISCONNECTED;
    private final Handler serverCommsHandler = new Handler(Looper.getMainLooper());

    private WebSocketLifecycleManager webSocketLifecycleManager;
    private boolean isMicEnabledForFrontend = false;

    private boolean isInitializing = false;

    private boolean metricSystemEnabled;

    // Handler and Runnable for periodic datetime sending
    private final Handler datetimeHandler = new Handler(Looper.getMainLooper());
    private Runnable datetimeRunnable;

    // Handler and Runnable to clear the glasses screen after displaying text
    private final Handler clearScreenHandler = new Handler(Looper.getMainLooper());
    private Runnable clearScreenRunnable;

    // Add fields to cache latest glasses version info
    private String glassesAppVersion = null;
    private String glassesBuildNumber = null;
    private String glassesDeviceModel = null;
    private String glassesAndroidVersion = null;
    private String glassesOtaVersionUrl = null;
    private String glassesSerialNumber = null;
    private String glassesStyle = null;
    private String glassesColor = null;
    private String protobufSchemaVersion = "Unknown";
    private String glassesProtobufVersion = "Unknown";

    // OTA progress tracking
    private DownloadProgressEvent.DownloadStatus downloadStatus = null;
    private int downloadProgress = 0;
    private long downloadBytesDownloaded = 0;
    private long downloadTotalBytes = 0;
    private String downloadErrorMessage = null;
    private long downloadTimestamp = 0;

    private InstallationProgressEvent.InstallationStatus installationStatus = null;
    private String installationApkPath = null;
    private String installationErrorMessage = null;
    private long installationTimestamp = 0;

    public AugmentosService() {
    }

    // Smart glasses event handler
    private final SmartGlassesManager.SmartGlassesEventHandler smartGlassesEventHandler =
            new SmartGlassesManager.SmartGlassesEventHandler() {
                @Override
                public void onGlassesConnectionStateChanged(SmartGlassesDevice device, SmartGlassesConnectionState connectionState) {
                    if (connectionState == previousSmartGlassesConnectionState) return;
                    previousSmartGlassesConnectionState = connectionState;

                    webSocketLifecycleManager.updateSmartGlassesState(connectionState);

                    ServerComms.getInstance().sendGlassesConnectionState(device == null ? null : device.deviceModelName, connectionState.name());

                    if (connectionState == SmartGlassesConnectionState.CONNECTED) {
                        Log.d(TAG, "Got event for onGlassesConnected.. CONNECTED ..");
                        Log.d(TAG, "****************** SENDING REFERENCE CARD: CONNECTED TO AUGMENT OS");
                        isInitializing = true;
                        playStartupSequenceOnSmartGlasses();
                        asrPlanner.updateAsrLanguages();
                        ServerComms.getInstance().requestSettingsFromServer();

                        // Upgrade service type to avoid Android 15's 6-hour dataSync timeout
                        upgradeForegroundServiceType();
                    } else if (connectionState == SmartGlassesConnectionState.DISCONNECTED) {
                        edgeAppSystem.stopAllThirdPartyApps();

                        // Reset WiFi status when glasses disconnect
                        glassesWifiConnected = false;
                        glassesWifiSsid = "";
                        glassesWifiLocalIp = "";
                        
                        // Reset hotspot status when glasses disconnect
                        glassesHotspotEnabled = false;
                        glassesHotspotSsid = "";
                        glassesHotspotPassword = "";
                        glassesHotspotGatewayIp = "";
                    }

                    sendStatusToAugmentOsManager();
                }
            };

    public void onTriggerSendStatusToAugmentOsManagerEvent(TriggerSendStatusToAugmentOsManagerEvent event) {
        sendStatusToAugmentOsManager();
    }

    @Subscribe
    public void onLocalTranscriptionEvent(LocalTranscriptionEvent event) {
        if (smartGlassesManager != null) {
            transcriptProcessor.modifyLanguage(event.language);
            String processedText = transcriptProcessor.processString(event.text, event.isFinal);
            JSONObject displayJson = new JSONObject();
            JSONObject layoutJson = new JSONObject();
            try {
                layoutJson.put("layoutType", "text_wall");
                layoutJson.put("text", processedText);
                displayJson.put("layout", layoutJson);
                displayJson.put("type", "display_event");
                displayJson.put("view", "main");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to construct transcription display JSON", e);
            }
            if (processedText != null) {
                blePeripheral.sendGlassesDisplayEventToManager(displayJson);
                smartGlassesManager.sendTextWall(processedText);
                // Schedule screen clear after 10 seconds, cancelling previous if pending
                // In case of online captions cloud takes care of this
                if (clearScreenRunnable != null) {
                    clearScreenHandler.removeCallbacks(clearScreenRunnable);
                }
                clearScreenRunnable = () -> {
                    if (smartGlassesManager != null) {
                        JSONObject displayJsonScheduled = new JSONObject();
                        JSONObject layoutJsonScheduled = new JSONObject();
                        try {
                            layoutJsonScheduled.put("layoutType", "text_wall");
                            layoutJsonScheduled.put("text", "");
                            displayJsonScheduled.put("layout", layoutJsonScheduled);
                            displayJsonScheduled.put("type", "display_event");
                            displayJsonScheduled.put("view", "main");
                        } catch (JSONException e) {
                            Log.e(TAG, "Failed to construct transcription display JSON", e);
                        }   
                        blePeripheral.sendGlassesDisplayEventToManager(displayJsonScheduled);
                        smartGlassesManager.sendTextWall("");
                    }
                };
                clearScreenHandler.postDelayed(clearScreenRunnable, 10_000);
            }
        }
    }

    @Subscribe
    public void onGlassesHeadUpEvent(GlassesHeadUpEvent event){
        ServerComms.getInstance().sendHeadPosition("up");
        // BATTERY OPTIMIZATION: Directly call method instead of posting additional event
        if (contextualDashboardEnabled && smartGlassesManager != null) {
            try {
                displayGlassesDashboardEvent();
            } catch (JSONException e) {
                Log.e(TAG, "Error displaying dashboard", e);
            }
        }
    }

    @Subscribe
    public void onGlassesHeadDownEvent(GlassesHeadDownEvent event) {
        ServerComms.getInstance().sendHeadPosition("down");
        if (smartGlassesManager != null)
            smartGlassesManager.windowManager.hideDashboard();
    }

    @Subscribe
    public void onGlassesTapSideEvent(GlassesTapOutputEvent event) {
        int numTaps = event.numTaps;
        boolean sideOfGlasses = event.sideOfGlasses;
        long time = event.timestamp;

        Log.d(TAG, "GLASSES TAPPED X TIMES: " + numTaps + " SIDEOFGLASSES: " + sideOfGlasses);

        if (smartGlassesManager == null) return;
        if (numTaps == 2 || numTaps == 3) {
            if (smartGlassesManager.windowManager.isDashboardShowing()) {
                smartGlassesManager.windowManager.hideDashboard();
            } else {
                // BATTERY OPTIMIZATION: Directly call method instead of posting additional event
                if (contextualDashboardEnabled) {
                    try {
                        Log.d(TAG, "GOT A DOUBLE+ TAP");
                        displayGlassesDashboardEvent();
                    } catch (JSONException e) {
                        Log.e(TAG, "Error displaying dashboard", e);
                    }
                }
            }
        }
    }

    @Subscribe
    public void onThirdPartyAppErrorEvent(ThirdPartyEdgeAppErrorEvent event) {
        if (blePeripheral != null) {
            blePeripheral.sendNotifyManager(event.text, "error");
        }
        if (edgeAppSystem != null) {
            edgeAppSystem.stopThirdPartyAppByPackageName(event.packageName);
        }
        if (smartGlassesManager != null) {
            smartGlassesManager.windowManager.showAppLayer("system", () -> smartGlassesManager.sendReferenceCard("App error", event.text), 10);
        }
        sendStatusToAugmentOsManager();
    }

    //TODO NO MORE PASTA
    public ArrayList<String> notificationList = new ArrayList<String>();
    public JSONArray latestNewsArray = new JSONArray();
    private int latestNewsIndex = 0;
    
    // Photo request tracking for webhook URLs (for error responses)
    private Map<String, PhotoRequestInfo> photoRequestInfo = new HashMap<>();
    
    private static class PhotoRequestInfo {
        String requestId;
        String webhookUrl;
        String authToken;
        long timestamp;

        PhotoRequestInfo(String requestId, String webhookUrl, String authToken) {
            this.requestId = requestId;
            this.webhookUrl = webhookUrl != null ? webhookUrl : "";
            this.authToken = authToken != null ? authToken : "";
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Subscribe
    public void displayGlassesDashboardEvent() throws JSONException {
        if (!contextualDashboardEnabled) {
            return;
        }

        if (cachedDashboardDisplayObject != null) {
            if (smartGlassesManager != null) {
                Runnable dashboardDisplayRunnable = parseDisplayEventMessage(cachedDashboardDisplayObject);

                smartGlassesManager.windowManager.showDashboard(dashboardDisplayRunnable,
                        -1
                );
            }

            if (blePeripheral != null) {
                JSONObject newMsg = generateTemplatedJsonFromServer(cachedDashboardDisplayObject);
                blePeripheral.sendGlassesDisplayEventToManager(newMsg);
            }
            return;
        }

        // SHOW FALLBACK DASHBOARD

        // --- Build date/time line ---
        SimpleDateFormat currentTimeFormat = new SimpleDateFormat("h:mm", Locale.getDefault());
        SimpleDateFormat currentDateFormat = new SimpleDateFormat("MMM d", Locale.getDefault());
        String currentTime = currentTimeFormat.format(new Date());
        String currentDate = currentDateFormat.format(new Date());

        // Battery, date/time, etc.
        String leftHeaderLine = String.format(Locale.getDefault(), "◌ %s %s, %d%%\n", currentTime, currentDate, batteryLevel);

        String connString = webSocketStatus == null ? "Not connected" : webSocketStatus.name();

        if (smartGlassesManager != null) {
            smartGlassesManager.windowManager.showDashboard(() ->
                            smartGlassesManager.sendDoubleTextWall(leftHeaderLine, connString),
                    -1
            );
        }
    }

    @Subscribe
    public void onGlassBatteryLevelEvent(BatteryLevelEvent event) {
        if (batteryLevel != null && event.batteryLevel == batteryLevel) return;
        batteryLevel = event.batteryLevel;
        glassesCharging = event.isCharging;
        ServerComms.getInstance().sendGlassesBatteryUpdate(event.batteryLevel, event.isCharging, -1);
        sendStatusToAugmentOsManager();
    }

    @Subscribe
    public void onGlassCaseEvent(CaseEvent event) {
        // if (batteryLevel != null && event.batteryLevel == batteryLevel) return;
        // batteryLevel = event.batteryLevel;
        // ServerComms.getInstance().sendGlassesBatteryUpdate(event.batteryLevel, false, -1);
        caseBatteryLevel = event.caseBatteryLevel;
        caseCharging = event.caseCharging;
        caseOpen = event.caseOpen;
        caseRemoved = event.caseRemoved;

        Log.d("AugmentOsService", "Case event: " + event.caseBatteryLevel + " " + event.caseCharging + " " + event.caseOpen + " " + event.caseRemoved);

        sendStatusToAugmentOsManager();
    }

    // @Subscribe
    // public void onBrightnessLevelEvent(BrightnessLevelEvent event) {
    //     brightnessLevel = event.brightnessLevel;
    //     autoBrightness = event.autoBrightness;

    // if (brightnessLevel != -1) {
    //     PreferenceManager.getDefaultSharedPreferences(this)
    //         .edit()
    //         .putString(this.getResources().getString(R.string.SHARED_PREF_BRIGHTNESS), String.valueOf(brightnessLevel))
    //         .apply();
    //     PreferenceManager.getDefaultSharedPreferences(this)
    //         .edit()
    //         .putBoolean(this.getResources().getString(R.string.SHARED_PREF_AUTO_BRIGHTNESS), false)
    //         .apply();
    // } else {
    //     PreferenceManager.getDefaultSharedPreferences(this)
    //         .edit()
    //         .putBoolean(this.getResources().getString(R.string.SHARED_PREF_AUTO_BRIGHTNESS), autoBrightness)
    //         .apply();
    // }

    // sendStatusToAugmentOsManager();
    // sendStatusToBackend();
    // }

    @Subscribe
    public void onHeadUpAngleEvent(HeadUpAngleEvent event) {
        headUpAngle = event.headUpAngle;
        sendStatusToAugmentOsManager();
        sendStatusToBackend();
    }

    @Override
    public void onCreate() {
        super.onCreate();

//        EnvHelper.init(this);

        EventBus.getDefault().register(this);
        Log.d(TAG, "🔔 EventBus registration completed for AugmentosService");

        ServerComms.getInstance(this);

        authHandler = new AuthHandler(this);

        userId = authHandler.getUniqueIdForAnalytics();

        batteryStatusHelper = new BatteryStatusHelper(this);
        wifiStatusHelper = new WifiStatusHelper(this);
        gsmStatusHelper = new GsmStatusHelper(this);

        notificationSystem = new NotificationSystem(this, userId);
        calendarSystem = CalendarSystem.getInstance(this);

        // Initialize settings with default values
        brightnessLevel = 50;
        autoBrightness = false;
        headUpAngle = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this).getString(getResources().getString(R.string.HEAD_UP_ANGLE), "20"));
        dashboardHeight = 4;
        dashboardDepth = 5;

        // Request settings from server
        ServerComms.getInstance().requestSettingsFromServer();
        preferredMic = PreferenceManager.getDefaultSharedPreferences(this).getString(getResources().getString(R.string.PREFERRED_MIC), "glasses");

        contextualDashboardEnabled = true;
        metricSystemEnabled = false;

        alwaysOnStatusBarEnabled = false;

        edgeAppSystem = new EdgeAppSystem(this, null); // We'll set smartGlassesManager after it's created
        asrPlanner = new AsrPlanner(edgeAppSystem);

        // Initialize BLE Peripheral
        blePeripheral = new AugmentosBlePeripheral(this, this);

        // If this is the ASG client, start the peripheral
        if (getPackageName().equals(AugmentOSAsgClientPackageName)) {
            //    blePeripheral.start();
        }

        // Whitelist AugmentOS from battery optimization when system app
        // If not system app, bring up the settings menu
        if (isSystemApp(this)) {
            handleBatteryOptimization(this);
        }

        // Automatically connect to glasses on service start
        String preferredWearable = SmartGlassesManager.getPreferredWearable(this);
        if (!preferredWearable.isEmpty()) {
            SmartGlassesDevice preferredDevice = SmartGlassesManager.getSmartGlassesDeviceFromModelName(preferredWearable);
            if (preferredDevice != null) {
                // Initialize SmartGlassesManager
                startSmartGlassesManager();

                // Store the device to connect when SmartGlassesManager is ready
                final SmartGlassesDevice deviceToConnect = preferredDevice;

                // Add a listener that will be called when the service is connected
                executeOnceSmartGlassesManagerReady(() -> {
                    // Connect to glasses once the manager is available
                    if (smartGlassesManager != null) {
                        Log.d(TAG, "Connecting to preferred smart glasses: " + deviceToConnect.deviceModelName);
                        smartGlassesManager.connectToSmartGlasses(deviceToConnect);
                        sendStatusToAugmentOsManager();
                    } else {
                        Log.e(TAG, "SmartGlassesManager still null when ready listener called!");
                    }
                });
            } else {
                // We have some invalid device saved... delete from preferences
                SmartGlassesManager.savePreferredWearable(this, "");
            }
        }

        // cachedThirdPartyAppList is already initialized as a class member

        webSocketLifecycleManager = new WebSocketLifecycleManager(this, authHandler);

        // Set up backend comms
        //if(authHandler.getCoreToken() != null)
        //    ServerComms.getInstance().connectWebSocket(authHandler.getCoreToken());
        initializeServerCommsCallbacks();

        // Bind to the LocationSystem service
        bindLocationService();

        // Start periodic datetime sending
        datetimeRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
                    String isoDatetime = sdf.format(new java.util.Date());
                    ServerComms.getInstance().sendUserDatetimeToBackend(isoDatetime);
                } catch (Exception e) {
                    Log.e(TAG, "Exception while sending periodic datetime: " + e.getMessage());
                }
                // Schedule next run in 60 seconds
                datetimeHandler.postDelayed(this, 60 * 1000);
            }
        };
        datetimeHandler.postDelayed(datetimeRunnable, 60 * 1000); // Start after 60 seconds
    }

    private void bindLocationService() {
        Intent intent = new Intent(this, LocationSystem.class);
        bindService(intent, locationServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    myChannelId,
                    notificationAppName,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(notificationDescription);
            channel.enableLights(false);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // Flag to track if we should restart when killed
    private boolean shouldRestartOnKill = true;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent == null || intent.getAction() == null) {
            Log.e(TAG, "Received null intent or null action");
            // If we get null intent/action, maintain the sticky behavior for embedded systems
            return shouldRestartOnKill ? Service.START_STICKY : Service.START_NOT_STICKY;
        }

        String action = intent.getAction();

        switch (action) {
            case ACTION_START_CORE:
            case ACTION_START_FOREGROUND_SERVICE:
                // start the service in the foreground
                Log.d("TEST", "starting foreground");
                createNotificationChannel(); // New method to ensure one-time channel creation

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    /*
                        New in Android 15:
                         Android 15 limits 'dataSync' Foreground service type to 6 hours per day.
                         To get around this, we need to use 'connectedDevice' type (if we have BT permissions).
                         If we don't have BT perms, we'll upgrade this FGS from 'dataSync'=>'connectedDevice'
                         once we've connected a pair of glasses.
                    */
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                        currentForegroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC |
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                        startForeground(AUGMENTOS_NOTIFICATION_ID,
                                buildSharedForegroundNotification(this),
                                currentForegroundServiceType);
                    } else {
                        currentForegroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                        startForeground(AUGMENTOS_NOTIFICATION_ID,
                                buildSharedForegroundNotification(this),
                                currentForegroundServiceType);
                    }
                } else {
                    startForeground(AUGMENTOS_NOTIFICATION_ID, this.buildSharedForegroundNotification(this));
                }

                // Reset restart flag to true when service starts
                shouldRestartOnKill = true;

                // Send out the status once AugmentOS_Core is ready :)
                edgeAppSystem.startThirdPartyAppByPackageName(AugmentOSManagerPackageName);

                if (!NewPermissionUtils.areAllPermissionsGranted(this)) {
                    blePeripheral.sendPermissionsErrorToManager();
                }

                break;
            case ACTION_STOP_CORE:
            case ACTION_STOP_FOREGROUND_SERVICE:
                // Set flag to not restart - this is an explicit stop request
                shouldRestartOnKill = false;

                // Clean up resources before stopping
                Log.d(TAG, "Stopping service from ACTION_STOP");
                cleanupAllResources();
                stopForeground(true);
                stopSelf();
                break;
            default:
                Log.d(TAG, "Unknown action received in onStartCommand");
                Log.d(TAG, action);
        }

        // Return START_STICKY by default for embedded hardware,
        // but the shouldRestartOnKill flag will be checked in onTaskRemoved/onDestroy
        return shouldRestartOnKill ? Service.START_STICKY : Service.START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "onTaskRemoved called - app is being closed");

        // Check if glasses are connected
        boolean glassesConnected = false;
        if (smartGlassesManager != null) {
            SmartGlassesConnectionState connectionState = smartGlassesManager.getSmartGlassesConnectState();
            glassesConnected = (connectionState == SmartGlassesConnectionState.CONNECTED);
            Log.d(TAG, "Glasses connection state: " + connectionState + ", connected: " + glassesConnected);
        } else {
            Log.d(TAG, "SmartGlassesManager is null, assuming no glasses connected");
        }

        // Check if there are any active third-party apps running
        boolean hasActiveApps = false;
        if (edgeAppSystem != null) {
            // Check if any third-party apps are currently running
            ArrayList<ThirdPartyEdgeApp> thirdPartyApps = edgeAppSystem.getThirdPartyApps();
            for (ThirdPartyEdgeApp app : thirdPartyApps) {
                if (edgeAppSystem.checkIsThirdPartyAppRunningByPackageName(app.packageName)) {
                    hasActiveApps = true;
                    Log.d(TAG, "Found active third-party app: " + app.packageName);
                    break;
                }
            }
            Log.d(TAG, "Active third-party apps: " + hasActiveApps);
        }

        // If no glasses are connected and no active apps, stop the service
        if (!glassesConnected && !hasActiveApps) {
            Log.d(TAG, "No glasses connected and no active apps - stopping service");
            shouldRestartOnKill = false; // Prevent restart
            cleanupAllResources();
            stopForeground(true);
            stopSelf();
        } else {
            Log.d(TAG, "Keeping service running - glasses connected: " + glassesConnected + ", active apps: " + hasActiveApps);
        }
    }

    private Notification updateNotification() {
        Context context = getApplicationContext();

        PendingIntent action = PendingIntent.getActivity(context,
                0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE); // Flag indicating that if the described PendingIntent already exists, the current one should be canceled before generating a new one.

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder;

        String CHANNEL_ID = myChannelId;

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, notificationAppName,
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(notificationDescription);
        channel.enableVibration(false);
        channel.enableLights(false);
        manager.createNotificationChannel(channel);

        builder = new NotificationCompat.Builder(this, CHANNEL_ID);

        return builder.setContentIntent(action)
                .setContentTitle(notificationAppName)
                .setContentText(notificationDescription)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setTicker("...")
                .setContentIntent(action)
                .setOngoing(true).build();
    }

    // Replacement for buildSharedForegroundNotification that was previously imported from AugmentOSLib
    private Notification buildSharedForegroundNotification(Context context) {
        // Create a notification similar to updateNotification
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create the notification channel if it doesn't exist
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    myChannelId,
                    notificationAppName,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(notificationDescription);
            channel.enableLights(false);
            channel.enableVibration(false);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        // Create the intent for when notification is tapped - launch MentraOS Manager app
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(AugmentOSManagerPackageName);
        if (launchIntent == null) {
            // Fallback to MainActivity if MentraOS Manager is not installed
            launchIntent = new Intent(context, MainActivity.class);
        }
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );

        // Build the notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, myChannelId)
                .setContentTitle(notificationAppName)
                .setContentText(notificationDescription)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        return builder.build();
    }

    // Method to initialize the SmartGlassesManager by binding to the service
    public void startSmartGlassesManager() {
        if (!smartGlassesManagerBound && smartGlassesManager == null) {
            Log.d(TAG, "Binding to SmartGlassesManager service");

            // Start and bind to the SmartGlassesManager service
            Intent intent = new Intent(this, SmartGlassesManager.class);

            // Start the service as a foreground service for Android O+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            // Bind to the service
            bindService(intent, smartGlassesServiceConnection, Context.BIND_AUTO_CREATE);

            // The service connection callbacks will handle the rest
        } else if (smartGlassesManager != null) {
            Log.d(TAG, "SmartGlassesManager already initialized");
        }
    }

    // Method to clean up the SmartGlassesManager
    public void stopSmartGlassesManager() {
        if (smartGlassesManagerBound) {
            Log.d(TAG, "Unbinding from SmartGlassesManager service");

            // Unbind from the service
            unbindService(smartGlassesServiceConnection);
            smartGlassesManagerBound = false;

            // Stop the service
            Intent intent = new Intent(this, SmartGlassesManager.class);
            stopService(intent);

            // Clean up references
            if (smartGlassesManager != null) {
                smartGlassesManager = null;
            }

            // Update state
            if (edgeAppSystem != null) {
                edgeAppSystem.setSmartGlassesManager(null);
            }
            if (webSocketLifecycleManager != null) {
                webSocketLifecycleManager.updateSmartGlassesState(SmartGlassesConnectionState.DISCONNECTED);
            }
        }
    }

    @Subscribe
    public void onGlassesDisplayPowerEvent(GlassesDisplayPowerEvent event) {
        if (smartGlassesManager == null) return;
        if (event.turnedOn) {
            // BATTERY OPTIMIZATION: Using direct lambda instead of creating a new Runnable object
            smartGlassesManager.windowManager.showAppLayer(
                    "system",
                    () -> smartGlassesManager.sendReferenceCard("MentraOS Connected", "Screen back on"),
                    4
            );
        }
    }

    /**
     * Send a dedicated WiFi status change event to the AugmentOS manager
     *
     * @param isConnected Whether the glasses are connected to WiFi
     * @param ssid        The SSID of the connected network (if connected)
     */
    private void sendWifiStatusChangeEvent(boolean isConnected, String ssid, String localIp) {
        try {
            JSONObject wifiStatusEvent = new JSONObject();
            JSONObject wifiStatus = new JSONObject();
            wifiStatus.put("connected", isConnected);
            wifiStatus.put("ssid", ssid != null ? ssid : "");
            wifiStatus.put("local_ip", localIp != null ? localIp : "");
            wifiStatusEvent.put("glasses_wifi_status_change", wifiStatus);

            if (blePeripheral != null) {
                blePeripheral.sendDataToAugmentOsManager(wifiStatusEvent.toString());
                Log.d(TAG, "Sent WiFi status change event: connected=" + isConnected + ", ssid=" + ssid);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating WiFi status change event JSON", e);
        }
    }

    /**
     * Send a dedicated hotspot status change event to the AugmentOS manager
     * @param isEnabled Whether the glasses hotspot is enabled
     * @param ssid The SSID of the hotspot (if enabled)
     * @param password The password of the hotspot (if enabled)
     * @param localIp The local IP of the hotspot (if enabled)
     */
    private void sendHotspotStatusChangeEvent(boolean isEnabled, String ssid, String password, String localIp) {
        try {
            JSONObject hotspotStatusEvent = new JSONObject();
            JSONObject hotspotStatus = new JSONObject();
            hotspotStatus.put("enabled", isEnabled);
            hotspotStatus.put("ssid", ssid != null ? ssid : "");
            hotspotStatus.put("password", password != null ? password : "");
            hotspotStatus.put("local_ip", localIp != null ? localIp : "");
            hotspotStatusEvent.put("glasses_hotspot_status_change", hotspotStatus);

            if (blePeripheral != null) {
                blePeripheral.sendDataToAugmentOsManager(hotspotStatusEvent.toString());
                Log.d(TAG, "Sent hotspot status change event: enabled=" + isEnabled + ", ssid=" + ssid);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating hotspot status change event JSON", e);
        }
    }

    @Subscribe
    public void onGlassesNeedWifiCredentialsEvent(GlassesWifiStatusChange event) {
        glassesWifiConnected = event.isWifiConnected;
        glassesWifiSsid = event.currentSsid;
        glassesWifiLocalIp = event.localIpAddress;


        Log.d(TAG, "Received GlassesNeedWifiCredentialsEvent: device=" + event.deviceModel +
                ", wifiConnected=" + event.isWifiConnected +
                ", SSID=" + event.currentSsid);


        // Send the dedicated WiFi status change event
        sendWifiStatusChangeEvent(glassesWifiConnected, glassesWifiSsid, glassesWifiLocalIp);

        // Also update the general status
        sendStatusToAugmentOsManager();
    }

    @Subscribe
    public void onGlassesHotspotStatusChange(GlassesHotspotStatusChange event) {
        glassesHotspotEnabled = event.isHotspotEnabled;
        glassesHotspotSsid = event.hotspotSsid;
        glassesHotspotPassword = event.hotspotPassword;
        glassesHotspotGatewayIp = event.hotspotGatewayIp;

        Log.d(TAG, "Received GlassesHotspotStatusChange: device=" + event.deviceModel +
              ", enabled=" + event.isHotspotEnabled + ", ssid=" + event.hotspotSsid);

        // Send the dedicated hotspot status change event
        sendHotspotStatusChangeEvent(glassesHotspotEnabled, glassesHotspotSsid, glassesHotspotPassword, glassesHotspotGatewayIp);

        // Also update the general status
        sendStatusToAugmentOsManager();
    }

    @Subscribe
    public void onGlassesGalleryStatusEvent(GlassesGalleryStatusEvent event) {
        Log.d(TAG, "📸 Received gallery status from glasses: " + event.photoCount + " photos, " + 
              event.videoCount + " videos, total: " + event.totalCount);
        
        // Send gallery status to manager app via dedicated message
        try {
            JSONObject galleryStatusData = new JSONObject();
            galleryStatusData.put("photos", event.photoCount);
            galleryStatusData.put("videos", event.videoCount);
            galleryStatusData.put("total", event.totalCount);
            galleryStatusData.put("total_size", event.totalSize);
            galleryStatusData.put("has_content", event.hasContent);
            
            JSONObject wrapper = new JSONObject();
            wrapper.put("glasses_gallery_status", galleryStatusData);
            
            if (blePeripheral != null) {
                blePeripheral.sendDataToAugmentOsManager(wrapper.toString());
            }
        } catch (JSONException e) {
            Log.e(TAG, "📸 Error sending gallery status to manager", e);
        }
    }

    @Subscribe
    public void onGlassesWifiScanResultEvent(GlassesWifiScanResultEvent event) {
        Log.d(TAG, "Received WiFi scan results from glasses: " + event.networks.size() + " networks");

        // Send a dedicated message for WiFi scan results (not part of status)
        try {
            JSONObject wifiScanResultObj = new JSONObject();
            JSONArray networksArray = new JSONArray();

            for (String network : event.networks) {
                networksArray.put(network);
            }

            wifiScanResultObj.put("wifi_scan_results", networksArray);

            // Send to the manager app
            if (blePeripheral != null) {
                blePeripheral.sendDataToAugmentOsManager(wifiScanResultObj.toString());
                blePeripheral.sendNotifyManager("Found " + event.networks.size() + " WiFi networks", "success");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating WiFi scan results JSON", e);
        }

        // If glasses need WiFi credentials, trigger the credentials input UI in the Manager app
        // and show a message on the glasses
//        if (!event.isWifiConnected && smartGlassesManager != null &&
//            smartGlassesManager.getConnectedSmartGlasses() != null) {
//
//            // Send a specific notification to trigger the WiFi setup UI in the Manager app
//            if (blePeripheral != null) {
//                blePeripheral.sendWifiCredentialsRequestToManager(event.deviceModel);
//            }
//
//            // Show a message on the glasses to inform the user
//            smartGlassesManager.windowManager.showAppLayer(
//                "system",
//                () -> smartGlassesManager.sendReferenceCard("WiFi Required",
//                                                           "Please set up WiFi in the AugmentOS Manager app"),
//                10
//            );
//        }
    }

    @Subscribe
    public void onRtmpStreamStatusEvent(RtmpStreamStatusEvent event) {
        Log.d(TAG, "Received RTMP stream status event: " + event.statusMessage.toString());

        // Forward to ServerComms for cloud communication
        ServerComms.getInstance().sendRtmpStreamStatus(event.statusMessage);

        // Update local state and notify manager
        sendStatusToAugmentOsManager();
    }

    @Subscribe
    public void onKeepAliveAckEvent(KeepAliveAckEvent event) {
        Log.d(TAG, "Received keep-alive ACK event: " + event.ackMessage.toString());

        // Forward to ServerComms for cloud communication
        ServerComms.getInstance().sendKeepAliveAck(event.ackMessage);
    }

    private static final String[] ARROW_FRAMES = {
            // "↑", "↗", "–", "↘", "↓", "↙", "–", "↖"
            "↑", "↗", "↑", "↖"
    };

    // BATTERY OPTIMIZATION: Use a single Handler instance for the service
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable animationRunnable;

    private void playStartupSequenceOnSmartGlasses() {
        if (smartGlassesManager == null || smartGlassesManager.windowManager == null) return;

        // Cancel any existing animation to prevent multiple animations running
        if (animationRunnable != null) {
            uiHandler.removeCallbacks(animationRunnable);
        }

        int delay = 250; // Frame delay
        int totalFrames = ARROW_FRAMES.length;
        int totalCycles = 3;

        animationRunnable = new Runnable() {
            int frameIndex = 0;
            int cycles = 0;

            @Override
            public void run() {
                // Check for null each time before updating the UI
                if (smartGlassesManager == null || smartGlassesManager.windowManager == null) {
                    return;
                }

                if (cycles >= totalCycles) {
                    // End animation with final message
                    smartGlassesManager.windowManager.showAppLayer(
                            "system",
                            () -> smartGlassesManager.sendTextWall("                  /// MentraOS Connected \\\\\\"),
                            6
                    );

//                    if (alwaysOnStatusBarEnabled) {
//                        // BATTERY OPTIMIZATION: Use the existing handler instead of creating a new one
//                        uiHandler.postDelayed(() ->
//                                smartGlassesManager.windowManager.showAppLayer(
//                                    "serverappid",
//                                    () -> smartGlassesManager.sendTextWall(cachedDashboardTopLine),
//                                    0
//                            ), 3000); // Delay of 3 seconds
//                    }

                    // Set isInitializing to false after booting sequence is finished, with 100ms delay
                    uiHandler.postDelayed(() -> isInitializing = false, 500);
                    return; // Stop looping
                }

                // Send current frame
                String currentAnimationTextFrame = "                    " + ARROW_FRAMES[frameIndex] + " MentraOS Booting " + ARROW_FRAMES[frameIndex];
                smartGlassesManager.windowManager.showAppLayer(
                        "system",
                        () -> {
                            smartGlassesManager.sendTextWall(currentAnimationTextFrame);
                        },
                        6
                );
                // Send the same text wall to AugmentOS Manager in JSONObject format
                JSONObject displayJson = new JSONObject();
                try {
                    JSONObject layoutJson = new JSONObject();
                    layoutJson.put("layoutType", "text_wall");
                    layoutJson.put("text", currentAnimationTextFrame);
                    displayJson.put("layout", layoutJson);
                    //blePeripheral.sendGlassesDisplayEventToManager(displayJson);
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating display JSON", e);
                }

                // Move to next frame
                frameIndex = (frameIndex + 1) % totalFrames;

                // Count full cycles
                if (frameIndex == 0) cycles++;

                // Schedule next frame
                uiHandler.postDelayed(this, delay);
            }
        };

        // Start animation with the reused handler
        uiHandler.postDelayed(animationRunnable, 350);
    }

    @Subscribe
    public void onSmartRingButtonEvent(SmartRingButtonOutputEvent event) {
        int buttonId = event.buttonId;
        long time = event.timestamp;
        boolean isDown = event.isDown;

        if (!isDown || buttonId != 1) return;
        Log.d(TAG, "DETECTED BUTTON PRESS W BUTTON ID: " + buttonId);
        currTime = System.currentTimeMillis();

        ServerComms.getInstance().sendButtonPress("ring", "single");

        //Detect double presses
        if (isDown && currTime - lastPressed < doublePressTimeConst) {
            Log.d(TAG, "Double tap - CurrTime-lastPressed: " + (currTime - lastPressed));
            ServerComms.getInstance().sendButtonPress("ring", "double");
        }

        if (isDown) {
            lastPressed = System.currentTimeMillis();
        }
    }

    @Subscribe
    public void onButtonPressEvent(ButtonPressEvent event) {
        Log.d(TAG, "Received button press event from glasses - buttonId: " + event.buttonId +
                ", pressType: " + event.pressType + ", device: " + event.deviceModel);

        // Forward button press to cloud via ServerComms
        ServerComms.getInstance().sendButtonPress(event.buttonId, event.pressType);
    }

    private JSONObject generateTemplatedJsonFromServer(JSONObject rawMsg) {
        // Process all placeholders in the entire JSON structure in a single pass
        SimpleDateFormat sdf = new SimpleDateFormat("M/dd, h:mm");
        String formattedDate = sdf.format(new Date());

        // 12-hour time format (with leading zeros for hours)
        SimpleDateFormat time12Format = new SimpleDateFormat("hh:mm");
        String time12 = time12Format.format(new Date());

        // 24-hour time format
        SimpleDateFormat time24Format = new SimpleDateFormat("HH:mm");
        String time24 = time24Format.format(new Date());

        // Current date with format MM/dd
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd");
        String currentDate = dateFormat.format(new Date());

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("$no_datetime$", formattedDate);
        placeholders.put("$DATE$", currentDate);
        placeholders.put("$TIME12$", time12);
        placeholders.put("$TIME24$", time24);
        placeholders.put("$GBATT$", (batteryLevel == null ? "" : batteryLevel + "%"));

        try {
            JSONObject msg = processJSONPlaceholders(rawMsg, placeholders);
            return msg;
        } catch (JSONException e) {
            //throw new RuntimeException(e);
            Log.d(TAG, "Error processing JSON placeholders: " + e.getMessage());
            return rawMsg;
        }
    }

    private void parseAugmentosResults(JSONObject jsonResponse) throws JSONException {
        JSONArray notificationArray = jsonResponse.getJSONArray(notificationFilterKey);
        JSONArray newsSummaryArray = jsonResponse.getJSONArray(newsSummaryKey);

        if (notificationArray.length() > 0) {
            JSONArray notifications = notificationArray.getJSONObject(0).getJSONArray("notification_data");
            Log.d(TAG, "Got notifications: " + notifications);

            List<JSONObject> sortedNotifications = new ArrayList<>();
            for (int i = 0; i < notifications.length(); i++) {
                sortedNotifications.add(notifications.getJSONObject(i));
            }

            Collections.sort(sortedNotifications, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    try {
                        return Integer.compare(a.getInt("rank"), b.getInt("rank"));
                    } catch (JSONException e) {
                        // If a rank is missing or unparsable, treat as equal
                        return 0;
                    }
                }
            });

            notificationList.clear();
//        Log.d(TAG, "Got notifications: " + sortedNotifications.toString());

            for (int i = 0; i < sortedNotifications.size(); i++) {
                JSONObject notification = sortedNotifications.get(i);
                String summary = notification.getString("summary");
                notificationList.add(summary);
            }
        }

        if (newsSummaryArray.length() > 0) {
            JSONObject newsSummary = newsSummaryArray.getJSONObject(0);
            latestNewsArray = newsSummary.getJSONObject("news_data").getJSONArray("news_summaries");
            Log.d(TAG, "Latest news: " + latestNewsArray);
        }
    }

    public Runnable parseDisplayEventMessage(JSONObject rawMsg) {
        if (isInitializing) {
            return () -> {
            };
        }

        try {
            JSONObject msg = generateTemplatedJsonFromServer(rawMsg);

//                Log.d(TAG, "Parsed message: " + msg.toString());

            JSONObject layout = msg.getJSONObject("layout");
            String layoutType = layout.getString("layoutType");
            String title;
            String text;
            switch (layoutType) {
                case "empty":
                    return () -> smartGlassesManager.sendTextWall(cachedDashboardTopLine);
                case "reference_card":
//                        if (alwaysOnStatusBarEnabled && cachedDashboardTopLine != null
//                                && !layout.getString("title").contains("AugmentOS")) {
//                            title = layout.getString("title") + " | " + cachedDashboardTopLine;
//                        } else {
                    title = layout.getString("title");
//                        }
                    text = layout.getString("text");
                    return () -> smartGlassesManager.sendReferenceCard(title, text);
                case "text_wall":
                case "text_line": // This assumes that the dashboard doesn't use textwall layout
                    text = layout.getString("text");
//                        if (alwaysOnStatusBarEnabled && cachedDashboardTopLine != null) {
//                            String finalText = cachedDashboardTopLine + "\n" + text;
//                            return () -> smartGlassesManager.sendTextWall(finalText);
//                        } else {
                    return () -> smartGlassesManager.sendTextWall(text);
//                        }
                case "double_text_wall":
                    String topText = layout.getString("topText");
                    String bottomText = layout.getString("bottomText");
                    return () -> smartGlassesManager.sendDoubleTextWall(topText, bottomText);
                case "text_rows":
                    JSONArray rowsArray = layout.getJSONArray("text");
                    String[] stringsArray = new String[rowsArray.length()];
                    for (int k = 0; k < rowsArray.length(); k++)
                        stringsArray[k] = rowsArray.getString(k);
                    return () -> smartGlassesManager.sendRowsCard(stringsArray);
                case "bitmap_view":
                    String base64Data = layout.getString("data");
                    Log.d(TAG, "Received bitmap data: " + base64Data.length());
                    byte[] decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                    Bitmap bmp = BitmapJavaUtils.bytesToBitmap(decodedBytes);
                    return () -> smartGlassesManager.sendBitmap(bmp);
                default:
                    Log.d(TAG, "ISSUE PARSING LAYOUT");
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return () -> {};
    }

    /**
     * Parses the top line of a dashboard display.
     * This function extracts and processes information specifically from the top line
     * of the dashboard display, which typically contains time, date, battery status, etc.
     *
     * @param msg The JSON object containing the dashboard display data
     * @return The parsed top line string, or null if there was an error in parsing
     */
    public String parseDashboardTopLine(JSONObject msg) {
        try {
            // First check if this is a proper dashboard display with layout
            if (msg == null || !msg.has("layout")) {
                return generateFallbackDashboardTopLine();
            }

            JSONObject layout = msg.getJSONObject("layout");
            String layoutType = layout.getString("layoutType");

            // Most dashboards use double_text_wall layout
            if ("double_text_wall".equals(layoutType) && layout.has("topText")) {
                String topText = layout.getString("topText");
                if (topText.contains("\n")) {
                    topText = topText.split("\n")[0];
                }

                if (topText.contains("$GBATT$")) {
                    topText = topText.replace("$GBATT$", batteryLevel != null ? String.valueOf(batteryLevel) : "");
                }

                // Process special tokens in the top line if needed
                if (topText.contains("$no_datetime$")) {
                    SimpleDateFormat sdf = new SimpleDateFormat("M/dd, h:mm", Locale.getDefault());
                    String formatted = sdf.format(new Date());
                    topText = topText.replace("$no_datetime$", formatted);
                }

                return topText;
            } else if ("text_rows".equals(layoutType) && layout.has("text")) {
                // For text_rows layout, the first row is typically the header
                JSONArray rowsArray = layout.getJSONArray("text");
                if (rowsArray.length() > 0) {
                    return rowsArray.getString(0);
                }
            }

            // If we can't parse the dashboard format or it's not what we expect,
            // generate a fallback header line
            return generateFallbackDashboardTopLine();

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing dashboard top line", e);
            return generateFallbackDashboardTopLine();
        }
    }

    /**
     * Generates a fallback dashboard top line when the normal parsing fails.
     * This ensures that even if there are issues with the dashboard data,
     * we still display useful information to the user.
     *
     * @return A formatted string with time, date, and battery information
     */
    private String generateFallbackDashboardTopLine() {
        SimpleDateFormat currentTimeFormat = new SimpleDateFormat("h:mm", Locale.getDefault());
        SimpleDateFormat currentDateFormat = new SimpleDateFormat("MMM d", Locale.getDefault());
        String currentTime = currentTimeFormat.format(new Date());
        String currentDate = currentDateFormat.format(new Date());

        // Use a safe default if battery level is null
        int batteryPercentage = (batteryLevel != null) ? batteryLevel : 0;

        // Format: "◌ h:mm MMM d, XX%"
        return String.format(Locale.getDefault(), "◌ %s %s, %d%%",
                currentTime, currentDate, batteryPercentage);
    }

    /**
     * Extracts specific information from a dashboard top line.
     * This function can identify and extract elements like time, battery level,
     * or other structured data from the dashboard top line.
     *
     * @param topLine The dashboard top line string to analyze
     * @return A JSONObject containing the extracted information
     */
    public JSONObject extractDashboardTopLineInfo(String topLine) {
        JSONObject result = new JSONObject();

        try {
            // Check for null or empty input
            if (topLine == null || topLine.trim().isEmpty()) {
                return result;
            }

            // Extract time pattern (like "h:mm" or "hh:mm")
            Pattern timePattern = Pattern.compile("\\d{1,2}:\\d{2}");
            Matcher timeMatcher = timePattern.matcher(topLine);
            if (timeMatcher.find()) {
                result.put("time", timeMatcher.group());
            }

            // Extract date pattern (like "MMM d" or "Month day")
            Pattern datePattern = Pattern.compile("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{1,2}");
            Matcher dateMatcher = datePattern.matcher(topLine);
            if (dateMatcher.find()) {
                result.put("date", dateMatcher.group());
            }

            // Extract battery percentage (like "85%" or "100%")
            Pattern batteryPattern = Pattern.compile("(\\d{1,3})%");
            Matcher batteryMatcher = batteryPattern.matcher(topLine);
            if (batteryMatcher.find()) {
                result.put("battery", Integer.parseInt(batteryMatcher.group(1)));
            }

            // Detect if this is a status line (contains specific indicators)
            boolean isStatusLine = topLine.contains("◌") ||
                    (result.has("time") && result.has("battery"));
            result.put("isStatusLine", isStatusLine);

        } catch (JSONException e) {
            Log.e(TAG, "Error creating dashboard top line info JSON", e);
        }

        return result;
    }

    @Subscribe
    public void onGlassesBluetoothSearchDiscoverEvent(GlassesBluetoothSearchDiscoverEvent event) {
        blePeripheral.sendGlassesBluetoothDiscoverResultToManager(event.modelName, event.deviceName, event.deviceAddress);
    }

    @Subscribe
    public void onGlassesBluetoothSearchStopEvent(GlassesBluetoothSearchStopEvent event) {
        blePeripheral.sendGlassesBluetoothStopToManager(event.modelName);
    }

    @Subscribe
    public void onNewScreenImageEvent(NewScreenImageEvent event) {
        if (smartGlassesManager != null)
            smartGlassesManager.windowManager.showAppLayer("server", () -> smartGlassesManager.sendBitmap(event.bmp), -1);
    }

    @Subscribe
    public void onBleCommandReceiver(BleCommandReceiver event) {
        blePeripheral.sendBleCommandReceiverEventToManager(event);
    }

    @Subscribe
    public void onBleCommandSender(BleCommandSender event) {
        blePeripheral.sendBleCommandSenderEventToManager(event);
    }

    private void startNotificationService() {
        Intent notificationServiceIntent = new Intent(this, MyNotificationListeners.class);
        startService(notificationServiceIntent);

        NotificationListenerService.requestRebind(
                new ComponentName(this, MyNotificationListeners.class));
    }

    private void stopNotificationService() {
        Intent notificationServiceIntent = new Intent(this, MyNotificationListeners.class);
        stopService(notificationServiceIntent);
    }

    public boolean getIsSearchingForGlasses() {
        return smartGlassesManager != null
                && smartGlassesManager.getSmartGlassesConnectState() != SmartGlassesConnectionState.DISCONNECTED
                && smartGlassesManager.getSmartGlassesConnectState() != SmartGlassesConnectionState.CONNECTED;
    }

    /**
     * Executes an action once the SmartGlassesManager is ready.
     * If the manager is already initialized, the action is executed immediately.
     * Otherwise, it's queued to be executed when the manager is bound.
     */
    private void executeOnceSmartGlassesManagerReady(Runnable action) {
        if (smartGlassesManager != null) {
            // If the manager is already initialized, execute the action immediately
            action.run();
            return;
        }

        // Add the action to the queue
        smartGlassesReadyListeners.add(action);

        // Ensure the manager is started
        startSmartGlassesManager();
    }

    /**
     * Overloaded version that takes a context parameter (for backward compatibility)
     */
    private void executeOnceSmartGlassesManagerReady(Context context, Runnable action) {
        executeOnceSmartGlassesManagerReady(action);
    }

    public JSONObject generateStatusJson() {
        try {
            // Creating the main status object
            JSONObject status = new JSONObject();

            // Adding puck battery life and charging status
            JSONObject coreInfo = new JSONObject();
            coreInfo.put("augmentos_core_version", getCoreVersion(this));
            coreInfo.put("core_token", authHandler.getCoreToken());
            coreInfo.put("cloud_connection_status", webSocketStatus.name());
            coreInfo.put("puck_battery_life", batteryStatusHelper.getBatteryLevel());
            coreInfo.put("charging_status", batteryStatusHelper.isBatteryCharging());
            coreInfo.put("sensing_enabled", SmartGlassesManager.getSensingEnabled(this));
            coreInfo.put("bypass_vad_for_debugging", SmartGlassesManager.getBypassVadForDebugging(this));
            coreInfo.put("enforce_local_transcription", SmartGlassesManager.getEnforceLocalTranscription(this));
            coreInfo.put("bypass_audio_encoding_for_debugging", SmartGlassesManager.getBypassAudioEncodingForDebugging(this));
            coreInfo.put("contextual_dashboard_enabled", this.contextualDashboardEnabled);
            coreInfo.put("always_on_status_bar_enabled", this.alwaysOnStatusBarEnabled);
            coreInfo.put("force_core_onboard_mic", "phone".equals(SmartGlassesManager.getPreferredMic(this))); // Deprecated - use preferred_mic instead
            coreInfo.put("preferred_mic", preferredMic);
            coreInfo.put("default_wearable", SmartGlassesManager.getPreferredWearable(this));
            coreInfo.put("is_mic_enabled_for_frontend", isMicEnabledForFrontend);
            coreInfo.put("metric_system_enabled", this.metricSystemEnabled);
            coreInfo.put("power_saving_mode", SmartGlassesManager.getPowerSavingMode(this));
            coreInfo.put("is_searching", getIsSearchingForGlasses());
            coreInfo.put("protobuf_schema_version", this.protobufSchemaVersion);
            coreInfo.put("glasses_protobuf_version", this.glassesProtobufVersion);
            status.put("core_info", coreInfo);
            //Log.d(TAG, "PREFER - Got default wearable: " + SmartGlassesManager.getPreferredWearable(this));

            // Adding connected glasses object
            JSONObject connectedGlasses = new JSONObject();

            // Add glasses serial number info
            if (glassesSerialNumber != null) {
                connectedGlasses.put("glasses_serial_number", glassesSerialNumber);
                connectedGlasses.put("glasses_style", glassesStyle);
                connectedGlasses.put("glasses_color", glassesColor);
            }

            if (smartGlassesManager != null && smartGlassesManager.getConnectedSmartGlasses() != null) {
                connectedGlasses.put("model_name", smartGlassesManager.getConnectedSmartGlasses().deviceModelName);
                connectedGlasses.put("battery_level", (batteryLevel == null) ? -1 : batteryLevel); //-1 if unknown
                connectedGlasses.put("is_charging", (glassesCharging == null) ? false : glassesCharging);
                connectedGlasses.put("case_battery_level", (caseBatteryLevel == null) ? -1 : caseBatteryLevel); //-1 if unknown
                connectedGlasses.put("case_charging", (caseCharging == null) ? false : caseCharging);
                connectedGlasses.put("case_open", (caseOpen == null) ? false : caseOpen);
                connectedGlasses.put("case_removed", (caseRemoved == null) ? true : caseRemoved);

                // Add Bluetooth device name if available
                String bluetoothName = smartGlassesManager.getConnectedSmartGlassesBluetoothName();
                if (bluetoothName != null) {
                    connectedGlasses.put("bluetooth_name", bluetoothName);
                }

                // Add WiFi status information for glasses that need WiFi
                String deviceModel = smartGlassesManager.getConnectedSmartGlasses().deviceModelName;

                // Check if these are glasses that support WiFi
                boolean usesWifi = deviceModel != null && (deviceModel.contains("Mentra Live") || deviceModel.contains("Android Smart Glasses"));

                // Add the general WiFi support flag for all models
                connectedGlasses.put("glasses_use_wifi", usesWifi);

                // Add detailed WiFi status, but only for models that support it
                if (usesWifi) {
                    connectedGlasses.put("glasses_wifi_connected", glassesWifiConnected);
                    connectedGlasses.put("glasses_wifi_ssid", glassesWifiSsid);
                    connectedGlasses.put("glasses_wifi_local_ip", glassesWifiLocalIp);
                    
                    // Add hotspot status information
                    connectedGlasses.put("glasses_hotspot_enabled", glassesHotspotEnabled);
                    if (glassesHotspotEnabled) {
                        connectedGlasses.put("glasses_hotspot_ssid", glassesHotspotSsid);
                        connectedGlasses.put("glasses_hotspot_password", glassesHotspotPassword);
                        connectedGlasses.put("glasses_hotspot_gateway_ip", glassesHotspotGatewayIp);
                    }
                }

                // Add ASG client version information for Mentra Live glasses
                if (deviceModel != null && deviceModel.contains("Mentra Live")) {
                    // Add glasses version info
                    connectedGlasses.put("glasses_app_version", glassesAppVersion != null ? glassesAppVersion : "");
                    connectedGlasses.put("glasses_build_number", glassesBuildNumber != null ? glassesBuildNumber : "");
                    connectedGlasses.put("glasses_device_model", glassesDeviceModel != null ? glassesDeviceModel : "");
                    connectedGlasses.put("glasses_android_version", glassesAndroidVersion != null ? glassesAndroidVersion : "");
                    connectedGlasses.put("glasses_ota_version_url", glassesOtaVersionUrl != null ? glassesOtaVersionUrl : "");
                }

                // Add serial number information for Even Realities G1 glasses
                if (deviceModel != null && deviceModel.contains("Even Realities G1")) {
                    // Serial number info is already added above for all glasses, but we can add additional G1-specific info here if needed
                }
            }
            status.put("connected_glasses", connectedGlasses);

            // Adding glasses settings
            JSONObject glassesSettings = new JSONObject();
            glassesSettings.put("auto_brightness", autoBrightness);
            glassesSettings.put("head_up_angle", headUpAngle);
            glassesSettings.put("dashboard_height", 4);// TODO: get from settings
            glassesSettings.put("dashboard_depth", 5);// TODO: get from settings
            if (brightnessLevel == null) {
                brightnessLevel = 50;
            }
            glassesSettings.put("brightness", brightnessLevel);
            if (headUpAngle == null) {
                headUpAngle = 20;
            }
            glassesSettings.put("head_up_angle", headUpAngle);
            glassesSettings.put("button_mode", SmartGlassesManager.getButtonPressMode(this));
            glassesSettings.put("button_photo_size", SmartGlassesManager.getButtonPhotoSize(this));
            glassesSettings.put("button_camera_led", SmartGlassesManager.getButtonCameraLed(this));
            
            // Add button video settings as an object
            JSONObject buttonVideoSettings = new JSONObject();
            buttonVideoSettings.put("width", SmartGlassesManager.getButtonVideoWidth(this));
            buttonVideoSettings.put("height", SmartGlassesManager.getButtonVideoHeight(this));
            buttonVideoSettings.put("fps", SmartGlassesManager.getButtonVideoFps(this));
            glassesSettings.put("button_video_settings", buttonVideoSettings);
            
            status.put("glasses_settings", glassesSettings);

            // Adding OTA progress information
            JSONObject otaProgress = new JSONObject();

            // Download progress
            if (downloadStatus != null) {
                JSONObject downloadInfo = new JSONObject();
                downloadInfo.put("status", downloadStatus.name());
                downloadInfo.put("progress", downloadProgress);
                downloadInfo.put("bytes_downloaded", downloadBytesDownloaded);
                downloadInfo.put("total_bytes", downloadTotalBytes);
                if (downloadErrorMessage != null) {
                    downloadInfo.put("error_message", downloadErrorMessage);
                }
                downloadInfo.put("timestamp", downloadTimestamp);
                otaProgress.put("download", downloadInfo);
            }

            // Installation progress
            if (installationStatus != null) {
                JSONObject installationInfo = new JSONObject();
                installationInfo.put("status", installationStatus.name());
                if (installationApkPath != null) {
                    installationInfo.put("apk_path", installationApkPath);
                }
                if (installationErrorMessage != null) {
                    installationInfo.put("error_message", installationErrorMessage);
                }
                installationInfo.put("timestamp", installationTimestamp);
                otaProgress.put("installation", installationInfo);
            }

            status.put("ota_progress", otaProgress);

            // Adding wifi status (excluding signal_strength to prevent unnecessary updates)
            JSONObject wifi = new JSONObject();
            wifi.put("is_connected", wifiStatusHelper.isWifiConnected());
            wifi.put("ssid", wifiStatusHelper.getSSID());
            status.put("wifi", wifi);

            // Adding gsm status (excluding signal_strength to prevent unnecessary updates)
            JSONObject gsm = new JSONObject();
            gsm.put("is_connected", gsmStatusHelper.isConnected());
            gsm.put("carrier", gsmStatusHelper.getNetworkType());
            status.put("gsm", gsm);

            // Adding apps array
            JSONArray apps = new JSONArray();

            // Check if cachedThirdPartyAppList is not null before iterating
            if (cachedThirdPartyAppList != null) {
                for (ThirdPartyCloudApp app : cachedThirdPartyAppList) {
                    JSONObject appObj = app.toJson(false);
                    apps.put(appObj);
                }
            }

            // Adding apps array to the status object
            status.put("apps", apps);

            // Add auth to status object
            status.put("auth", authHandler.toJson());

            // Wrapping the status object inside a main object (as shown in your example)
            JSONObject mainObject = new JSONObject();
            mainObject.put("status", status);

            Log.d(TAG, "Sending status to backend: " + mainObject.toString());

            return mainObject;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public void initializeServerCommsCallbacks() {
        ServerComms.getInstance().setServerCommsCallback(new ServerCommsCallback() {
            @Override
            public void onConnectionAck() {
                // Send current datetime to backend after server ack
                try {
                    // Format current datetime as ISO 8601 string (yyyy-MM-dd'T'HH:mm:ssZ)
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
                    String isoDatetime = sdf.format(new java.util.Date());
                    ServerComms.getInstance().sendUserDatetimeToBackend(isoDatetime);
                } catch (Exception e) {
                    Log.e(TAG, "Exception while sending datetime to backend: " + e.getMessage());
                }
                sendStatusToBackend();
            }

            @Override
            public void onAppStateChange(List<ThirdPartyCloudApp> appList) {
                cachedThirdPartyAppList = appList;
                sendStatusToAugmentOsManager();
                sendStatusToBackend();
            }

            @Override
            public void onDisplayEvent(JSONObject displayData) {
                cachedDisplayData = displayData;
//                Log.d(TAG,"Received display data: " + displayData.toString());
                Runnable newRunnable = parseDisplayEventMessage(displayData);
//                Log.d(TAG, displayData.toString());
//                Log.d(TAG, "Parsed display event message: " + displayData.has("durationMs"));
                int durationMs = displayData.optInt("durationMs", -1);
//                Log.d(TAG, "Received display event with duration: " + durationMs);
//                Log.d("AugmentosService", "Received display event: " + displayData.toString());
                if (smartGlassesManager != null) {
                    smartGlassesManager.windowManager.showAppLayer("serverappid", newRunnable, durationMs / 1000); // TODO: either only use seconds or milliseconds
                }
                if (blePeripheral != null) {
                    JSONObject newMsg = generateTemplatedJsonFromServer(displayData);
                    blePeripheral.sendGlassesDisplayEventToManager(newMsg);  //THIS LINE RIGHT HERE ENDS UP TRIGGERING IT
                }
            }

            @Override
            public void onDashboardDisplayEvent(JSONObject dashboardDisplayData) {
                cachedDashboardDisplayObject = dashboardDisplayData;
                // Parse the top line for logging/debugging
                cachedDashboardTopLine = parseDashboardTopLine(dashboardDisplayData);

//                if (alwaysOnStatusBarEnabled) {
//                    onDisplayEvent(cachedDisplayData);
//                    Log.d("AugmentosService", "Dashboard display event received: " + dashboardDisplayData.toString());
//                }

                // Create the runnable as before
                cachedDashboardDisplayRunnable = parseDisplayEventMessage(dashboardDisplayData);
            }

            @Override
            public void onConnectionError(String errorMsg) {
                if (blePeripheral != null) {
                    blePeripheral.sendNotifyManager("Connection error: " + errorMsg, "error");
                }
            }

            @Override
            public void onAuthError() {
                // TODO: do a thing
                // TODO: is this the way we want to do it? should just be in status maybe???
                // blePeripheral.sendAuthErrorToManager();
                authHandler.deleteAuthSecretKey();
                sendStatusToAugmentOsManager();
            }

            @Override
            public void onMicrophoneStateChange(List<SpeechRequiredDataType> requiredData, boolean bypassVad) {
                if (smartGlassesManager != null && SmartGlassesManager.getSensingEnabled(getApplicationContext())) {
                    smartGlassesManager.changeMicrophoneState(requiredData, bypassVad);
                }
            }

            @Override
            public void onConnectionStatusChange(WebSocketManager.IncomingMessageHandler.WebSocketStatus status) {
                webSocketStatus = status;
                sendStatusToAugmentOsManager();
                if (status == WebSocketManager.IncomingMessageHandler.WebSocketStatus.CONNECTED) {
                    if (smartGlassesManager != null) {
                        smartGlassesManager.sendHomeScreen();
                    }
                }
                sendStatusToBackend();
            }

            @Override
            public void onRequestSingle(String dataType) {
                switch (dataType) {
                    case "core_status_update":
                        Log.d(TAG, "Server wants a core_status");
                        sendStatusToBackend();
                        break;
                    case "photo":
                        Log.d(TAG, "Server wants a photo");
                    default:
                        Log.d(TAG, "Unknown onRequestSingle dataType: " + dataType);
                        break;
                }
            }

            @Override
            public void onPhotoRequest(String requestId, String appId, String webhookUrl, String authToken, String size) {
                Log.d(TAG, "Photo request received: requestId=" + requestId + ", appId=" + appId + ", webhookUrl=" + webhookUrl + ", authToken=" + (authToken.isEmpty() ? "none" : "***") + ", size=" + size);

                // Track photo request info for potential error responses
                if (webhookUrl != null && !webhookUrl.isEmpty()) {
                    photoRequestInfo.put(requestId, new PhotoRequestInfo(requestId, webhookUrl, authToken));
                    
                    // Set up cleanup timeout (5 minutes)
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        photoRequestInfo.remove(requestId);
                    }, 300000); // 5 minutes
                }

                // Forward the request to the smart glasses manager
                if (smartGlassesManager != null) {
                    boolean requestSent = smartGlassesManager.requestPhoto(requestId, appId, webhookUrl, authToken, size);
                    if (!requestSent) {
                        Log.e(TAG, "Failed to send photo request to glasses");
                        // Error response will be sent by SmartGlassesManager
                    }
                } else {
                    Log.e(TAG, "Cannot process photo request: smartGlassesManager is null");
                    
                    // Send error response for service unavailable via webhook if available
                    sendPhotoErrorResponse(requestId, "PHONE_GLASSES_NOT_CONNECTED", 
                        "SmartGlassesManager service not available");
                }
            }

            @Override
            public void onRtmpStreamStartRequest(JSONObject message) {
                String rtmpUrl = message.optString("rtmpUrl", "");
                Log.d(TAG, "RTMP stream request received: rtmpUrl=" + rtmpUrl);

                // Forward the request to the smart glasses manager
                if (smartGlassesManager != null) {
                    boolean requestSent = smartGlassesManager.requestRtmpStream(message);
                    if (!requestSent) {
                        Log.e(TAG, "Failed to send RTMP stream request to glasses");
                    }
                } else {
                    Log.e(TAG, "Cannot process RTMP stream request: smartGlassesManager is null");
                }
            }

            @Override
            public void onRtmpStreamStop() {
                Log.d(TAG, "RTMP stream stop request received");

                // Forward the request to the smart glasses manager
                if (smartGlassesManager != null) {
                    boolean requestSent = smartGlassesManager.stopRtmpStream();
                    if (!requestSent) {
                        Log.e(TAG, "Failed to send RTMP stream stop request to glasses");
                    }
                } else {
                    Log.e(TAG, "Cannot process RTMP stream stop request: smartGlassesManager is null");
                }
            }

            @Override
            public void onRtmpStreamKeepAlive(JSONObject message) {
                Log.d(TAG, "RTMP stream keep alive received");

                // Forward the keep alive to the smart glasses manager
                if (smartGlassesManager != null) {
                    boolean messageSent = smartGlassesManager.sendRtmpStreamKeepAlive(message);
                    if (!messageSent) {
                        Log.e(TAG, "Failed to send RTMP keep alive to glasses");
                    }
                } else {
                    Log.e(TAG, "Cannot process RTMP keep alive: smartGlassesManager is null");
                }
            }

            @Override
            public void onAppStarted(String packageName) {
                sendStatusToBackend();
                AugmentosService.this.onAppStarted(packageName);
            }

            @Override
            public void onAppStopped(String packageName) {
                AugmentosService.this.onAppStopped(packageName);
            }

            @Override
            public void onSettingsUpdate(JSONObject settings) {
                Log.d("AugmentOsService", "!!!! Settings update received: " + settings.toString() + ".");
                try {
                    if (settings.has("brightness")) {
                        brightnessLevel = settings.getInt("brightness");
                    }
                    if (settings.has("autoBrightness")) {
                        autoBrightness = settings.getBoolean("autoBrightness");
                        Log.d(TAG, "Updating glasses auto brightness: " + autoBrightness);
                    }
                    if (autoBrightness) {
                        smartGlassesManager.updateGlassesAutoBrightness(true);
                    } else {
                        Log.d(TAG, "Updating glasses brightness: " + brightnessLevel);
                        smartGlassesManager.updateGlassesBrightness(brightnessLevel);
                    }

                    if (settings.has("headUpAngle")) {
                        headUpAngle = settings.getInt("headUpAngle");
                        smartGlassesManager.updateGlassesHeadUpAngle(headUpAngle);
                    }

                    if (settings.has("dashboardHeight") && settings.has("dashboardDepth")) {
                        dashboardHeight = settings.getInt("dashboardHeight");
                        dashboardDepth = settings.getInt("dashboardDepth");
                        smartGlassesManager.updateGlassesDepthHeight(dashboardDepth, dashboardHeight);
                    }

                    // if (settings.has("useOnboardMic")) {
                    //     useOnboardMic = settings.getBoolean("useOnboardMic");
                    //     if (useOnboardMic) {
                    //         smartGlassesManager.changeMicrophoneState(false);
                    //     }
                    // }
//                     if (settings.has("sensingEnabled")) {
//                         sensingEnabled = settings.getBoolean("sensingEnabled");
// //                        EventBus.getDefault().post(new SensingEnabledEvent(sensingEnabled));
//                     }
                    // if (settings.has("bypassVad")) {
                    //     bypassVad = settings.getBoolean("bypassVad");
//                        EventBus.getDefault().post(new BypassVadEvent(bypassVad));
                    // }
//                    if (settings.has("bypassAudioEncoding")) {
//                        bypassAudioEncoding = settings.getBoolean("bypassAudioEncoding");
//                        EventBus.getDefault().post(new BypassAudioEncodingEvent(bypassAudioEncoding));
//                    }
                    if (settings.has("contextualDashboard")) {
                        contextualDashboardEnabled = settings.getBoolean("contextualDashboard");
//                        EventBus.getDefault().post(new ContextualDashboardEnabledEvent(contextualDashboardEnabled));
                    }
                    if (settings.has("metricSystemEnabled")) {
                        metricSystemEnabled = settings.getBoolean("metricSystemEnabled");
                    }
                    if (settings.has("alwaysOnStatusBar")) {
                        alwaysOnStatusBarEnabled = settings.getBoolean("alwaysOnStatusBar");
//                        EventBus.getDefault().post(new AlwaysOnStatusBarEnabledEvent(alwaysOnStatusBarEnabled));
                    }
                    Log.d("AugmentOsService", "Settings updated: " + settings.toString() + ".");

                    // Update UI or notify other components about settings change
                    sendStatusToAugmentOsManager();
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing settings update", e);
                }
            }

            public void onAudioPlayRequest(JSONObject audioRequest) {
                // Extract the audio request parameters
                String requestId = audioRequest.optString("requestId", "");
                String packageName = audioRequest.optString("packageName", "");
                String audioUrl = audioRequest.optString("audioUrl", null);
                double volume = audioRequest.optDouble("volume", 1.0);
                boolean stopOtherAudio = audioRequest.optBoolean("stopOtherAudio", true);

                // Send the audio request as a message to the AugmentOS Manager via BLE
                if (blePeripheral != null) {
                    // Create a message with the audio play request type
                    try {
                        JSONObject message = new JSONObject();
                        message.put("type", "audio_play_request");
                        message.put("requestId", requestId);
                        message.put("packageName", packageName);

                        if (audioUrl != null) {
                            message.put("audioUrl", audioUrl);
                        }
                        message.put("volume", volume);
                        message.put("stopOtherAudio", stopOtherAudio);

                        // Send to AugmentOS Manager
                        blePeripheral.sendDataToAugmentOsManager(message.toString());

                    } catch (JSONException e) {
                        Log.e(TAG, "Error creating audio request message for manager", e);
                    }
                }
            }

            public void onAudioStopRequest(JSONObject audioStopRequest) {
                // Extract the audio stop request parameters
                String sessionId = audioStopRequest.optString("sessionId", "");
                String appId = audioStopRequest.optString("appId", "");

                // Send the audio stop request as a message to the AugmentOS Manager via BLE
                if (blePeripheral != null) {
                    try {
                        JSONObject message = new JSONObject();
                        message.put("type", "audio_stop_request");
                        message.put("sessionId", sessionId);
                        message.put("appId", appId);

                        // Send to AugmentOS Manager
                        blePeripheral.sendDataToAugmentOsManager(message.toString());
                        Log.d(TAG, "🔇 Forwarded audio stop request to manager from app: " + appId);

                    } catch (JSONException e) {
                        Log.e(TAG, "Error creating audio stop request message for manager", e);
                    }
                }
            }

            @Override
            public void onSetLocationTier(String tier) {
                String logMessage = "AugmentosService: onSetLocationTier called with tier: " + tier;
                ServerComms.getInstance().sendButtonPress("DEBUG_LOG", logMessage);

                if (locationSystem != null) {
                    locationSystem.setTier(tier);
                } else {
                    Log.e("LOCATION_DEBUG", "AugmentosService: locationSystem is null, cannot set tier.");
                }
            }

            @Override
            public void onRequestSingleLocation(String accuracy, String correlationId) {
                if (locationSystem != null) {
                    locationSystem.requestSingleUpdate(accuracy, correlationId);
                } else {
                    Log.e("LOCATION_DEBUG", "AugmentosService: locationSystem is null, cannot request single location.");
                }
            }

            @Override
            public void onStartBufferRecording() {
                Log.d(TAG, "onStartBufferRecording from server");
                startBufferRecording();
            }

            @Override
            public void onStopBufferRecording() {
                Log.d(TAG, "onStopBufferRecording from server");
                stopBufferRecording();
            }

            @Override
            public void onSaveBufferVideo(String requestId, int durationSeconds) {
                Log.d(TAG, "onSaveBufferVideo from server: requestId=" + requestId + ", duration=" + durationSeconds);
                saveBufferVideo(requestId, durationSeconds);
            }

            @Override
            public void onStartVideoRecording(String requestId, boolean save) {
                Log.d(TAG, "onStartVideoRecording from server: requestId=" + requestId + ", save=" + save);
                startVideoRecording(requestId, save);
            }

            @Override
            public void onStopVideoRecording(String requestId) {
                Log.d(TAG, "onStopVideoRecording from server: requestId=" + requestId);
                stopVideoRecording(requestId);
            }
        });
    }

    // MentraOS_Manager Comms Callbacks
    public void sendStatusToBackend() {
        JSONObject status = generateStatusJson();
        Log.d(TAG, "Sending status to backend: " + status.toString());
        ServerComms.getInstance().sendCoreStatus(status);
    }

    /**
     * Send photo error response via SmartGlassesManager centralized handler
     */
    private void sendPhotoErrorResponse(String requestId, String errorCode, String errorMessage) {
        Log.d(TAG, "12 📡 Delegating photo error to SmartGlassesManager for requestId: " + requestId);
        if (smartGlassesManager != null) {
            smartGlassesManager.sendPhotoErrorResponse(requestId, errorCode, errorMessage);
        } else {
            Log.e(TAG, "❌ Cannot send photo error - SmartGlassesManager not available");
        }
    }

    public void sendStatusToAugmentOsManager() {
        JSONObject status = generateStatusJson();
        blePeripheral.sendDataToAugmentOsManager(status.toString());
    }

    /**
     * Upgrades the foreground service type to include connectedDevice when glasses are connected.
     * This avoids the 6-hour dataSync timeout on Android 15.
     */
    private void upgradeForegroundServiceType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return; // Service types not required before Android Q
        }

        // Check if we're already using connectedDevice type
        int desiredType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC |
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;

        if (currentForegroundServiceType == desiredType) {
            Log.d(TAG, "Already using connectedDevice service type");
            return;
        }

        // Check if we have Bluetooth permissions (required for connectedDevice type)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot upgrade to connectedDevice type - missing BLUETOOTH_CONNECT permission");
            return;
        }

        try {
            // Upgrade the service type by calling startForeground again
            startForeground(AUGMENTOS_NOTIFICATION_ID,
                    buildSharedForegroundNotification(this),
                    desiredType);

            currentForegroundServiceType = desiredType;
            Log.d(TAG, "Successfully upgraded foreground service type to include connectedDevice");
        } catch (Exception e) {
            Log.e(TAG, "Failed to upgrade foreground service type", e);
        }
    }

    @Override
    public void requestPing() {
        blePeripheral.sendPing();
    }

    @Override
    public void requestStatus() {
        sendStatusToAugmentOsManager();
    }

    @Override
    public void searchForCompatibleDeviceNames(String modelName) {
        Log.d("AugmentOsService", "Searching for compatible device names for model: " + modelName);
        SmartGlassesDevice device = SmartGlassesManager.getSmartGlassesDeviceFromModelName(modelName);
        if (device == null) {
            Log.d(TAG, "Tried to pair to Incorrect model name: " + modelName);
            //blePeripheral.sendNotifyManager("Incorrect model name: " + modelName, "error");
            return;
        }

        // Ensure we have a fresh SmartGlassesManager instance for searching
        // First check if it's already running
        if (smartGlassesManager != null) {
            // Stop the existing instance to ensure a clean state
            try {
                stopSmartGlassesManager();
                smartGlassesManager = null;
                smartGlassesManagerBound = false;
                Log.d(TAG, "Stopped existing SmartGlassesManager before device search");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping SmartGlassesManager: " + e.getMessage());
            }
        }

        // Now execute with a fresh instance
        executeOnceSmartGlassesManagerReady(() -> {
            smartGlassesManager.findCompatibleDeviceNames(device);
            // blePeripheral.sendGlassesSearchResultsToManager(modelName, compatibleDeviceNames);
        });
    }

    @Subscribe
    public void onMicStateForFrontendEvent(isMicEnabledForFrontendEvent event) {
        Log.d("AugmentOsService", "Received mic state for frontend event: " + event.micState);
        isMicEnabledForFrontend = event.micState;
        sendStatusToAugmentOsManager();
    }

    // TODO: This is for debug.. remove before pushing to prod
    @Subscribe
    public void handleMicModeChangedEvent(MicModeChangedEvent event) {
        Log.d(TAG, "Microphone mode changed: " + event.getStatus());

        // Log the new microphone status
        PhoneMicrophoneManager.MicStatus status = event.getStatus();
        //blePeripheral.sendNotifyManager(status.name(), "success");
        switch (status) {
            case SCO_MODE:
                Log.d(TAG, "Microphone using Bluetooth SCO mode");
                break;
            case NORMAL_MODE:
                Log.d(TAG, "Microphone using normal phone mic");
                break;
            case GLASSES_MIC:
                Log.d(TAG, "Microphone using glasses onboard mic");
                break;
            case PAUSED:
                Log.d(TAG, "Microphone recording paused (conflict detected)");
                break;
        }
    }

    @Override
    public void connectToWearable(String modelName, String deviceName, String deviceAddress) {
        Log.d("AugmentOsService", "Connecting to wearable: " + modelName + ". DeviceName: " + deviceName + ".deviceAddress:" + deviceAddress);

        SmartGlassesDevice device = SmartGlassesManager.getSmartGlassesDeviceFromModelName(modelName);
        if (device == null) {
            Log.d(TAG, "Tried to pair to Incorrect model name: " + modelName);
            //blePeripheral.sendNotifyManager("Incorrect model name: " + modelName, "error");
            return;
        }

        // Save device address for specific glasses types (just like Even)
        if (!deviceName.isEmpty()) {
            if (modelName.contains("Even Realities")) {
                savePreferredG1DeviceId(this, deviceName);
            } else if (modelName.equals("Mentra Live")) {
                // Save Mentra Live device name in its preferences
                SharedPreferences mentraPrefs = getSharedPreferences("MentraLivePrefs", Context.MODE_PRIVATE);
                mentraPrefs.edit().putString("LastConnectedDeviceName", deviceName).apply();
                Log.d("AugmentOsService", "Saved Mentra Live device name: " + deviceName);
            }
            else if (modelName.equals("Brilliant Labs Frame")) {
                // Save Frame device name in its preferences
                SharedPreferences framePrefs = getSharedPreferences("FramePrefs", Context.MODE_PRIVATE);
                framePrefs.edit().putString("LastFrameDeviceName", deviceName).apply();
                Log.d("AugmentOsService", "Saved Frame device name: " + deviceName);
            }
        }

        device.setDeviceAddress(deviceAddress);

        executeOnceSmartGlassesManagerReady(() -> {
            smartGlassesManager.connectToSmartGlasses(device);
            sendStatusToAugmentOsManager();
        });
    }

    @Override
    public void disconnectWearable(String wearableId) {
        Log.d("AugmentOsService", "Disconnecting from wearable: " + wearableId);

        // Reset WiFi status
        glassesWifiConnected = false;
        glassesWifiSsid = "";
        
        // Reset hotspot status
        glassesHotspotEnabled = false;
        glassesHotspotSsid = "";
        glassesHotspotPassword = "";
        glassesHotspotGatewayIp = "";

        // Reset state AND completely stop the service to get a clean state
        if (smartGlassesManager != null) {
            smartGlassesManager.resetState();
        }

        sendStatusToAugmentOsManager();
    }

    @Override
    public void forgetSmartGlasses() {
        Log.d("AugmentOsService", "Forgetting wearable");
        SmartGlassesManager.savePreferredWearable(this, "");
        SmartGlassesManager.savePreferredWearableAddress(this, "");
        deleteEvenSharedPreferences(this);

        // Clear MentraLive device name preference
        SharedPreferences mentraPrefs = getSharedPreferences("MentraLivePrefs", Context.MODE_PRIVATE);
        mentraPrefs.edit().remove("LastConnectedDeviceName").apply();

        SharedPreferences framePrefs = getSharedPreferences("FramePrefs", Context.MODE_PRIVATE);
        framePrefs.edit().remove("LastFrameDeviceName").apply();
        Log.d("AugmentOsService", "Cleared MentraLive stored device name");

        brightnessLevel = null;
        batteryLevel = null;
        glassesCharging = null;

        // CLEAR SERIAL NUMBER DATA
        glassesSerialNumber = null;
        glassesStyle = null;
        glassesColor = null;

        // Reset WiFi status
        glassesWifiConnected = false;
        glassesWifiSsid = "";
        
        // Reset hotspot status
        glassesHotspotEnabled = false;
        glassesHotspotSsid = "";
        glassesHotspotPassword = "";
        glassesHotspotGatewayIp = "";


        // Reset instead of stopping
        if (smartGlassesManager != null) {
            smartGlassesManager.resetState();
        }

        sendStatusToAugmentOsManager();
    }

    // TODO: Can remove this?
    @Override
    public void startApp(String packageName) {
        Log.d("AugmentOsService", "Starting app: " + packageName);
        // Logic to start the app by package name

        ServerComms.getInstance().startApp(packageName);
        if (smartGlassesManager == null || smartGlassesManager.getConnectedSmartGlasses() == null) {
            //    blePeripheral.sendNotifyManager("Connect glasses to use your app", "success");
        }
    }

    // TODO: Can remove this?
    @Override
    public void stopApp(String packageName) {
        Log.d("AugmentOsService", "Stopping app: " + packageName);
        ServerComms.getInstance().stopApp(packageName);
    }

    @Override
    public void setForceCoreOnboardMic(boolean toForceCoreOnboardMic) {
        SmartGlassesManager.saveForceCoreOnboardMic(this, toForceCoreOnboardMic);
        if(smartGlassesManager != null && smartGlassesManager.getConnectedSmartGlasses() != null) {
            Log.d(TAG, "force core onboard mic set while sgm running");
            //blePeripheral.sendNotifyManager(this.getResources().getString(R.string.SETTING_WILL_APPLY_ON_NEXT_GLASSES_CONNECTION), "success");
        }
        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void setSensingEnabled(boolean sensingEnabled) {
        SmartGlassesManager.saveSensingEnabled(this, sensingEnabled);
        if(smartGlassesManager != null && smartGlassesManager.getConnectedSmartGlasses() != null) {
            Log.d(TAG, "enable/disable sensing set while sgm running");
            //blePeripheral.sendNotifyManager(this.getResources().getString(R.string.SETTING_WILL_APPLY_ON_NEXT_GLASSES_CONNECTION), "success");
        }
        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void setBypassVadForDebugging(boolean bypassVadForDebugging) {
        SmartGlassesManager.saveBypassVadForDebugging(this, bypassVadForDebugging);
        sendStatusToBackend();
    }

    @Override
    public void setBypassAudioEncodingForDebugging(boolean bypassAudioEncodingForDebugging) {
        SmartGlassesManager.saveBypassAudioEncodingForDebugging(this, bypassAudioEncodingForDebugging);
        sendStatusToBackend();
    }

    @Override
    public void setEnforceLocalTranscription(boolean enforceLocalTranscription) {
        SmartGlassesManager.saveEnforceLocalTranscription(this, enforceLocalTranscription);
        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void onEnableOfflineMode(boolean enabled) {
        Log.d(TAG, "Enabling offline captions");
        // save a string offline in the preferences
        SmartGlassesManager.saveEnableOfflineMode(this, enabled);
        List<SpeechRequiredDataType> requiredData = new ArrayList<>();
        if (enabled) {
            requiredData.add(SpeechRequiredDataType.TRANSCRIPTION);
        }
        if (smartGlassesManager != null && SmartGlassesManager.getSensingEnabled(getApplicationContext())) {
            if (enabled) {
                smartGlassesManager.sendReferenceCard("// MentraOS - Starting App", "Offline Captions");
            } else {
                smartGlassesManager.sendTextWall("");
            }   
            smartGlassesManager.changeMicrophoneState(requiredData, false);
        }
    }

    @Override
    public void setContextualDashboardEnabled(boolean contextualDashboardEnabled) {
        this.contextualDashboardEnabled = contextualDashboardEnabled;
        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void setAlwaysOnStatusBarEnabled(boolean alwaysOnStatusBarEnabled) {
        // TODO: Fix this

        // if (alwaysOnStatusBarEnabled) {
        //     smartGlassesManager.windowManager.showAppLayer(
        //             "serverappid",
        //             () -> smartGlassesManager.sendTextWall(cachedDashboardTopLine),
        //             0
        //     );
        // }
        // else {
        //     EventBus.getDefault().post(new HomeScreenEvent());
        // }

//        Log.d(TAG, "Setting always on status bar enabled: " + alwaysOnStatusBarEnabled);

        this.alwaysOnStatusBarEnabled = alwaysOnStatusBarEnabled;
        sendStatusToBackend();
//        sendStatusToAugmentOsManager();
    }

    @Override
    public void setMetricSystemEnabled(boolean metricSystemEnabled) {
        this.metricSystemEnabled = metricSystemEnabled;
        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void setPowerSavingMode(boolean powerSavingMode) {
        SmartGlassesManager.savePowerSavingMode(this, powerSavingMode);
        if (smartGlassesManager != null && smartGlassesManager.getConnectedSmartGlasses() != null) {
            blePeripheral.sendNotifyManager(this.getResources().getString(R.string.SETTING_WILL_APPLY_ON_NEXT_GLASSES_CONNECTION), "success");
        }
        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void setUpdatingScreen(boolean updatingScreen) {
        if (smartGlassesManager != null) {
            smartGlassesManager.sendExitCommand();
            smartGlassesManager.setUpdatingScreen(updatingScreen);
        }
    }

    // TODO: Can remove this?
    @Override
    public void installAppFromRepository(String repository, String packageName) throws JSONException {
        Log.d("AugmentOsService", "Installing app from repository: " + packageName);
        blePeripheral.sendNotifyManager("Not implemented", "error");
    }

    // TODO: Can remove this?
    @Override
    public void uninstallApp(String uninstallPackageName) {
        Log.d(TAG, "uninstallApp not implemented");
        blePeripheral.sendNotifyManager("Not implemented", "error");
    }

    @Override
    public void requestAppInfo(String packageNameToGetDetails) {
        ThirdPartyEdgeApp app = edgeAppSystem.getThirdPartyAppByPackageName(packageNameToGetDetails);
        if (app == null) {
            blePeripheral.sendNotifyManager("Could not find app", "error");
            sendStatusToAugmentOsManager();
            return;
        }
        JSONArray settings = app.getSettings(this);
        if (settings == null) {
            blePeripheral.sendNotifyManager("Could not get app's details", "error");
            return;
        }
        blePeripheral.sendAppInfoToManager(app);
    }

    @Override
    public void handleNotificationData(JSONObject notificationData) {
        try {
            if (notificationData != null) {
                String appName = notificationData.optString("app_name");
                String title = notificationData.getString("title");
                String text = notificationData.getString("text");
//                long timestamp = notificationData.getLong("timestamp");
                String uuid = java.util.UUID.randomUUID().toString();

                ServerComms.getInstance().sendPhoneNotification(uuid, appName, title, text, "high");

                //EventBus.getDefault().post(new NotificationEvent(title, text, appName, timestamp, uuid));
            } else {
                System.out.println("Notification Data is null");
            }
        } catch (JSONException e) {
            Log.d(TAG, "JSONException occurred while handling notification data: " + e.getMessage());
        }
    }

    @Override
    public void handleNotificationDismissal(JSONObject dismissalData) {
        try {
            String appName = dismissalData.getString("app_name");
            String title = dismissalData.getString("title");
            String text = dismissalData.getString("text");
            String notificationKey = dismissalData.getString("notification_key");

            Log.d(TAG, "🚨 NOTIFICATION DISMISSED: " + appName + " - " + title);
            Log.d(TAG, "📝 Dismissal details - Text: " + text + ", Key: " + notificationKey);

            // Send dismissal to server via ServerComms
            String uuid = java.util.UUID.randomUUID().toString();
            ServerComms.getInstance().sendPhoneNotificationDismissal(uuid, appName, title, text, notificationKey);
            Log.d(TAG, "📡 Sent notification dismissal to server - UUID: " + uuid);

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing notification dismissal data", e);
        }
    }

    @Override
    public void updateGlassesBrightness(int brightness) {
        Log.d("AugmentOsService", "Updating glasses brightness: " + brightness);
        if (smartGlassesManager != null) {
            String title = "Brightness Adjustment";
            String body = "Updating glasses brightness to " + brightness + "%.";
            smartGlassesManager.windowManager.showAppLayer("system", () -> smartGlassesManager.sendReferenceCard(title, body), 6);
            smartGlassesManager.updateGlassesBrightness(brightness);
        }
        this.brightnessLevel = brightness;
        this.autoBrightness = false;

        // Save brightness settings to SharedPreferences
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(getString(R.string.SHARED_PREF_BRIGHTNESS), String.valueOf(brightness))
                .putBoolean(getString(R.string.SHARED_PREF_AUTO_BRIGHTNESS), false)
                .apply();

        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void updateGlassesAutoBrightness(boolean autoBrightness) {
        Log.d("AugmentOsService", "Updating glasses auto brightness: " + autoBrightness);
        if (smartGlassesManager != null) {
            smartGlassesManager.updateGlassesAutoBrightness(autoBrightness);
        }
        this.autoBrightness = autoBrightness;

        // Save auto brightness setting to SharedPreferences
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(getString(R.string.SHARED_PREF_AUTO_BRIGHTNESS), autoBrightness)
                .apply();

        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void updateGlassesHeadUpAngle(int headUpAngle) {
        Log.d("AugmentOsService", "Updating glasses head up angle: " + headUpAngle);
        if (smartGlassesManager != null) {
            smartGlassesManager.updateGlassesHeadUpAngle(headUpAngle);
        }
        this.headUpAngle = headUpAngle;

        // Save head up angle setting to SharedPreferences
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(getString(R.string.HEAD_UP_ANGLE), String.valueOf(headUpAngle))
                .apply();

        sendStatusToBackend();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void updateGlassesHeight(int dashboardHeight) {
        Log.d("AugmentOsService", "Updating glasses dashboard height: " + dashboardHeight);
        if (smartGlassesManager != null) {
            this.dashboardHeight = dashboardHeight;
            smartGlassesManager.updateGlassesDepthHeight(this.dashboardDepth, this.dashboardHeight);
            sendStatusToBackend();
            sendStatusToAugmentOsManager();
        } else {
            blePeripheral.sendNotifyManager("Connect glasses to update dashboard height", "error");
        }
    }


    @Override
    public void updateGlassesDepth(int depth) {
        Log.d("AugmentOsService", "Updating glasses depth: " + depth);
        if (smartGlassesManager != null) {
            this.dashboardDepth = depth;
            smartGlassesManager.updateGlassesDepthHeight(this.dashboardDepth, this.dashboardHeight);
            sendStatusToBackend();
            sendStatusToAugmentOsManager();
        } else {
            blePeripheral.sendNotifyManager("Connect glasses to update depth", "error");
        }
    }

    @Override
    public void setGlassesWifiCredentials(String ssid, String password) {
        Log.d(TAG, "@#@$@ Setting WiFi credentials for glasses, SSID: " + ssid);

        if (smartGlassesManager == null || smartGlassesManager.getConnectedSmartGlasses() == null) {
            blePeripheral.sendNotifyManager("No glasses connected to set WiFi credentials", "error");
            return;
        }

        String deviceModel = smartGlassesManager.getConnectedSmartGlasses().deviceModelName;
        if (deviceModel == null || !deviceModel.contains("Mentra Live")) {
            blePeripheral.sendNotifyManager("Connected glasses do not support WiFi", "error");
            return;
        }

        // Send WiFi credentials to glasses
        smartGlassesManager.sendWifiCredentials(ssid, password);

        // Show a message on the glasses
        smartGlassesManager.windowManager.showAppLayer(
                "system",
                () -> smartGlassesManager.sendReferenceCard("WiFi Setup",
                        "Connecting to: " + ssid),
                8
        );

        // Notify manager app
        blePeripheral.sendNotifyManager("WiFi credentials sent to glasses", "success");

        sendStatusToAugmentOsManager();

    }

    @Override
    public void disconnectFromWifi() {
        Log.d(TAG, "📶 Disconnecting glasses from WiFi");

        if (smartGlassesManager == null || smartGlassesManager.getConnectedSmartGlasses() == null) {
            blePeripheral.sendNotifyManager("No glasses connected to disconnect WiFi", "error");
            return;
        }

        String deviceModel = smartGlassesManager.getConnectedSmartGlasses().deviceModelName;
        if (deviceModel == null || !deviceModel.contains("Mentra Live")) {
            blePeripheral.sendNotifyManager("Connected glasses do not support WiFi", "error");
            return;
        }

        // Send WiFi disconnect command to glasses
        smartGlassesManager.disconnectFromWifi();

        // Show a message on the glasses
        smartGlassesManager.windowManager.showAppLayer(
                "system",
                () -> smartGlassesManager.sendReferenceCard("WiFi Disconnect",
                        "Disconnecting from WiFi..."),
                5
        );

        // Notify manager app
        blePeripheral.sendNotifyManager("WiFi disconnect command sent to glasses", "success");

        Log.d(TAG, "📶 WiFi disconnect command sent to glasses");
    }

    @Override
    public void setGlassesHotspotState(boolean enabled) {
        Log.d(TAG, "🔥 Setting glasses hotspot state: " + enabled);

        if (smartGlassesManager == null || smartGlassesManager.getConnectedSmartGlasses() == null) {
            blePeripheral.sendNotifyManager("No glasses connected to set hotspot state", "error");
            return;
        }

        String deviceModel = smartGlassesManager.getConnectedSmartGlasses().deviceModelName;
        if (deviceModel == null || !deviceModel.contains("Mentra Live")) {
            blePeripheral.sendNotifyManager("Connected glasses do not support hotspot", "error");
            return;
        }

        // Send hotspot state to glasses
        smartGlassesManager.sendHotspotState(enabled);

        sendStatusToAugmentOsManager();
    }

    @Override
    public void requestWifiScan() {
        Log.d(TAG, "Requesting WiFi scan from glasses");

        if (smartGlassesManager == null || smartGlassesManager.getConnectedSmartGlasses() == null) {
            blePeripheral.sendNotifyManager("No glasses connected to scan for WiFi networks", "error");
            return;
        }

        String deviceModel = smartGlassesManager.getConnectedSmartGlasses().deviceModelName;
        if (deviceModel == null || !deviceModel.contains("Mentra Live")) {
            blePeripheral.sendNotifyManager("Connected glasses do not support WiFi scanning", "error");
            return;
        }

        // Show a message on the glasses
        smartGlassesManager.windowManager.showAppLayer(
                "system",
                () -> smartGlassesManager.sendReferenceCard("WiFi Setup", "Scanning for networks..."),
                5
        );

        // Send the scan request to the glasses
        smartGlassesManager.requestWifiScan();

        // Notify manager app
        //blePeripheral.sendNotifyManager("Scanning for WiFi networks...", "info");
    }
    
    @Override
    public void queryGalleryStatus() {
        Log.d(TAG, "📸 Querying gallery status from glasses");
        
        if (smartGlassesManager == null || smartGlassesManager.getConnectedSmartGlasses() == null) {
            Log.e(TAG, "📸 No glasses connected to query gallery status");
            return;
        }
        
        String deviceModel = smartGlassesManager.getConnectedSmartGlasses().deviceModelName;
        if (deviceModel == null || !deviceModel.contains("Mentra Live")) {
            Log.w(TAG, "📸 Connected glasses do not support gallery status query");
            return;
        }
        
        // Send the query request to the glasses
        smartGlassesManager.queryGalleryStatus();
    }

    @Override
    public void setPreferredMic(String mic) {
        Log.d("AugmentOsService", "Setting preferred mic: " + mic);
        preferredMic = mic;
        SmartGlassesManager.setPreferredMic(this, mic);

        // Trigger immediate microphone switch using direct getter approach
        if (smartGlassesManager != null && smartGlassesManagerBound) {
            SmartGlassesRepresentative rep = smartGlassesManager.getSmartGlassesRepresentative();
            if (rep != null) {
                PhoneMicrophoneManager micManager = rep.getPhoneMicrophoneManager();
                if (micManager != null) {
                    Log.d("AugmentOsService", "Notifying PhoneMicrophoneManager of preference change");
                    micManager.onMicrophonePreferenceChanged();
                } else {
                    Log.d("AugmentOsService", "No PhoneMicrophoneManager available - preference will take effect on next connection");
                }
            } else {
                Log.d("AugmentOsService", "No SmartGlassesRepresentative available - preference will take effect on next connection");
            }
        }
    }

    @Override
    public void restartTranscriber() {
        Log.d("AugmentOsService", "Restarting transcriber");
        ServerComms.getInstance().restartTranscriber();

    }

    @Override
    public void setButtonMode(String mode) {
        Log.d("AugmentOsService", "Setting button mode: " + mode);
        // Save locally
        SmartGlassesManager.setButtonPressMode(this, mode);

        // Send to glasses if connected
        if (smartGlassesManager != null && smartGlassesManagerBound) {
            smartGlassesManager.sendButtonModeSetting(mode);
        }
    }

    @Override
    public void sendGalleryModeActive(boolean active) {
        Log.d("AugmentOsService", "📸 Sending gallery mode active to glasses: " + active);
        
        // Send to glasses if connected (Mentra Live only)
        if (smartGlassesManager != null && smartGlassesManagerBound) {
            smartGlassesManager.sendGalleryModeActive(active);
        } else {
            Log.w("AugmentOsService", "Cannot send gallery mode - glasses not connected");
        }
    }

    @Override
    public void setButtonPhotoSize(String size) {
        Log.d("AugmentOsService", "Setting button photo size: " + size);
        // Save locally
        SmartGlassesManager.setButtonPhotoSize(this, size);

        // Send to glasses if connected
        if (smartGlassesManager != null && smartGlassesManagerBound) {
            smartGlassesManager.sendButtonPhotoSettings(size);
        }
    }

    @Override
    public void setButtonVideoSettings(int width, int height, int fps) {
        Log.d("AugmentOsService", "Setting button video settings: " + width + "x" + height + "@" + fps);
        // Save locally
        SmartGlassesManager.setButtonVideoSettings(this, width, height, fps);

        // Send to glasses if connected
        if (smartGlassesManager != null && smartGlassesManagerBound) {
            smartGlassesManager.sendButtonVideoRecordingSettings(width, height, fps);
        }
    }

    @Override
    public void setButtonCameraLed(boolean enabled) {
        Log.d("AugmentOsService", "Setting button camera LED: " + enabled);
        // Save locally
        SmartGlassesManager.setButtonCameraLed(this, enabled);

        // Send to glasses if connected
        if (smartGlassesManager != null && smartGlassesManagerBound) {
            smartGlassesManager.sendButtonCameraLedSetting(enabled);
        }
    }


    @Override
    public void setAuthSecretKey(String uniqueUserId, String authSecretKey) {
        Log.d("AugmentOsService", "Setting auth secret key: " + authSecretKey);
        if (authHandler.getCoreToken() == null || !authHandler.getCoreToken().equals(authSecretKey)) {
            authHandler.setAuthSecretKey(authSecretKey);
            ServerComms.getInstance().disconnectWebSocket();
            ServerComms.getInstance().connectWebSocket(authHandler.getCoreToken());
        }
        authHandler.verifyAuthSecretKey(uniqueUserId);
        sendStatusToAugmentOsManager();
    }

    @Override
    public void verifyAuthSecretKey() {
        Log.d("AugmentOsService", "verify auth secret key");
    }

    @Override
    public void deleteAuthSecretKey() {
        Log.d("AugmentOsService", "Deleting auth secret key");
        authHandler.deleteAuthSecretKey();

        // When auth key is deleted (sign out), reset state for the next user
        if (smartGlassesManager != null) {
            smartGlassesManager.resetState();
        }

        // Stop all running apps
        if (edgeAppSystem != null) {
            edgeAppSystem.stopAllThirdPartyApps();
        }

        // Reset cached app data
        cachedThirdPartyAppList = new ArrayList<>();
        cachedDashboardDisplayObject = null;
        // When auth key is deleted (sign out), reset state for the next user
        if (smartGlassesManager != null) {
            smartGlassesManager.resetState();
        }

        // Stop all running apps
        if (edgeAppSystem != null) {
            edgeAppSystem.stopAllThirdPartyApps();
        }

        // Reset cached app data
        cachedThirdPartyAppList = new ArrayList<>();
        cachedDashboardDisplayObject = null;

        // Disconnect from server
        ServerComms.getInstance().disconnectWebSocket();
        webSocketLifecycleManager.updateSmartGlassesState(SmartGlassesConnectionState.DISCONNECTED);

        sendStatusToAugmentOsManager();
    }

    @Override
    public void updateAppSettings(String targetApp, JSONObject settings) {
        Log.d("AugmentOsService", "Updating settings for app: " + targetApp);
        ThirdPartyEdgeApp app = edgeAppSystem.getThirdPartyAppByPackageName(targetApp);
        if (app == null) {
            blePeripheral.sendNotifyManager("Could not find app", "error");
            return;
        }

        boolean allSuccess = true;
        try {
            // New loop over all keys in the settings object
            Iterator<String> keys = settings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = settings.get(key);
                if (!app.updateSetting(this, key, value)) {
                    allSuccess = false;
                }
            }
        } catch (JSONException e) {
            Log.e("AugmentOsService", "Failed to parse settings object", e);
            allSuccess = false;
        }

        if (!allSuccess) {
            blePeripheral.sendNotifyManager("Error updating settings", "error");
        }
    }

    /**
     * Helper method to clean up all resources, disconnect from devices,
     * and reset the service state completely
     */
    private void cleanupAllResources() {
        Log.d(TAG, "Cleaning up all resources and connections");

        // Stop all running apps
        if (edgeAppSystem != null) {
            edgeAppSystem.stopAllThirdPartyApps();
        }

        // Unbind from LocationSystem service
        if (locationSystemBound) {
            unbindService(locationServiceConnection);
            locationSystemBound = false;
        }

        // Clean up calendar system
        if (calendarSystem != null) {
            calendarSystem.cleanup();
        }

        // Clean up screen capture resources
        if (screenCaptureRunnable != null) {
            screenCaptureHandler.removeCallbacks(screenCaptureRunnable);
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        // BATTERY OPTIMIZATION: Clean up our animation handler
        if (animationRunnable != null) {
            uiHandler.removeCallbacks(animationRunnable);
            animationRunnable = null;
        }
        // Remove all pending posts to avoid any UI updates after destruction
        uiHandler.removeCallbacksAndMessages(null);

        // Reset glasses connection - unbind from the service
        if (smartGlassesManagerBound) {
            stopSmartGlassesManager(); // This method handles all the cleanup
        } else if (smartGlassesManager != null) {
            smartGlassesManager = null;
            edgeAppSystem.setSmartGlassesManager(null);
        }

        // Reset cached data
        cachedThirdPartyAppList = new ArrayList<>();
        cachedDashboardDisplayObject = null;

        // Reset WiFi status
        glassesWifiConnected = false;
        glassesWifiSsid = "";
        
        // Reset hotspot status
        glassesHotspotEnabled = false;
        glassesHotspotSsid = "";
        glassesHotspotPassword = "";
        glassesHotspotGatewayIp = "";

        // Disconnect websockets
        if (webSocketLifecycleManager != null) {
            webSocketLifecycleManager.updateSmartGlassesState(SmartGlassesConnectionState.DISCONNECTED);
            webSocketLifecycleManager.cleanup();
        }
        ServerComms.getInstance().disconnectWebSocket();

        // Clear BLE connections
        if (blePeripheral != null) {
            blePeripheral.destroy();
        }

        if (edgeAppSystem != null) {
            edgeAppSystem.destroy();
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service being destroyed");

        // BATTERY OPTIMIZATION: Cleanup resources first, then unregister from EventBus
        // This prevents unhandled EventBus events during cleanup
        cleanupAllResources();

        // Unregister from EventBus with proper error handling
        try {
            if (EventBus.getDefault().isRegistered(this)) {
                EventBus.getDefault().unregister(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering from EventBus", e);
        }

        // Stop periodic datetime sending
        datetimeHandler.removeCallbacks(datetimeRunnable);

        super.onDestroy();
    }


    public class LocalBinder extends Binder {
        public AugmentosService getService() {
            // Return this instance of LocalService so clients can call public methods
            return AugmentosService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        Log.d(TAG, "Something bound");
        return binder;
    }

    // Called when the backend notifies that an app has started
    public void onAppStarted(String packageName) {
        Log.d(TAG, "App started: " + packageName + " - checking for auto-reconnection");

        // Check if glasses are disconnected but there is a saved pair, initiate connection
        if (smartGlassesManager != null &&
                smartGlassesManager.getSmartGlassesConnectState() == SmartGlassesConnectionState.DISCONNECTED) {

            String preferredWearable = SmartGlassesManager.getPreferredWearable(this);
            final String preferredWearableAddress = SmartGlassesManager.getPreferredWearableAddress(this);
            Log.d(TAG, "Found preferred wearable: " + preferredWearable);

            if (preferredWearable != null && !preferredWearable.isEmpty()) {
                SmartGlassesDevice preferredDevice = SmartGlassesManager.getSmartGlassesDeviceFromModelName(preferredWearable);
                if (preferredDevice != null) {
                    Log.d(TAG, "Auto-connecting to glasses due to app start: " + preferredWearable);
                    preferredDevice.setDeviceAddress(preferredWearable);
                    // Always run on main thread to avoid threading issues
                    new Handler(Looper.getMainLooper()).post(() -> {
                        // Use executeOnceSmartGlassesManagerReady to ensure proper connection flow
                        executeOnceSmartGlassesManagerReady(() -> {
                            if (smartGlassesManager != null) {
                                smartGlassesManager.connectToSmartGlasses(preferredDevice);
                                sendStatusToAugmentOsManager();

                                // Notify manager app about the auto-connection attempt
                                if (blePeripheral != null) {
                                    blePeripheral.sendNotifyManager("Auto-connecting to " + preferredWearable, "info");
                                }
                            }
                        });
                    });
                } else {
                    Log.w(TAG, "Invalid preferred device found, cannot auto-connect: " + preferredWearable);
                }
            } else {
                Log.d(TAG, "No preferred wearable found for auto-connection");
            }
        } else if (smartGlassesManager == null) {
            Log.d(TAG, "SmartGlassesManager is null, cannot check connection state for auto-reconnection");
        } else {
            Log.d(TAG, "Glasses already connected or connecting, skipping auto-reconnection. Current state: " +
                    smartGlassesManager.getSmartGlassesConnectState());
        }

        // Send notification to manager app about app start (existing functionality)
        if (blePeripheral != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "app_started");
                msg.put("packageName", packageName);
                blePeripheral.sendDataToAugmentOsManager(msg.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Error sending app started notification to manager", e);
            }
        }
    }

    // Called when the backend notifies that an app has stopped
    public void onAppStopped(String packageName) {
        if (blePeripheral != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "app_stopped");
                msg.put("packageName", packageName);
                blePeripheral.sendDataToAugmentOsManager(msg.toString());
            } catch (JSONException e) {
                // Optionally log or handle error
            }
        }
    }

    @Override
    public void setServerUrl(String url) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (url == null || url.trim().isEmpty()) {
            // Reset to default by removing the override
            prefs.edit().remove("augmentos_server_url_override").apply();
            Log.d(TAG, "Server URL override cleared, using default URL");
        } else {
            // Set the custom URL override
            prefs.edit().putString("augmentos_server_url_override", url).apply();
            Log.d(TAG, "Server URL override set to: " + url);
        }
        // Disconnect and reconnect websocket to use new URL
        ServerComms.getInstance().disconnectWebSocket();
        if (authHandler != null && authHandler.getCoreToken() != null) {
            ServerComms.getInstance().connectWebSocket(authHandler.getCoreToken());
        }
    }

    /**
     * Handle calendar permission changes
     */
    public void handleCalendarPermissionChange() {
        if (calendarSystem != null) {
            calendarSystem.handlePermissionChange();
        }
    }

    // Event handler for glasses version info
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGlassesVersionInfoEvent(GlassesVersionInfoEvent event) {
        this.glassesAppVersion = event.getAppVersion();
        this.glassesBuildNumber = event.getBuildNumber();
        this.glassesDeviceModel = event.getDeviceModel();
        this.glassesAndroidVersion = event.getAndroidVersion();
        this.glassesOtaVersionUrl = event.getOtaVersionUrl();
        Log.d("AugmentOsService", "Glasses version info: " + glassesAppVersion + " " + glassesBuildNumber + " " + glassesDeviceModel + " " + glassesAndroidVersion + " OTA URL: " + glassesOtaVersionUrl);
        sendStatusToAugmentOsManager();
    }

    // Event handler for pairing failure
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPairFailureEvent(PairFailureEvent event) {
        Log.d(TAG, "Pairing failure: " + event.getError());
        if (blePeripheral != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "pair_failure");
                msg.put("error", event.getError());
                blePeripheral.sendDataToAugmentOsManager(msg.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Error sending pair failure notification to manager", e);
            }
        }
    }


    @Override
    public void onAudioPlayRequest(JSONObject audioRequest) {
        Log.d(TAG, "Received audio play request from cloud: " + audioRequest.toString());

        // Forward the audio play request to the manager if connected
        if (blePeripheral != null) {
            // Extract the audio request parameters
            String requestId = audioRequest.optString("requestId", "");
            String packageName = audioRequest.optString("packageName", "");
            String audioUrl = audioRequest.optString("audioUrl", null);
            double volume = audioRequest.optDouble("volume", 1.0);
            boolean stopOtherAudio = audioRequest.optBoolean("stopOtherAudio", true);

            // Send the audio request as a message to the AugmentOS Manager via BLE
            try {
                JSONObject message = new JSONObject();
                message.put("type", "audio_play_request");
                message.put("requestId", requestId);
                message.put("packageName", packageName);

                if (audioUrl != null) {
                    message.put("audioUrl", audioUrl);
                }
                message.put("volume", volume);
                message.put("stopOtherAudio", stopOtherAudio);

                // Send to AugmentOS Manager
                blePeripheral.sendDataToAugmentOsManager(message.toString());
                Log.d(TAG, "🔊 Forwarded audio play request to manager from cloud");

            } catch (JSONException e) {
                Log.e(TAG, "Error creating audio request message for manager", e);
            }
        }
    }

    @Override
    public void onAudioPlayResponse(JSONObject audioResponse) {
        Log.d(TAG, "Received audio play response from manager: " + audioResponse.toString());

        try {
            // Forward the audio play response to the cloud
            ServerComms.getInstance().sendAudioPlayResponse(audioResponse);

        } catch (Exception e) {
            Log.e(TAG, "Failed to forward audio play response to cloud", e);
        }
    }

    @Override
    public void onAudioStopRequest(JSONObject audioStopParams) {
        Log.d(TAG, "Received audio stop request from cloud: " + audioStopParams.toString());

        // Forward the audio stop request to the manager if connected
        if (blePeripheral != null) {
            blePeripheral.sendAudioStopRequest(audioStopParams);
        }
    }

    // Event handler for download progress
    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    public void onDownloadProgressEvent(DownloadProgressEvent event) {
        Log.d(TAG, "🎯 $#$# EVENT RECEIVED! Download progress: " + event.getStatus() +
                " - " + event.getProgress() + "% (" +
                event.getBytesDownloaded() + "/" + event.getTotalBytes() + " bytes)");

        // Store download progress information
        downloadStatus = event.getStatus();
        downloadProgress = event.getProgress();
        downloadBytesDownloaded = event.getBytesDownloaded();
        downloadTotalBytes = event.getTotalBytes();
        downloadErrorMessage = event.getErrorMessage();
        downloadTimestamp = event.getTimestamp();

        // Update status to include download progress
        sendStatusToAugmentOsManager();

        // Send notification to manager app if needed
        if (blePeripheral != null) {
            switch (event.getStatus()) {
                case STARTED:
                    blePeripheral.sendNotifyManager("Mentra Live Update Started", "info");
                    break;
                // case FINISHED:
                //     blePeripheral.sendNotifyManager("Mentra Live Update Completed", "success");
                //     break;
                case FAILED:
                    blePeripheral.sendNotifyManager("Mentra Live Update Failed: " + event.getErrorMessage(), "error");
                    break;
                case PROGRESS:
                    // Only notify at key milestones (25%, 50%, 75%, 100%)
                    // if (event.getProgress() % 25 == 0 || event.getProgress() == 100) {
                    //     blePeripheral.sendNotifyManager("Mentra Live Update: " + event.getProgress() + "%", "info");
                    // }
                    break;
            }
        }
    }

    // Event handler for installation progress
    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    public void onInstallationProgressEvent(InstallationProgressEvent event) {
        Log.d(TAG, "🔧 Received installation progress: " + event.getStatus() +
                " - APK: " + event.getApkPath());

        // Store installation progress information
        installationStatus = event.getStatus();
        installationApkPath = event.getApkPath();
        installationErrorMessage = event.getErrorMessage();
        installationTimestamp = event.getTimestamp();

        // Update status to include installation progress
        sendStatusToAugmentOsManager();

        // If installation is finished, clear OTA progress data after 30 seconds
        if (event.getStatus() == InstallationProgressEvent.InstallationStatus.FINISHED) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                clearOtaProgressData();
            }, 15000); // 15 seconds
        }

        // Send notification to manager app if needed
        if (blePeripheral != null) {
            switch (event.getStatus()) {
                // case STARTED:
                //     blePeripheral.sendNotifyManager("Mentra Live Update Installation Started", "info");
                //     break;
                case FINISHED:
                    blePeripheral.sendNotifyManager("Mentra Live Update Installation Completed", "success");
                    break;
                case FAILED:
                    blePeripheral.sendNotifyManager("Mentra Live Update Installation Failed: " + event.getErrorMessage(), "error");
                    break;
            }
        }
    }

    // Event handler for heartbeat sent
    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    public void onHeartbeatSentEvent(com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.HeartbeatSentEvent event) {
        Log.d(TAG, "💓 Heartbeat sent at: " + event.timestamp);
        
        if (blePeripheral != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("heartbeat_sent", new JSONObject().put("timestamp", event.timestamp));
                blePeripheral.sendDataToAugmentOsManager(data.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending heartbeat sent event to manager", e);
            }
        }
    }

    // Event handler for heartbeat received
    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    public void onHeartbeatReceivedEvent(com.augmentos.augmentos_core.smarterglassesmanager.eventbusmessages.HeartbeatReceivedEvent event) {
        Log.d(TAG, "💓 Heartbeat received at: " + event.timestamp);
        
        if (blePeripheral != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("heartbeat_received", new JSONObject().put("timestamp", event.timestamp));
                blePeripheral.sendDataToAugmentOsManager(data.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending heartbeat received event to manager", e);
            }
        }
    }

    /**
     * Clear OTA progress data to hide the progress section
     */
    private void clearOtaProgressData() {
        Log.d(TAG, "🧹 Clearing OTA progress data");

        // Clear download progress
        downloadStatus = null;
        downloadProgress = 0;
        downloadBytesDownloaded = 0;
        downloadTotalBytes = 0;
        downloadErrorMessage = null;
        downloadTimestamp = 0;

        // Clear installation progress
        installationStatus = null;
        installationApkPath = null;
        installationErrorMessage = null;
        installationTimestamp = 0;

        // Update status to reflect cleared OTA progress
        sendStatusToAugmentOsManager();

        Log.d(TAG, "✅ OTA progress data cleared");
    }

    // Event handler for glasses serial number
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGlassesSerialNumberEvent(GlassesSerialNumberEvent event) {
        this.glassesSerialNumber = event.serialNumber;
        this.glassesStyle = event.style;
        this.glassesColor = event.color;
        Log.d(TAG, "Glasses serial number: " + glassesSerialNumber + ", Style: " + glassesStyle + ", Color: " + glassesColor);
        sendStatusToAugmentOsManager();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onProtobufSchemaVersionEvent(ProtobufSchemaVersionEvent event) {
        Log.d(TAG, "Received protobuf schema version event: " + event.getBuildInfo());
        
        // Store the protobuf version in the service state
        this.protobufSchemaVersion = event.getBuildInfo();
        
        // Send updated status to React Native (includes the new protobuf version)
        sendStatusToAugmentOsManager();
        
        Log.d(TAG, "Updated protobuf schema version in core status: " + this.protobufSchemaVersion);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onProtocolVersionResponseEvent(ProtocolVersionResponseEvent event) {
        Log.d(TAG, "Received glasses protobuf version response event: " + event.getFormattedVersion());
        
        // Store the glasses protobuf version in the service state
        this.glassesProtobufVersion = event.getFormattedVersion();
        
        // Send updated status to React Native (includes the new glasses protobuf version)
        sendStatusToAugmentOsManager();
        
        Log.d(TAG, "Updated glasses protobuf version in core status: " + this.glassesProtobufVersion);
    }

    @Override
    public void simulateHeadPosition(String position) {
        Log.d(TAG, "Simulating head position: " + position);

        if ("up".equals(position)) {
            onGlassesHeadUpEvent(new GlassesHeadUpEvent());
        } else {
            onGlassesHeadDownEvent(new GlassesHeadDownEvent());
        }
    }

    @Override
    public void simulateButtonPress(String buttonId, String pressType) {
        Log.d(TAG, "Simulating button press: " + buttonId + " " + pressType);

        String deviceModel = "";
        if(smartGlassesManager != null && smartGlassesManager.getConnectedSmartGlasses() != null)
            deviceModel = smartGlassesManager.getConnectedSmartGlasses().deviceModelName;

        EventBus.getDefault().post(new ButtonPressEvent(deviceModel, buttonId, pressType));
    }

    @Override
    public void onDisplayTextNotified(String text, int size, int x, int y) {
        Log.d(TAG, "onDisplayTextNotified text: " + text + " size:" + size + " x:" + x + " y:" + y);

        EventBus.getDefault().post(new DisplayTextEvent(text, size, x, y));
    }

    @Override
    public void onDisplayImageNotified(String imageType, String imageSize) {
        Log.d(TAG, "onDisplayImageNotified imageType: " + imageType + ", imageSize: " + imageSize);
        EventBus.getDefault().post(new DisplayImageEvent(imageType, imageSize));
    }

    @Override
    public void clearDisplay() {
        Log.d(TAG, "clearDisplay called");
        if (smartGlassesManager != null) {
            smartGlassesManager.clearDisplay();
        } else {
            Log.e(TAG, "SmartGlassesManager is null, cannot clear display");
        }
    }

    @Override
    public void setLc3AudioEnabled(boolean enabled) {
        Log.d(TAG, "setLc3AudioEnabled called: " + enabled);
        if (smartGlassesManager != null && 
            smartGlassesManager.getSmartGlassesRepresentative() != null &&
            smartGlassesManager.getSmartGlassesRepresentative().smartGlassesCommunicator != null &&
            smartGlassesManager.getSmartGlassesRepresentative().smartGlassesCommunicator instanceof MentraNexSGC) {
            ((MentraNexSGC) smartGlassesManager.getSmartGlassesRepresentative().smartGlassesCommunicator).setLc3AudioEnabled(enabled);
        } else {
            Log.e(TAG, "SmartGlassesManager is null or not connected to MentraNexSGC, cannot control LC3 audio");
        }
    }

    @Override
    public void updateSettings(JSONObject newSettings) {
        Log.d(TAG, "Updating settings: " + newSettings.toString());
        try {
            // if there is a default_wearable setting, update our wearable:
            if (newSettings.has("default_wearable")) {
                String defaultWearable = newSettings.getString("default_wearable");
                SmartGlassesManager.savePreferredWearable(this, defaultWearable);
                sendStatusToAugmentOsManager();
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error updating settings", e);
        }
    }

    @Override
    public void startBufferRecording() {
        Log.d(TAG, "Starting buffer recording on smart glasses");
        if (smartGlassesManager != null) {
            smartGlassesManager.startBufferRecording();
        } else {
            Log.e(TAG, "SmartGlassesManager is null, cannot start buffer recording");
        }
    }

    @Override
    public void stopBufferRecording() {
        Log.d(TAG, "Stopping buffer recording on smart glasses");
        if (smartGlassesManager != null) {
            smartGlassesManager.stopBufferRecording();
        } else {
            Log.e(TAG, "SmartGlassesManager is null, cannot stop buffer recording");
        }
    }

    @Override
    public void saveBufferVideo(String requestId, int durationSeconds) {
        Log.d(TAG, "Saving buffer video: requestId=" + requestId + ", duration=" + durationSeconds + "s");
        if (smartGlassesManager != null) {
            smartGlassesManager.saveBufferVideo(requestId, durationSeconds);
        } else {
            Log.e(TAG, "SmartGlassesManager is null, cannot save buffer video");
        }
    }

    @Override
    public void startVideoRecording(String requestId, boolean save) {
        Log.d(TAG, "Starting video recording: requestId=" + requestId + ", save=" + save);
        if (smartGlassesManager != null) {
            smartGlassesManager.startVideoRecording(requestId, save);
        } else {
            Log.e(TAG, "SmartGlassesManager is null, cannot start video recording");
        }
    }

    @Override
    public void stopVideoRecording(String requestId) {
        Log.d(TAG, "Stopping video recording: requestId=" + requestId);
        if (smartGlassesManager != null) {
            smartGlassesManager.stopVideoRecording(requestId);
        } else {
            Log.e(TAG, "SmartGlassesManager is null, cannot stop video recording");
        }
    }
}