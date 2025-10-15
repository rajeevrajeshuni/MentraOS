protocol SGCManager {
    // MARK: - Device Information

    var type: String { get }
    var ready: Bool { get }
    var glassesAppVersion: String? { get }
    var glassesBuildNumber: String? { get }
    var glassesDeviceModel: String? { get }
    var glassesAndroidVersion: String? { get }
    var glassesOtaVersionUrl: String? { get }
    var glassesSerialNumber: String? { get }
    var glassesStyle: String? { get }
    var glassesColor: String? { get }

    // MARK: - Hardware Status

    var hasMic: Bool { get }
    var batteryLevel: Int { get }
    var isHeadUp: Bool { get }

    // MARK: - Case Status

    var caseOpen: Bool { get }
    var caseRemoved: Bool { get }
    var caseCharging: Bool { get }
    var caseBatteryLevel: Int? { get }

    // MARK: - Network Status

    var wifiSsid: String? { get }
    var wifiConnected: Bool? { get }
    var wifiLocalIp: String? { get }
    var isHotspotEnabled: Bool? { get }
    var hotspotSsid: String? { get }
    var hotspotPassword: String? { get }
    var hotspotGatewayIp: String? { get }

    // MARK: - Audio Control

    func setMicEnabled(_ enabled: Bool)

    // MARK: - Messaging

    func sendJson(_ jsonOriginal: [String: Any], wakeUp: Bool, requireAck: Bool)

    // MARK: - Camera & Media

    func requestPhoto(_ requestId: String, appId: String, size: String?, webhookUrl: String?)
    func startRtmpStream(_ message: [String: Any])
    func stopRtmpStream()
    func sendRtmpKeepAlive(_ message: [String: Any])
    func startBufferRecording()
    func stopBufferRecording()
    func saveBufferVideo(requestId: String, durationSeconds: Int)
    func startVideoRecording(requestId: String, save: Bool)
    func stopVideoRecording(requestId: String)

    // MARK: - Button Settings

    func sendButtonPhotoSettings()
    func sendButtonModeSetting()
    func sendButtonVideoRecordingSettings()
    func sendButtonMaxRecordingTime(_ minutes: Int)
    func sendButtonCameraLedSetting()

    // MARK: - Display Control

    func setBrightness(_ level: Int, autoMode: Bool)
    func clearDisplay()
    func sendTextWall(_ text: String)
    func sendDoubleTextWall(_ top: String, _ bottom: String)
    func displayBitmap(base64ImageData: String) async -> Bool
    func showDashboard()
    func setDashboardPosition(_ height: Int, _ depth: Int)

    // MARK: - Device Control

    func setHeadUpAngle(_ angle: Int)
    func getBatteryStatus()
    func setSilentMode(_ enabled: Bool)
    func exit()

    // MARK: - Connection Management

    func disconnect()
    func forget()
    func findCompatibleDevices()
    func connectById(_ id: String)
    func getConnectedBluetoothName() -> String?

    // MARK: - Network Management

    func requestWifiScan()
    func sendWifiCredentials(_ ssid: String, _ password: String)
    func sendHotspotState(_ enabled: Bool)

    // MARK: - Gallery

    func queryGalleryStatus()
    func sendGalleryModeActive(_ active: Bool)
}

extension SGCManager {
    func sendJson(_ jsonOriginal: [String: Any], wakeUp: Bool) {
        sendJson(jsonOriginal, wakeUp: wakeUp, requireAck: true)
    }
}

//// template:
// var glassesBuildNumber = ""
// var glassesDeviceModel = ""
// var glassesAndroidVersion = ""
// var glassesOtaVersionUrl = ""
// var glassesSerialNumber = ""
// var glassesStyle = ""
// var glassesColor = ""
// var caseBatteryLevel = 0
// var glassesAppVersion = ""
//
//// Data Properties
// @Published var batteryLevel: Int = -1
// @Published var isCharging: Bool = false
// @Published var wifiConnected: Bool = false
// @Published var wifiSsid: String = ""
// @Published var wifiLocalIp: String = ""
// @Published var isHotspotEnabled: Bool = false
// @Published var hotspotSsid: String = ""
// @Published var hotspotPassword: String = ""
// @Published var hotspotGatewayIp: String = "" // The gateway IP to connect to when on hotspot
