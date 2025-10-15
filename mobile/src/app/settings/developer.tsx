import {useState} from "react"
import {View, ScrollView, TextInput} from "react-native"
import Icon from "react-native-vector-icons/MaterialCommunityIcons"
import bridge from "@/bridge/MantleBridge"
import showAlert from "@/utils/AlertUtils"
import {useAppTheme} from "@/utils/useAppTheme"
import {Header, Screen, PillButton, Text} from "@/components/ignite"
import RouteButton from "@/components/ui/RouteButton"
import {Spacer} from "@/components/misc/Spacer"
import ToggleSetting from "@/components/settings/ToggleSetting"
import {translate} from "@/i18n"
import {useNavigationHistory} from "@/contexts/NavigationHistoryContext"
import {spacing} from "@/theme"
import {glassesFeatures} from "@/config/glassesFeatures"
import {SETTINGS_KEYS, useSetting} from "@/stores/settings"

export default function DeveloperSettingsScreen() {
  // const {status} = useCoreStatus()
  const {theme} = useAppTheme()
  const {goBack, push} = useNavigationHistory()
  const {replace} = useNavigationHistory()
  const [customUrlInput, setCustomUrlInput] = useState("")
  const [isSavingUrl, setIsSavingUrl] = useState(false)
  const [defaultWearable, _setDefaultWearable] = useSetting(SETTINGS_KEYS.default_wearable)
  const [customBackendUrl, setCustomBackendUrl] = useSetting(SETTINGS_KEYS.custom_backend_url)
  const [powerSavingMode, setPowerSavingMode] = useSetting(SETTINGS_KEYS.power_saving_mode)
  const [reconnectOnAppForeground, setReconnectOnAppForeground] = useSetting(SETTINGS_KEYS.reconnect_on_app_foreground)
  const [newUi, setNewUi] = useSetting(SETTINGS_KEYS.new_ui)
  const [enableSquircles, setEnableSquircles] = useSetting(SETTINGS_KEYS.enable_squircles)

  // Triple-tap detection for Asia East button
  const [asiaButtonTapCount, setAsiaButtonTapCount] = useState(0)
  const [asiaButtonLastTapTime, setAsiaButtonLastTapTime] = useState(0)

  const toggleReconnectOnAppForeground = async () => {
    const newSetting = !reconnectOnAppForeground
    await setReconnectOnAppForeground(newSetting)
  }

  const toggleNewUi = async () => {
    const newSetting = !newUi
    await setNewUi(newSetting)
  }

  const toggleEnableSquircles = async () => {
    const newSetting = !enableSquircles
    await setEnableSquircles(newSetting)
  }

  // Modified handler for Custom URL
  const handleSaveUrl = async () => {
    const urlToTest = customUrlInput.trim().replace(/\/+$/, "")

    // Basic validation
    if (!urlToTest) {
      showAlert("Empty URL", "Please enter a URL or reset to default.", [{text: "OK"}])
      return
    }

    if (!urlToTest.startsWith("http://") && !urlToTest.startsWith("https://")) {
      showAlert("Invalid URL", "Please enter a valid URL starting with http:// or https://", [{text: "OK"}])
      return
    }

    setIsSavingUrl(true) // Start loading indicator

    try {
      // Test the URL by fetching the version endpoint
      const testUrl = `${urlToTest}/apps/version`
      console.log(`Testing URL: ${testUrl}`)

      // Create an AbortController for timeout
      const controller = new AbortController()
      const timeoutId = setTimeout(() => controller.abort(), 5000) // 5 second timeout

      try {
        const response = await fetch(testUrl, {
          method: "GET",
          signal: controller.signal,
        })

        clearTimeout(timeoutId) // Clear timeout if request completes

        // Check if the request was successful (status 200-299)
        if (response.ok) {
          const data = await response.json()
          console.log("URL Test Successful:", data)

          // Save the URL if the test passes
          await setCustomBackendUrl(urlToTest)
          await bridge.setServerUrl(urlToTest) // TODO: config: remove

          await showAlert(
            "Success",
            "Custom backend URL saved and verified. It will be used on the next connection attempt or app restart.",
            [
              {
                text: translate("common:ok"),
                onPress: () => {
                  replace("/init")
                },
              },
            ],
          )
        } else {
          // Handle non-2xx responses as errors
          console.error(`URL Test Failed: Status ${response.status}`)
          showAlert(
            "Verification Failed",
            `The server responded, but with status ${response.status}. Please check the URL and server status.`,
            [{text: "OK"}],
          )
        }
      } catch (fetchError: unknown) {
        clearTimeout(timeoutId) // Ensure timeout is cleared
        throw fetchError // Re-throw to be caught by outer try-catch
      }
    } catch (error: unknown) {
      // Handle network errors or timeouts
      console.error("URL Test Failed:", error instanceof Error ? error.message : "Unknown error")

      let errorMessage = "Could not connect to the specified URL. Please check the URL and your network connection."

      // Check if it's an abort error (timeout)
      if (error instanceof Error && error.name === "AbortError") {
        errorMessage = "Connection timed out. Please check the URL and server status."
      }
      // Check for network errors
      else if (error instanceof TypeError && error.message.includes("fetch")) {
        errorMessage = "Network error occurred. Please check your internet connection and the URL."
      }

      showAlert("Verification Failed", errorMessage, [{text: "OK"}])
    } finally {
      setIsSavingUrl(false) // Stop loading indicator
    }
  }

  const handleResetUrl = async () => {
    setCustomBackendUrl(null)
    await bridge.setServerUrl("") // TODO: config: remove
    setCustomUrlInput("")
    showAlert("Success", "Reset backend URL to default.", [
      {
        text: "OK",
        onPress: () => {
          replace("/init")
        },
      },
    ])
  }

  // Triple-tap handler for Asia East button
  const handleAsiaButtonPress = () => {
    const currentTime = Date.now()
    const timeDiff = currentTime - asiaButtonLastTapTime

    // Reset counter if more than 2 seconds has passed
    if (timeDiff > 2000) {
      setAsiaButtonTapCount(1)
    } else {
      setAsiaButtonTapCount(prev => prev + 1)
    }

    setAsiaButtonLastTapTime(currentTime)

    // Check for triple-tap
    if (asiaButtonTapCount + 1 >= 3) {
      setCustomUrlInput("https://devold.augmentos.org:443")
    } else {
      setCustomUrlInput("https://asiaeastapi.mentra.glass:443")
    }
  }

  return (
    <Screen preset="fixed" style={{paddingHorizontal: theme.spacing.md}}>
      <Header title="Developer Settings" leftIcon="caretLeft" onLeftPress={() => goBack()} />

      <View
        style={[
          styles.warningContainer,
          {
            backgroundColor: theme.colors.warningBackground,
            borderWidth: theme.spacing.xxxs,
            borderColor: theme.colors.palette.angry600,
          },
        ]}>
        <View style={styles.warningContent}>
          <Icon name="alert" size={16} color={theme.colors.text} />
          <Text tx="warning:warning" style={[styles.warningTitle, {color: theme.colors.text}]} />
        </View>
        <Text tx="warning:developerSettingsWarning" style={[styles.warningSubtitle, {color: theme.colors.text}]} />
      </View>

      <Spacer height={theme.spacing.md} />

      <ScrollView style={{flex: 1, marginHorizontal: -theme.spacing.md, paddingHorizontal: theme.spacing.md}}>
        <RouteButton
          label="🎥 Buffer Recording Debug"
          subtitle="Control 30-second video buffer on glasses"
          onPress={() => push("/settings/buffer-debug")}
        />

        <Spacer height={theme.spacing.md} />
        <ToggleSetting
          label={translate("settings:reconnectOnAppForeground")}
          subtitle={translate("settings:reconnectOnAppForegroundSubtitle")}
          value={reconnectOnAppForeground}
          onValueChange={toggleReconnectOnAppForeground}
        />

        <Spacer height={theme.spacing.md} />

        <ToggleSetting
          label={translate("settings:newUi")}
          subtitle={translate("settings:newUiSubtitle")}
          value={newUi}
          onValueChange={toggleNewUi}
        />

        <Spacer height={theme.spacing.md} />

        <ToggleSetting
          label="Enable Squircles"
          subtitle="Use iOS-style squircle app icons instead of circles"
          value={enableSquircles}
          onValueChange={toggleEnableSquircles}
        />
        <Spacer height={theme.spacing.md} />

        {/* G1 Specific Settings - Only show when connected to Even Realities G1 */}
        {defaultWearable && glassesFeatures[defaultWearable] && glassesFeatures[defaultWearable].powerSavingMode && (
          <>
            <Text style={[styles.sectionTitle, {color: theme.colors.textDim}]}>G1 Specific Settings</Text>
            <ToggleSetting
              label={translate("settings:powerSavingMode")}
              subtitle={translate("settings:powerSavingModeSubtitle")}
              value={powerSavingMode}
              onValueChange={async value => {
                await setPowerSavingMode(value)
                await bridge.sendTogglePowerSavingMode(value) // TODO: config: remove
              }}
            />
            <Spacer height={theme.spacing.md} />
          </>
        )}

        <View
          style={[
            styles.settingContainer,
            {
              backgroundColor: theme.colors.backgroundAlt,
              borderWidth: theme.spacing.xxxs,
              borderColor: theme.colors.border,
            },
          ]}>
          <View style={styles.settingTextContainer}>
            <Text style={[styles.label, {color: theme.colors.text}]}>Custom Backend URL</Text>
            <Text style={[styles.value, {color: theme.colors.textDim}]}>
              Override the default backend server URL. Leave blank to use default.
              {customBackendUrl && `\nCurrently using: ${customBackendUrl}`}
            </Text>
            <TextInput
              style={[
                styles.urlInput,
                {
                  backgroundColor: theme.colors.background,
                  borderColor: theme.colors.primary,
                  color: theme.colors.text,
                },
              ]}
              placeholder="e.g., http://192.168.1.100:7002"
              placeholderTextColor={theme.colors.textDim}
              value={customUrlInput}
              onChangeText={setCustomUrlInput}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              editable={!isSavingUrl}
            />
            <View style={styles.buttonRow}>
              <PillButton
                text={isSavingUrl ? "Testing..." : "Save & Test URL"}
                variant="primary"
                onPress={handleSaveUrl}
                disabled={isSavingUrl}
                buttonStyle={styles.saveButton}
              />
              <PillButton
                tx="common:reset"
                variant="icon"
                onPress={handleResetUrl}
                disabled={isSavingUrl}
                buttonStyle={styles.resetButton}
              />
            </View>
            <View style={styles.buttonColumn}>
              <PillButton
                tx="developer:global"
                variant="icon"
                onPress={() => setCustomUrlInput("https://api.mentra.glass:443")}
                buttonStyle={styles.button}
              />
              <PillButton
                tx="developer:dev"
                variant="icon"
                onPress={() => setCustomUrlInput("https://devapi.mentra.glass:443")}
                buttonStyle={styles.button}
              />
            </View>
            <View style={styles.buttonColumn}>
              <PillButton
                tx="developer:debug"
                variant="icon"
                onPress={() => setCustomUrlInput("https://debug.augmentos.cloud:443")}
                buttonStyle={styles.button}
              />
              <PillButton
                tx="developer:usCentral"
                variant="icon"
                onPress={() => setCustomUrlInput("https://uscentralapi.mentra.glass:443")}
                buttonStyle={styles.button}
              />
            </View>
            <View style={styles.buttonColumn}>
              <PillButton
                tx="developer:france"
                variant="icon"
                onPress={() => setCustomUrlInput("https://franceapi.mentra.glass:443")}
                buttonStyle={styles.button}
              />
              <PillButton
                tx="developer:asiaEast"
                variant="icon"
                onPress={handleAsiaButtonPress}
                buttonStyle={styles.button}
              />
            </View>
          </View>
        </View>

        <Spacer height={theme.spacing.md} />
        <Spacer height={theme.spacing.xxl} />
      </ScrollView>
    </Screen>
  )
}

const styles = {
  sectionTitle: {
    fontSize: 14,
    fontWeight: "600",
    marginBottom: 8,
    marginLeft: 4,
    textTransform: "uppercase",
    letterSpacing: 0.5,
  },
  warningContainer: {
    borderRadius: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  warningContent: {
    alignItems: "center",
    flexDirection: "row",
    marginBottom: 4,
  },
  warningTitle: {
    fontSize: 16,
    fontWeight: "bold",
    marginLeft: 6,
  },
  warningSubtitle: {
    fontSize: 14,
    marginLeft: 22,
  },
  settingContainer: {
    borderRadius: spacing.sm,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
  },
  button: {
    flexShrink: 1,
  },
  buttonColumn: {
    flexDirection: "row",
    gap: 12,
    justifyContent: "space-between",
    marginTop: 12,
  },
  settingTextContainer: {
    flex: 1,
  },
  label: {
    flexWrap: "wrap",
    fontSize: 16,
  },
  value: {
    flexWrap: "wrap",
    fontSize: 12,
    marginTop: 5,
  },
  // New styles for custom URL section
  urlInput: {
    borderWidth: 1,
    borderRadius: 12, // Consistent border radius
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    marginTop: 10,
    marginBottom: 10,
  },
  buttonRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginTop: 10,
  },
  saveButton: {
    flex: 1,
    marginRight: 10,
  },
  resetButton: {
    flex: 1,
  },
} as const
