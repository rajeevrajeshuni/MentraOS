import {useState, useRef, useCallback, useMemo, FC, memo} from "react"
import {View, TouchableOpacity, ViewStyle, TextStyle, Dimensions, FlatList, Alert} from "react-native"
import Popover from "react-native-popover-view"
import {Text} from "@/components/ignite"
import AppIcon from "./AppIcon"
import {useAppTheme} from "@/utils/useAppTheme"
import {ThemedStyle} from "@/theme"
import {translate} from "@/i18n"
import MaterialCommunityIcons from "react-native-vector-icons/MaterialCommunityIcons"
import EmptyAppsView from "@/components/home/EmptyAppsView"
import {TreeIcon} from "assets/icons/component/TreeIcon"
import {AppletInterface, AppletPermission, isOfflineApp, getOfflineAppRoute} from "@/types/AppletTypes"
import {useNavigationHistory} from "@/contexts/NavigationHistoryContext"

// Constants at the top for easy configuration
const GRID_COLUMNS = 4
const SCREEN_WIDTH = Dimensions.get("window").width
const POPOVER_ICON_SIZE = 32
const EMPTY_APP_PLACEHOLDER = {
  packageName: "",
  name: "",
  type: "standard",
  logoURL: "",
  permissions: [] as AppletPermission[],
} as const

interface AppsGridViewProps {
  apps: AppletInterface[]
  onStartApp?: (packageName: string) => void
  onStopApp?: (packageName: string) => void
  onOpenSettings?: (app: AppletInterface) => void
  onOpenWebView?: (app: AppletInterface) => void
  title?: string
  isIncompatible?: boolean
}

// Separate component for the popover to improve performance and maintainability
const AppPopover: FC<{
  app: AppletInterface | null
  visible: boolean
  anchorRef: any
  onClose: () => void
  onCloseComplete: () => void
  onStartStop: () => void
  onOpenSettings: () => void
  onOpenWebView: () => void
  themed: (style: any) => any
  theme: any
}> = ({
  app,
  visible,
  anchorRef,
  onClose,
  onCloseComplete,
  onStartStop,
  onOpenSettings,
  onOpenWebView,
  themed,
  theme,
}) => {
  if (!app || !anchorRef || !visible) return null

  return (
    <Popover
      from={anchorRef}
      isVisible={visible}
      onRequestClose={onClose}
      onCloseComplete={onCloseComplete}
      popoverStyle={themed($popoverStyle)}
      backgroundStyle={{backgroundColor: "rgba(0, 0, 0, 0.5)"}}
      animationConfig={{duration: 200}}
      arrowSize={{width: 16, height: 8}}>
      <View style={themed($popoverContent)}>
        <View style={themed($popoverHeader)}>
          <AppIcon app={app} style={themed($popoverAppIcon)} />
          <Text text={app.name} style={themed($popoverAppName)} numberOfLines={1} />
        </View>

        <View style={themed($popoverDivider)} />

        <TouchableOpacity style={themed($popoverOption)} onPress={onStartStop}>
          <MaterialCommunityIcons
            name={app.is_running ? "stop-circle-outline" : "play-circle-outline"}
            size={24}
            color={theme.colors.text}
          />
          <Text
            text={app.is_running ? translate("common:stop") : translate("common:start")}
            style={themed($popoverOptionText)}
          />
        </TouchableOpacity>

        <TouchableOpacity style={themed($popoverOption)} onPress={onOpenSettings}>
          <MaterialCommunityIcons name="cog-outline" size={24} color={theme.colors.text} />
          <Text text={translate("common:settings")} style={themed($popoverOptionText)} />
        </TouchableOpacity>

        {app.webviewURL && (
          <TouchableOpacity style={themed($popoverOption)} onPress={onOpenWebView}>
            <MaterialCommunityIcons name="web" size={24} color={theme.colors.text} />
            <Text text={translate("common:openWebView")} style={themed($popoverOptionText)} />
          </TouchableOpacity>
        )}
      </View>
    </Popover>
  )
}

// Separate component for grid items to improve performance
const GridItem: FC<{
  item: AppletInterface
  onPress: (app: AppletInterface) => void
  setRef: (ref: any) => void
  themed: (style: any) => any
  theme: any
}> = ({item, onPress, setRef, themed, theme}) => {
  const handlePress = useCallback(() => {
    if (item.packageName) {
      onPress(item)
    }
  }, [item, onPress])

  // Don't render empty placeholder items
  if (!item.packageName) {
    return <View style={themed($gridItem)} />
  }

  const isForeground = item.type === "standard"
  const isOffline = item.isOnline === false
  const isOfflineAppItem = isOfflineApp(item)

  return (
    <TouchableOpacity ref={setRef} style={themed($gridItem)} onPress={handlePress} activeOpacity={0.7}>
      <View style={themed($appContainer)}>
        <AppIcon app={item as any} style={themed($appIcon)} />
        {isOffline && (
          <View style={themed($offlineBadge)}>
            <MaterialCommunityIcons name="alert-circle" size={14} color={theme.colors.error} />
          </View>
        )}
        {isForeground && (
          <View style={themed($foregroundIndicator)}>
            <TreeIcon size={theme.spacing.md} color={theme.colors.text} />
          </View>
        )}
        {isOfflineAppItem && (
          <View style={themed($offlineAppIndicator)}>
            <MaterialCommunityIcons name="home" size={theme.spacing.md} color={theme.colors.text} />
          </View>
        )}
      </View>
      <Text
        text={item.name}
        style={themed(isOffline ? $appNameOffline : $appName)}
        numberOfLines={item.name.split(" ").length > 1 ? 2 : 1}
      />
    </TouchableOpacity>
  )
}

export const AppsGridViewRoot: FC<AppsGridViewProps> = ({
  apps,
  onStartApp,
  onStopApp,
  onOpenSettings,
  onOpenWebView,
}) => {
  const {themed, theme} = useAppTheme()
  const {push} = useNavigationHistory()
  const [selectedApp, setSelectedApp] = useState<AppletInterface | null>(null)
  const [popoverVisible, setPopoverVisible] = useState(false)
  const touchableRefs = useRef<Map<string, any>>(new Map())

  // Maintenance dialog for offline apps
  const showMaintenanceDialog = useCallback(
    (app: AppletInterface) => {
      const developerName = (" " + (app.developerName || "") + " ").replace("  ", " ")
      Alert.alert(
        "App is down for maintenance",
        `${app.name} appears to be offline. You can try anyway.\n\nThe developer${developerName}needs to get their server back up and running. Please contact them for more details.`,
        [
          {text: "Cancel", style: "cancel"},
          {
            text: "Try Anyway",
            onPress: () => {
              onStartApp?.(app.packageName)
            },
          },
        ],
        {cancelable: true},
      )
    },
    [onStartApp],
  )

  // Memoize padded apps to avoid recalculation on every render
  const paddedApps = useMemo(() => {
    if (!apps || apps.length === 0) return []

    const appsCopy = [...apps]
    const remainder = appsCopy.length % GRID_COLUMNS

    if (remainder !== 0) {
      const missingApps = GRID_COLUMNS - remainder
      for (let i = 0; i < missingApps; i++) {
        appsCopy.push({...EMPTY_APP_PLACEHOLDER})
      }
    }

    return appsCopy
  }, [apps])

  const handleAppPress = useCallback(
    (app: AppletInterface) => {
      // Prevent opening if another popover is already visible
      if (popoverVisible) {
        return
      }

      const ref = touchableRefs.current.get(app.packageName)
      if (ref) {
        // Handle offline apps
        if (isOfflineApp(app)) {
          // If app is already running, navigate to its route
          if (app.is_running) {
            const offlineRoute = getOfflineAppRoute(app)
            if (offlineRoute) {
              push(offlineRoute)
              return
            }
          }
          // If app is not running, activate it
          onStartApp?.(app.packageName)
          return
        }

        // If app is offline, show maintenance dialog instead of popover
        if (app.isOnline === false) {
          showMaintenanceDialog(app)
          return
        }
        setSelectedApp(app)
        setPopoverVisible(true)
      }
    },
    [showMaintenanceDialog, popoverVisible, push, onStartApp],
  )

  const handlePopoverClose = useCallback(() => {
    setPopoverVisible(false)
  }, [])

  const handlePopoverCloseComplete = useCallback(() => {
    setSelectedApp(null)
  }, [])

  const handleStartStop = useCallback(() => {
    if (!selectedApp) return

    try {
      if (selectedApp.is_running) {
        onStopApp?.(selectedApp.packageName)
      } else {
        onStartApp?.(selectedApp.packageName)
      }
      handlePopoverClose()
    } catch (error) {
      console.error("Error starting/stopping app:", error)
      handlePopoverClose()
    }
  }, [selectedApp, onStartApp, onStopApp, handlePopoverClose])

  const handleOpenSettings = useCallback(() => {
    if (!selectedApp) return

    try {
      onOpenSettings?.(selectedApp)
      handlePopoverClose()
    } catch (error) {
      console.error("Error opening settings:", error)
      handlePopoverClose()
    }
  }, [selectedApp, onOpenSettings, handlePopoverClose])

  const handleOpenWebView = useCallback(() => {
    if (!selectedApp || !onOpenWebView) return

    try {
      onOpenWebView(selectedApp)
      handlePopoverClose()
    } catch (error) {
      console.error("Error opening webview:", error)
      handlePopoverClose()
    }
  }, [selectedApp, onOpenWebView, handlePopoverClose])

  const setItemRef = useCallback(
    (packageName: string) => (ref: any) => {
      if (packageName) {
        if (ref) {
          touchableRefs.current.set(packageName, ref)
        } else {
          touchableRefs.current.delete(packageName)
        }
      }
    },
    [],
  )

  const renderAppItem = useCallback(
    ({item}: {item: AppletInterface}) => (
      <GridItem
        item={item}
        onPress={handleAppPress}
        setRef={setItemRef(item.packageName)}
        themed={themed}
        theme={theme}
      />
    ),
    [handleAppPress, setItemRef, themed, theme],
  )

  const keyExtractor = useCallback((item: AppletInterface, index: number) => {
    return item.packageName || `empty-${index}`
  }, [])

  // Handle empty state
  if (!apps || apps.length === 0) {
    return (
      <View style={themed($container)}>
        <EmptyAppsView statusMessageKey="home:noActiveApps" activeAppsMessageKey="home:emptyActiveAppListInfo" />
      </View>
    )
  }

  const currentAnchorRef = selectedApp ? touchableRefs.current.get(selectedApp.packageName) : null

  return (
    <View style={themed($container)}>
      <FlatList
        data={paddedApps}
        renderItem={renderAppItem}
        keyExtractor={keyExtractor}
        numColumns={GRID_COLUMNS}
        columnWrapperStyle={themed($row)}
        scrollEnabled={false}
        contentContainerStyle={themed($gridContainer)}
        removeClippedSubviews={true}
        maxToRenderPerBatch={GRID_COLUMNS * 2}
        windowSize={5}
        initialNumToRender={GRID_COLUMNS * 3}
      />

      <AppPopover
        app={selectedApp}
        visible={popoverVisible}
        anchorRef={currentAnchorRef || null}
        onClose={handlePopoverClose}
        onCloseComplete={handlePopoverCloseComplete}
        onStartStop={handleStartStop}
        onOpenSettings={handleOpenSettings}
        onOpenWebView={handleOpenWebView}
        themed={themed}
        theme={theme}
      />
    </View>
  )
}

export const AppsGridView = memo(AppsGridViewRoot)

// Styles remain the same but with consistent sizing using constants
const $container: ThemedStyle<ViewStyle> = ({spacing}) => ({
  paddingHorizontal: spacing.sm,
})

const $gridContainer: ThemedStyle<ViewStyle> = () => ({})

const $row: ThemedStyle<ViewStyle> = () => ({
  justifyContent: "space-evenly",
})

const $gridItem: ThemedStyle<ViewStyle> = ({spacing}) => ({
  width: (SCREEN_WIDTH - (spacing.lg * 2 + spacing.md * 2)) / GRID_COLUMNS,
  alignItems: "center",
  marginBottom: spacing.lg,
})

const $appContainer: ThemedStyle<ViewStyle> = () => ({
  position: "relative",
})

const $appIcon: ThemedStyle<ViewStyle> = ({spacing}) => ({
  marginBottom: spacing.xs,
})

const $foregroundIndicator: ThemedStyle<ViewStyle> = ({colors, spacing}) => ({
  position: "absolute",
  left: -spacing.xxs,
  bottom: 0,
  width: spacing.lg,
  height: spacing.lg,
  justifyContent: "center",
  alignItems: "center",
  borderRadius: spacing.md,
  backgroundColor: colors.palette.primary400,
  borderWidth: 2,
  borderColor: colors.background,
})

const $offlineAppIndicator: ThemedStyle<ViewStyle> = ({colors, spacing}) => ({
  position: "absolute",
  right: -spacing.xxs,
  bottom: 0,
  width: spacing.lg,
  height: spacing.lg,
  justifyContent: "center",
  alignItems: "center",
  borderRadius: spacing.md,
  backgroundColor: colors.palette.secondary400,
  borderWidth: 2,
  borderColor: colors.background,
})

const $appName: ThemedStyle<TextStyle> = ({colors}) => ({
  fontSize: 11,
  fontWeight: "600",
  color: colors.text,
  textAlign: "center",
  lineHeight: 14,
})

const $appNameOffline: ThemedStyle<TextStyle> = ({colors}) => ({
  fontSize: 11,
  fontWeight: "600",
  color: colors.text,
  textAlign: "center",
  lineHeight: 14,
  textDecorationLine: "line-through",
})

const $popoverStyle: ThemedStyle<ViewStyle> = ({colors, spacing}) => ({
  backgroundColor: colors.background,
  borderRadius: spacing.sm,
  padding: 0,
  minWidth: 200,
})

const $popoverContent: ThemedStyle<ViewStyle> = () => ({})

const $popoverHeader: ThemedStyle<ViewStyle> = ({spacing}) => ({
  flexDirection: "row",
  alignItems: "center",
  padding: spacing.md,
})

const $popoverAppIcon: ThemedStyle<ViewStyle> = ({spacing}) => ({
  width: POPOVER_ICON_SIZE,
  height: POPOVER_ICON_SIZE,
  marginRight: spacing.sm,
  borderRadius: spacing.sm,
})

const $popoverAppName: ThemedStyle<TextStyle> = ({colors}) => ({
  fontSize: 16,
  fontWeight: "600",
  color: colors.text,
  flex: 1,
})

const $popoverDivider: ThemedStyle<ViewStyle> = ({colors}) => ({
  height: 1,
  backgroundColor: colors.separator,
})

const $popoverOption: ThemedStyle<ViewStyle> = ({spacing}) => ({
  flexDirection: "row",
  alignItems: "center",
  padding: spacing.md,
})

const $popoverOptionText: ThemedStyle<TextStyle> = ({colors, spacing}) => ({
  fontSize: 15,
  color: colors.text,
  marginLeft: spacing.md,
})

const $offlineBadge: ThemedStyle<ViewStyle> = ({colors, spacing}) => ({
  position: "absolute",
  right: -spacing.xxs,
  top: -spacing.xxs,
  backgroundColor: colors.background,
  borderRadius: spacing.sm,
  padding: 2,
})
