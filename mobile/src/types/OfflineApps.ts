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
 * Get all offline apps
 */
export const getOfflineApps = (): AppletInterface[] => {
  return OFFLINE_APPS
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
