import {AppletInterface} from "./AppletTypes"

/**
 * Offline Apps Configuration
 *
 * These are local React Native apps that don't require webviews or server communication.
 * They navigate directly to specific React Native routes when activated.
 */

export const OFFLINE_APPS: AppletInterface[] = [
  {
    packageName: "com.augmentos.camera",
    name: "Camera",
    type: "offline",
    developerName: "Mentra",
    logoURL: require("../../assets/icons/camera.png"),
    permissions: [],
    offlineRoute: "/asg/gallery",
    is_running: false,
    loading: false,
    isOnline: true, // Always "online" for offline apps
    compatibility: {
      isCompatible: true,
      missingRequired: [],
      missingOptional: [],
      message: "",
    },
  },
]

/**
 * Get all offline apps with dynamic compatibility based on connected glasses
 */
export const getOfflineApps = (
  glassesModelName?: string | null,
  defaultWearable?: string | null,
): AppletInterface[] => {
  return OFFLINE_APPS.map(app => {
    // Camera app requires camera-capable glasses to be connected
    if (app.packageName === "com.augmentos.camera") {
      // No glasses saved/configured - show as incompatible
      if (!defaultWearable) {
        return {
          ...app,
          compatibility: {
            isCompatible: false,
            missingRequired: [{type: "camera", available: false}],
            missingOptional: [],
            message: "Camera app requires glasses with a camera to be connected.",
          },
        }
      }

      // Glasses saved but disconnected - hide entirely (return null to filter out)
      if (defaultWearable && !glassesModelName) {
        return null as any // This will be filtered out by the caller
      }

      // Glasses connected - check if they have camera capability
      const {hasCamera} = require("@/config/glassesFeatures")
      const hasGlassesCamera = hasCamera(glassesModelName)

      if (!hasGlassesCamera) {
        // Mark as incompatible if glasses don't have camera
        return {
          ...app,
          compatibility: {
            isCompatible: false,
            missingRequired: [{type: "camera", available: false}],
            missingOptional: [],
            message: `Camera app requires glasses with a camera. ${glassesModelName} does not have a camera.`,
          },
        }
      }

      // Glasses connected with camera - compatible (return app as-is)
      return app
    }

    return app
  }).filter(app => app !== null) // Filter out null entries
}

/**
 * Get a specific offline app by package name
 */
export const getOfflineApp = (packageName: string): AppletInterface | undefined => {
  return OFFLINE_APPS.find(app => app.packageName === packageName)
}

/**
 * Check if a package name corresponds to an offline app
 */
export const isOfflineAppPackage = (packageName: string): boolean => {
  return OFFLINE_APPS.some(app => app.packageName === packageName)
}
