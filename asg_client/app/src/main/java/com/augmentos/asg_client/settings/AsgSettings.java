package com.augmentos.asg_client.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Arrays;

/**
 * Settings manager for ASG Client
 * Handles persistent storage of user preferences
 */
public class AsgSettings {
    private static final String TAG = "AugmentOS_AsgSettings";
    private static final String PREFS_NAME = "asg_settings";
    private static final String KEY_BUTTON_VIDEO_WIDTH = "button_video_width";
    private static final String KEY_BUTTON_VIDEO_HEIGHT = "button_video_height";
    private static final String KEY_BUTTON_VIDEO_FPS = "button_video_fps";
    private static final String KEY_BUTTON_PHOTO_SIZE = "button_photo_size";
    private static final String KEY_BUTTON_CAMERA_LED = "button_camera_led";
    
    private final SharedPreferences prefs;
    private final Context context;
    
    // Transient state - not persisted, resets on restart
    private volatile boolean saveInGalleryMode = true;
    
    public AsgSettings(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Log.d(TAG, "AsgSettings initialized");
    }
    
    /**
     * Get the video recording settings for button-initiated recording
     * @return VideoSettings with the saved resolution and fps
     */
    public VideoSettings getButtonVideoSettings() {
        int width = prefs.getInt(KEY_BUTTON_VIDEO_WIDTH, 1280);
        int height = prefs.getInt(KEY_BUTTON_VIDEO_HEIGHT, 720);
        int fps = prefs.getInt(KEY_BUTTON_VIDEO_FPS, 30);
        VideoSettings settings = new VideoSettings(width, height, fps);
        Log.d(TAG, "Retrieved button video settings: " + settings);
        return settings;
    }
    
    /**
     * Set the video recording settings for button-initiated recording
     * @param settings VideoSettings to save
     */
    public void setButtonVideoSettings(VideoSettings settings) {
        if (settings == null) {
            Log.w(TAG, "Attempted to set null video settings, ignoring");
            return;
        }
        if (!settings.isValid()) {
            Log.w(TAG, "Attempted to set invalid video settings: " + settings + ", ignoring");
            return;
        }
        Log.d(TAG, "Setting button video settings to: " + settings);
        // Using commit() for immediate persistence
        prefs.edit()
            .putInt(KEY_BUTTON_VIDEO_WIDTH, settings.width)
            .putInt(KEY_BUTTON_VIDEO_HEIGHT, settings.height)
            .putInt(KEY_BUTTON_VIDEO_FPS, settings.fps)
            .commit();
    }
    
    /**
     * Set button video settings from width, height, and fps values
     * @param width Video width
     * @param height Video height  
     * @param fps Video frame rate
     */
    public void setButtonVideoSettings(int width, int height, int fps) {
        VideoSettings settings = new VideoSettings(width, height, fps);
        setButtonVideoSettings(settings);
    }
    
    /**
     * Get the photo size setting for button-initiated photos
     * @return Photo size ("small", "medium", or "large")
     */
    public String getButtonPhotoSize() {
        String size = prefs.getString(KEY_BUTTON_PHOTO_SIZE, "medium");
        Log.d(TAG, "Retrieved button photo size: " + size);
        return size;
    }
    
    /**
     * Set the photo size setting for button-initiated photos
     * @param size Photo size ("small", "medium", or "large")
     */
    public void setButtonPhotoSize(String size) {
        // Validate size
        if (!Arrays.asList("small", "medium", "large").contains(size)) {
            Log.w(TAG, "Invalid photo size: " + size + ", using medium");
            size = "medium";
        }
        Log.d(TAG, "Setting button photo size to: " + size);
        // Using commit() for immediate persistence
        prefs.edit().putString(KEY_BUTTON_PHOTO_SIZE, size).commit();
    }
    
    /**
     * Get the camera LED setting for button-initiated recordings
     * @return true if LED should be enabled, false otherwise
     */
    public boolean getButtonCameraLedEnabled() {
        boolean enabled = prefs.getBoolean(KEY_BUTTON_CAMERA_LED, true); // Default to true
        Log.d(TAG, "Retrieved button camera LED setting: " + enabled);
        return enabled;
    }
    
    /**
     * Set the camera LED setting for button-initiated recordings
     * @param enabled true to enable LED, false to disable
     */
    public void setButtonCameraLedEnabled(boolean enabled) {
        Log.d(TAG, "Setting button camera LED to: " + enabled);
        // Using commit() for immediate persistence
        prefs.edit().putBoolean(KEY_BUTTON_CAMERA_LED, enabled).commit();
    }
    
    /**
     * Check if currently in gallery mode (save/capture mode active)
     * This is transient state set by the phone when gallery view is active
     * @return true if in gallery mode, false otherwise
     */
    public boolean isSaveInGalleryMode() {
        return saveInGalleryMode;
    }
    
    /**
     * Set the gallery mode state
     * Called when phone notifies that gallery view is active/inactive
     * @param inGalleryMode true if gallery mode active, false otherwise
     */
    public void setSaveInGalleryMode(boolean inGalleryMode) {
        Log.d(TAG, "📸 Gallery mode state changed: " + (inGalleryMode ? "ACTIVE" : "INACTIVE"));
        this.saveInGalleryMode = inGalleryMode;
    }
}