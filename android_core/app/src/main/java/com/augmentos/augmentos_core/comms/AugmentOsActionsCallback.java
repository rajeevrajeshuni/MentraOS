package com.augmentos.augmentos_core.comms;

import org.json.JSONException;
import org.json.JSONObject;

public interface AugmentOsActionsCallback {
    void requestPing();
    void requestStatus();
    void searchForCompatibleDeviceNames(String modelName);

    void connectToWearable(String modelName, String deviceName, String deviceAddress);

    void disconnectWearable(String wearableId);
    void forgetSmartGlasses();
    void startApp(String packageName);
    void stopApp(String packageName);
    void setSensingEnabled(boolean sensingEnabled);
    void setForceCoreOnboardMic(boolean forceCoreOnboardMic);
    void setContextualDashboardEnabled(boolean contextualDashboardEnabled);
    void setMetricSystemEnabled(boolean metricSystemEnabled);
    void setUpdatingScreen(boolean updatingScreen);
    void setBypassVadForDebugging(boolean bypassVadForDebugging);
    void setBypassAudioEncodingForDebugging(boolean bypassAudioEncodingForDebugging);
    void setEnforceLocalTranscription(boolean enforceLocalTranscription);
    void onEnableOfflineMode(boolean enabled);
    void setAlwaysOnStatusBarEnabled(boolean alwaysOnStatusBarEnabled);
    void setPowerSavingMode(boolean powerSavingMode);
    void installAppFromRepository(String repository, String packageName) throws JSONException;
    void uninstallApp(String packageName);
    void handleNotificationData(JSONObject notificationData);
    void setAuthSecretKey(String userId, String authSecretKey);
    void verifyAuthSecretKey();
    void deleteAuthSecretKey();
    void updateAppSettings(String targetApp, JSONObject settings);
    void requestAppInfo(String packageNameToGetDetails);
    void updateGlassesBrightness(int brightness);
    void updateGlassesAutoBrightness(boolean autoBrightness);
    void updateGlassesHeadUpAngle(int headUpAngle);
    void updateGlassesHeight(int height);
    void updateGlassesDepth(int depth);
    void setGlassesWifiCredentials(String ssid, String password);
    void setGlassesHotspotState(boolean enabled);
    void requestWifiScan();
    void disconnectFromWifi();
    void queryGalleryStatus();
    void setPreferredMic(String mic);
    void setButtonMode(String mode);
    void sendGalleryModeActive(boolean active);
    void setButtonPhotoSize(String size);
    void setButtonVideoSettings(int width, int height, int fps);
    void setButtonCameraLed(boolean enabled);
    void setServerUrl(String url);
    void onAudioPlayRequest(JSONObject audioRequest);
    void onAudioPlayResponse(JSONObject audioResponse);
    void onAudioStopRequest(JSONObject audioStopParams);
    void simulateHeadPosition(String position);
    void simulateButtonPress(String buttonId, String pressType);

    void onDisplayTextNotified(String text, int size, int x, int y);

    void onDisplayImageNotified(String imageType, String imageSize);

    void clearDisplay();

    void setLc3AudioEnabled(boolean enabled);
    void updateSettings(JSONObject newSettings);

    void handleNotificationDismissal(JSONObject dismissalData);
    void startBufferRecording();
    void stopBufferRecording();
    void saveBufferVideo(String requestId, int durationSeconds);
    void startVideoRecording(String requestId, boolean save);
    void stopVideoRecording(String requestId);
    void restartTranscriber();
}
