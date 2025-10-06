export type AppPermissionType =
  | "ALL"
  | "MICROPHONE"
  | "CAMERA"
  | "CALENDAR"
  | "LOCATION"
  | "BACKGROUND_LOCATION"
  | "READ_NOTIFICATIONS"
  | "POST_NOTIFICATIONS"

export interface AppletPermission {
  description: string
  type: AppPermissionType
  required?: boolean
}

// Define the AppletInterface based on AppI from SDK
export interface AppletInterface {
  packageName: string
  name: string
  developerName?: string
  publicUrl?: string
  isSystemApp?: boolean
  uninstallable?: boolean
  webviewURL?: string
  logoURL: string | any
  type: string // "standard", "background", "offline"
  appStoreId?: string
  developerId?: string
  hashedEndpointSecret?: string
  hashedApiKey?: string
  description?: string
  version?: string
  settings?: Record<string, unknown>
  isPublic?: boolean
  appStoreStatus?: "DEVELOPMENT" | "SUBMITTED" | "REJECTED" | "PUBLISHED"
  developerProfile?: {
    company?: string
    website?: string
    contactEmail?: string
    description?: string
    logo?: string
  }
  permissions: AppletPermission[]
  is_running?: boolean
  loading?: boolean
  compatibility?: {
    isCompatible: boolean
    missingRequired: Array<{
      type: string
      description?: string
    }>
    missingOptional: Array<{
      type: string
      description?: string
    }>
    message: string
  }
  // New optional isOnline from backend
  isOnline?: boolean | null
  // Offline app configuration
  offlineRoute?: string // React Native route for offline apps
}

// Utility functions for offline apps
export const isOfflineApp = (app: AppletInterface): boolean => {
  return app.type === "offline" || !!app.offlineRoute
}

export const getOfflineAppRoute = (app: AppletInterface): string | null => {
  if (!isOfflineApp(app)) return null
  return app.offlineRoute || null
}
