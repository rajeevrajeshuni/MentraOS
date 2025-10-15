// YourAppsList.tsx
import {useEffect, useRef, useState, useCallback, RefObject, Fragment} from "react"
import {View, TouchableOpacity, Animated, Platform, ViewStyle, TextStyle, Easing, Keyboard} from "react-native"
import {Text} from "@/components/ignite"
import {useFocusEffect} from "@react-navigation/native"
import {useAppStatus} from "@/contexts/AppletStatusProvider"
import {useCoreStatus} from "@/contexts/CoreStatusProvider"
import {isOfflineApp, getOfflineAppRoute} from "@/types/AppletTypes"
import {askPermissionsUI} from "@/utils/PermissionsUtils"
import showAlert from "@/utils/AlertUtils"
import {translate} from "@/i18n"
import {useAppTheme} from "@/utils/useAppTheme"
import {AppListItem} from "./AppListItem"
import {Spacer} from "@/components/misc/Spacer"
import Divider from "@/components/misc/Divider"
import {ThemedStyle} from "@/theme"
import AppsHeader from "@/components/misc/AppsHeader"
import {AppListStoreLink} from "@/components/misc/AppListStoreLink"
import {useNavigationHistory} from "@/contexts/NavigationHistoryContext"
import restComms from "@/managers/RestComms"
import {SETTINGS_KEYS, useSettingsStore} from "@/stores/settings"

export default function InactiveAppList({
  isSearchPage = false,
  searchQuery,
  liveCaptionsRef,
  onClearSearch,
}: {
  isSearchPage?: boolean
  searchQuery?: string
  liveCaptionsRef?: RefObject<any>
  onClearSearch?: () => void
}) {
  const {appStatus, optimisticallyStartApp, optimisticallyStopApp, clearPendingOperation} = useAppStatus()
  const {status: _status} = useCoreStatus()
  const [_onboardingModalVisible, _setOnboardingModalVisible] = useState(false)
  const [onboardingCompleted, setOnboardingCompleted] = useState(true)
  const [_inLiveCaptionsPhase, _setInLiveCaptionsPhase] = useState(false)
  const [_showSettingsHint, _setShowSettingsHint] = useState(false)
  const [showOnboardingTip, setShowOnboardingTip] = useState(false)
  const [_isLoading, _setIsLoading] = useState(true)
  const {themed, theme} = useAppTheme()
  const {push} = useNavigationHistory()

  // Static values instead of animations
  const bounceAnim = useRef(new Animated.Value(0)).current
  const pulseAnim = useRef(new Animated.Value(0)).current

  const [_containerWidth, _setContainerWidth] = useState(0)

  // Reference for the Live Captions list item (use provided ref or create new one)
  const internalLiveCaptionsRef = useRef<any>(null)
  const actualLiveCaptionsRef = liveCaptionsRef || internalLiveCaptionsRef

  // Constants for grid item sizing
  const _GRID_MARGIN = 6 // Total horizontal margin per item (left + right)
  const _numColumns = 4 // Desired number of columns
  // Calculate the item width based on container width and margins

  // console.log('%%% appStatus', appStatus);

  // Check onboarding status whenever the screen comes into focus
  useFocusEffect(
    useCallback(() => {
      const checkOnboardingStatus = async () => {
        const completed = await useSettingsStore.getState().getSetting(SETTINGS_KEYS.onboarding_completed)
        setOnboardingCompleted(completed)

        if (!completed) {
          setShowOnboardingTip(true)
        } else {
          setShowOnboardingTip(false)
        }
      }

      checkOnboardingStatus()
    }, []),
  )

  // Check if onboarding is completed on initial load
  useEffect(() => {
    const checkOnboardingStatus = async () => {
      const completed = await useSettingsStore.getState().getSetting(SETTINGS_KEYS.onboarding_completed)
      setOnboardingCompleted(completed)
      setShowOnboardingTip(!completed)
    }

    checkOnboardingStatus()
  }, [])

  // Set static values instead of animations
  useEffect(() => {
    if (showOnboardingTip) {
      // Set static values instead of animating
      bounceAnim.setValue(0)
      pulseAnim.setValue(0.5)
    } else {
      bounceAnim.setValue(0)
      pulseAnim.setValue(0)
    }
  }, [showOnboardingTip])

  const completeOnboarding = () => {
    useSettingsStore.getState().setSetting(SETTINGS_KEYS.onboarding_completed, true)
    setOnboardingCompleted(true)
    setShowOnboardingTip(false)

    // Make sure to post an update to ensure all components re-render
    // This is important to immediately hide any UI elements that depend on these states
    setTimeout(() => {
      // Force a re-render by setting state again
      setShowOnboardingTip(false)
    }, 100)
  }

  function checkIsForegroundAppStart(packageName: string, isForeground: boolean): Promise<boolean> {
    if (!isForeground) {
      return Promise.resolve(true)
    }

    // Always allow starting a new foreground app - it will automatically replace any running one
    return Promise.resolve(true)
  }

  const startApp = async (packageName: string) => {
    if (!onboardingCompleted) {
      if (packageName !== "com.augmentos.livecaptions" && packageName !== "com.mentra.livecaptions") {
        showAlert(
          translate("home:completeOnboardingTitle"),
          translate("home:completeOnboardingMessage"),
          [{text: translate("common:ok")}],
          {
            iconName: "information-outline",
            iconColor: theme.colors.textDim,
          },
        )
        return
      } else {
        completeOnboarding()
      }
    }

    // Find the app we're trying to start
    const appInfo = appStatus.find(app => app.packageName === packageName)
    if (!appInfo) {
      console.error("App not found:", packageName)
      return
    }

    // If this is a foreground app and there's already one running, stop it first
    if (appInfo.type === "standard") {
      const runningForegroundApp = appStatus.find(
        app => app.is_running && app.type === "standard" && app.packageName !== packageName,
      )
      if (runningForegroundApp) {
        console.log("Stopping running foreground app:", runningForegroundApp.packageName)
        optimisticallyStopApp(runningForegroundApp.packageName)

        // Skip offline apps - they don't need server communication
        if (!isOfflineApp(runningForegroundApp)) {
          try {
            await restComms.stopApp(runningForegroundApp.packageName)
            clearPendingOperation(runningForegroundApp.packageName)
          } catch (error) {
            console.error("Error stopping foreground app:", error)
          }
        } else {
          clearPendingOperation(runningForegroundApp.packageName)
        }
      }
    }

    // Debug: Log camera app properties
    if (packageName === "com.mentra.camera") {
      console.log(
        "📸 Camera app found in appStatus:",
        JSON.stringify(
          {
            packageName: appInfo.packageName,
            name: appInfo.name,
            isOffline: appInfo.isOffline,
            isOnline: appInfo.isOnline,
            type: appInfo.type,
          },
          null,
          2,
        ),
      )
      console.log("📸 isOfflineApp check result:", isOfflineApp(appInfo))
    }

    // Handle offline apps - activate only
    if (isOfflineApp(appInfo)) {
      // Activate the app (make it appear in active apps)
      optimisticallyStartApp(packageName, appInfo.type)
      return
    }

    // If the app appears offline, confirm before proceeding
    if (appInfo.isOnline === false) {
      const _developerName = (" " + (appInfo.developerName || "") + " ").replace("  ", " ")
      const shouldProceed = await new Promise<boolean>(resolve => {
        showAlert(
          `${appInfo.name} can't be reached`,
          `This app is offline. The developer "${appInfo.developerName}" needs to bring it back online. Please contact them for details.`,
          [
            {text: translate("common:cancel"), style: "cancel", onPress: () => resolve(false)},
            {text: "Try Anyway", onPress: () => resolve(true)},
          ],
          {iconName: "alert-circle-outline", iconColor: theme.colors.warning},
        )
      })
      if (!shouldProceed) {
        return
      }
    }

    // Optional live health check (keep but after offline confirmation)
    if (!(await restComms.checkAppHealthStatus(appInfo.packageName))) {
      showAlert(`${appInfo.name} can't be reached`, "Please try again later.", [{text: translate("common:ok")}])
      return
    }

    // ask for needed perms:
    const result = await askPermissionsUI(appInfo, theme)
    if (result === -1) {
      return
    } else if (result === 0) {
      startApp(appInfo.packageName) // restart this function
      return
    }

    // Check if glasses are connected and this is the first app being activated
    // const glassesConnected = status.glasses_info?.model_name != null
    // const activeApps = appStatus.filter(app => app.is_running)

    // if (!glassesConnected && activeApps.length === 0) {
    //   // Show alert for first app activation when glasses aren't connected
    //   const shouldContinue = await new Promise<boolean>(resolve => {
    //     showAlert(translate("home:glassesNotConnected"), translate("home:appWillRunWhenConnected"), [
    //       {
    //         text: translate("common:cancel"),
    //         style: "cancel",
    //         onPress: () => resolve(false),
    //       },
    //       {
    //         text: translate("common:ok"),
    //         onPress: () => resolve(true),
    //       },
    //     ])
    //   })

    //   if (!shouldContinue) {
    //     return
    //   }
    // }

    // Find the opacity value for this app
    const itemOpacity = opacities[packageName]

    // Animate the app disappearing
    if (itemOpacity) {
      Animated.timing(itemOpacity, {
        toValue: 0,
        duration: 150,
        easing: Easing.out(Easing.ease),
        useNativeDriver: true,
      }).start()
    }

    // Wait a bit for animation to complete
    await new Promise(resolve => setTimeout(resolve, 150))

    // Only update UI optimistically after user confirms and animation completes
    optimisticallyStartApp(packageName)
  }

  const openAppSettings = (app: any) => {
    console.log("%%% opening app settings", app)

    // Handle offline apps - navigate directly to React Native route
    if (isOfflineApp(app)) {
      const offlineRoute = getOfflineAppRoute(app)
      if (offlineRoute) {
        push(offlineRoute)
        return
      }
    }

    // Handle regular cloud apps
    push("/applet/settings", {packageName: app.packageName, appName: app.name})
  }

  // Filter out duplicate apps, running apps, and incompatible apps
  let availableApps = appStatus.filter(app => {
    if (app.is_running) {
      return false
    }
    // Filter out incompatible apps (they will be shown in a separate section)
    if (app.compatibility && !app.compatibility.isCompatible) {
      return false
    }
    // Check if this is the first occurrence of this package name
    const firstIndex = appStatus.findIndex(a => a.packageName === app.packageName)
    return firstIndex === appStatus.indexOf(app)
  })

  // remove the notify app on iOS
  if (Platform.OS === "ios") {
    availableApps = availableApps.filter(app => app.packageName !== "cloud.augmentos.notify" && app.name !== "Notify")
  }

  // Sort apps: Camera app first, then during onboarding put Live Captions second, otherwise alphabetical
  availableApps.sort((a, b) => {
    // Camera app always comes first
    if (a.packageName === "com.mentra.camera") return -1
    if (b.packageName === "com.mentra.camera") return 1

    // During onboarding, put Live Captions second
    if (!onboardingCompleted) {
      const aIsLiveCaptions =
        a.packageName === "com.augmentos.livecaptions" || a.packageName === "com.mentra.livecaptions"
      const bIsLiveCaptions =
        b.packageName === "com.augmentos.livecaptions" || b.packageName === "com.mentra.livecaptions"

      // If a is Live Captions, it should come second (after Camera)
      if (aIsLiveCaptions && !bIsLiveCaptions) return -1
      // If b is Live Captions, it should come second (after Camera)
      if (!aIsLiveCaptions && bIsLiveCaptions) return 1
    }

    // Otherwise sort alphabetically
    return a.name.localeCompare(b.name)
  })

  if (searchQuery) {
    availableApps = availableApps.filter(app => app.name.toLowerCase().includes(searchQuery.toLowerCase()))
  }

  // Create a ref for all app opacities, keyed by packageName
  const opacities = useRef<Record<string, Animated.Value>>(
    Object.fromEntries(appStatus.map(app => [app.packageName, new Animated.Value(0)])),
  ).current

  // Ensure every available app has an Animated.Value and animate to 1 on mount/change
  useEffect(() => {
    availableApps.forEach(app => {
      // Lazily create Animated.Value if it doesn't exist yet (e.g. newly fetched app)
      if (!(app.packageName in opacities)) {
        opacities[app.packageName] = new Animated.Value(0)
      }

      Animated.timing(opacities[app.packageName], {
        toValue: 1,
        duration: 300,
        easing: Easing.in(Easing.ease),
        useNativeDriver: true,
      }).start()
    })
  }, [availableApps])

  return (
    <View>
      {!isSearchPage && (
        <AppsHeader title="home:inactiveApps" showSearchIcon={appStatus.filter(app => app.is_running).length === 0} />
      )}

      {availableApps.map((app, index) => {
        // Check if this is the LiveCaptions app
        const isLiveCaptions =
          app.packageName === "com.augmentos.livecaptions" ||
          app.packageName === "cloud.augmentos.live-captions" ||
          app.packageName === "com.mentra.livecaptions"

        // Only set ref for LiveCaptions app
        const ref = isLiveCaptions ? actualLiveCaptionsRef : null

        // Get the shared opacity Animated.Value for this app
        const itemOpacity = opacities[app.packageName]

        return (
          <Fragment key={app.packageName}>
            <AppListItem
              app={app}
              isActive={false}
              onTogglePress={async () => {
                const isForegroundApp = app.type == "standard"
                const res = await checkIsForegroundAppStart(app.packageName, isForegroundApp)
                if (res) {
                  // Don't animate here - let startApp handle all UI updates
                  startApp(app.packageName)
                }
              }}
              onSettingsPress={() => openAppSettings(app)}
              refProp={ref}
              opacity={itemOpacity}
            />
            {index < availableApps.length - 1 && (
              <>
                <Spacer height={8} />
                <Divider variant="inset" />
                <Spacer height={8} />
              </>
            )}
          </Fragment>
        )
      })}

      {/* Add "Get More Apps" link at the bottom - only on home page, not search */}
      {availableApps.length > 0 && !isSearchPage && (
        <>
          <Spacer height={8} />
          <Divider variant="inset" />
          <Spacer height={8} />
          <AppListStoreLink />
        </>
      )}

      {/* Show "No apps found" message when searching returns no results */}
      {isSearchPage && searchQuery && availableApps.length === 0 && (
        <View style={themed($noAppsContainer)}>
          <Text style={themed($noAppsText)}>{translate("home:noAppsFoundForQuery", {query: searchQuery})}</Text>
          {onClearSearch && (
            <>
              <Spacer height={16} />
              <TouchableOpacity
                style={themed($clearSearchButton)}
                onPress={() => {
                  Keyboard.dismiss()
                  onClearSearch()
                }}
                activeOpacity={0.7}
                hitSlop={{top: 10, bottom: 10, left: 10, right: 10}}>
                <Text style={themed($clearSearchButtonText)}>{translate("home:clearSearch")}</Text>
              </TouchableOpacity>
            </>
          )}
        </View>
      )}

      {/* Add bottom padding for better scrolling experience */}
      <Spacer height={40} />
    </View>
  )
}

const $noAppsContainer: ThemedStyle<ViewStyle> = ({spacing}) => ({
  flex: 1,
  justifyContent: "center",
  alignItems: "center",
  paddingVertical: spacing.xxl,
})

const $noAppsText: ThemedStyle<TextStyle> = ({colors}) => ({
  fontSize: 16,
  color: colors.textDim,
  textAlign: "center",
})

const $clearSearchButton: ThemedStyle<ViewStyle> = ({colors, spacing}) => ({
  backgroundColor: colors.primary,
  paddingHorizontal: spacing.lg,
  paddingVertical: spacing.sm,
  borderRadius: 8,
  alignSelf: "center",
})

const $clearSearchButtonText: ThemedStyle<TextStyle> = ({colors}) => ({
  fontSize: 14,
  fontWeight: "600",
  color: colors.textAlt,
  textAlign: "center",
})
