//
//  Mach1.swift
//  MentraOS_Manager
//
//  Created by Mach1 Device Integration
//

import Combine
import CoreBluetooth
import Foundation
import React
import UIKit
import UltraliteSDK

class Mach1: UltraliteBaseViewController, SGCManager {
    func sendJson(_: [String: Any], wakeUp _: Bool, requireAck _: Bool) {}

    var caseBatteryLevel: Int?

    var glassesAppVersion: String?

    var glassesBuildNumber: String?

    var glassesDeviceModel: String?

    var glassesAndroidVersion: String?

    var glassesOtaVersionUrl: String?

    var glassesSerialNumber: String?

    var glassesStyle: String?

    var glassesColor: String?

    var wifiSsid: String?

    var wifiConnected: Bool?

    var wifiLocalIp: String?

    var isHotspotEnabled: Bool?

    var hotspotSsid: String?

    var hotspotPassword: String?

    var hotspotGatewayIp: String?

    func sendButtonPhotoSettings() {}

    func sendButtonModeSetting() {}

    func sendButtonVideoRecordingSettings() {}

    func sendButtonMaxRecordingTime(_: Int) {}

    func sendButtonCameraLedSetting() {}

    func exit() {}

    func requestWifiScan() {}

    func sendWifiCredentials(_: String, _: String) {}

    func sendHotspotState(_: Bool) {}

    func queryGalleryStatus() {}

    func sendGalleryModeActive(_: Bool) {}

    func showDashboard() {}

    func setDashboardPosition(_: Int, _: Int) {}

    func setSilentMode(_: Bool) {}

    func requestPhoto(_: String, appId _: String, size _: String?, webhookUrl _: String?) {}

    func sendJson(_: [String: Any]) {}

    func startRtmpStream(_: [String: Any]) {}

    func stopRtmpStream() {}

    func sendRtmpKeepAlive(_: [String: Any]) {}

    func startBufferRecording() {}

    func stopBufferRecording() {}

    func saveBufferVideo(requestId _: String, durationSeconds _: Int) {}

    func startVideoRecording(requestId _: String, save _: Bool) {}

    func stopVideoRecording(requestId _: String) {}

    func setHeadUpAngle(_: Int) {}

    func getBatteryStatus() {}

    func setBrightness(_: Int, autoMode _: Bool) {}

    let type = "Mentra Mach1"
    let hasMic: Bool = false
    var caseOpen = false
    var caseRemoved = true
    var caseCharging = false

    func setMicEnabled(_: Bool) {
        // N/A
    }

    var CONNECTING_DEVICE = ""
    var onConnectionStateChanged: (() -> Void)?
    @Published var batteryLevel: Int = -1
    @Published var isConnected: Bool = false
    var _ready = false
    var ready: Bool {
        get { return _ready }
        set {
            let oldValue = _ready
            _ready = newValue
            if oldValue != newValue {
                MentraManager.shared.handleConnectionStateChange()
            }
        }
    }

    // Store discovered peripherals by their identifier
    private var discoveredPeripherals: [String: CBPeripheral] = [:]

    private var textHandle: Int?
    private var tapTextHandle: Int?
    private var autoScroller: ScrollLayout.AutoScroller?
    private var currentLayout: Ultralite.Layout?
    private var isConnectedListener: BondListener<Bool>?
    private var batteryLevelListener: BondListener<Int>?
    private var setupDone: Bool = false
    @Published var isHeadUp = false

    func setup() {
        if setupDone { return }
        isConnectedListener = BondListener(listener: { [weak self] value in
            guard let self else { return }
            Bridge.log("MACH1: isConnectedListener: \(value)")

            if value {
                // Try to request control
                let gotControl = UltraliteManager.shared.currentDevice?.requestControl(
                    layout: UltraliteSDK.Ultralite.Layout.textBottomLeftAlign,
                    timeout: 0,
                    hideStatusBar: true,
                    showTapAnimation: true,
                    maxNumTaps: 3
                )

                Bridge.log("MACH1: gotControl: \(gotControl ?? false)")
                if batteryLevel != -1 {
                    ready = true
                }
            } else {
                ready = false
            }
        })

        batteryLevelListener = BondListener(listener: { [weak self] value in
            guard let self else { return }
            Bridge.log("MACH1: batteryLevelListener: \(value)")
            batteryLevel = value
            ready = true
        })

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTapEvent(_:)),
            name: .tap,
            object: nil
        )

        Bridge.log("MACH1: setup done")
        setupDone = true
    }

    // Handle the tap event
    @objc func handleTapEvent(_ notification: Notification) {
        guard let userInfo = notification.userInfo else {
            Bridge.log("MACH1: handleTapEvent: no userInfo")
            return
        }

        guard let tap = userInfo["tap"] else {
            Bridge.log("MACH1: handleTapEvent: no tap")
            return
        }

        let hack = "\(tap)"
        // get the number between the parentheses Optional(3)
        let tapNumber = hack.split(separator: "(").last?.split(separator: ")").first
        let tapNumberInt = Int(tapNumber ?? "0") ?? -1

        if tapNumberInt >= 2 {
            isHeadUp = !isHeadUp
            // start a timer and auto turn off the dashboard after 6 seconds:
            if isHeadUp {
                DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
                    if self.isHeadUp {
                        self.isHeadUp = false
                    }
                }
            }
        }
    }

    func linked(unk _: UltraliteSDK.Ultralite?) {
        Bridge.log("Mach1Manager: Linked")
        UltraliteManager.shared.currentDevice?.isConnected.bind(listener: isConnectedListener!)
        UltraliteManager.shared.currentDevice?.batteryLevel.bind(listener: batteryLevelListener!)
    }

    func connectById(_ id: String) {
        setup()
        let isLinked = UltraliteManager.shared.isLinked.value
        let currentDevice = UltraliteManager.shared.currentDevice
        let isConnected = isLinked && currentDevice != nil && currentDevice!.isPaired && currentDevice!.isConnected.value
        let peripheral = discoveredPeripherals[id] ?? currentDevice?.peripheral

        let gotControl = currentDevice?.requestControl(layout: UltraliteSDK.Ultralite.Layout.textBottomLeftAlign, timeout: 0, hideStatusBar: true, showTapAnimation: true, maxNumTaps: 3)

        Bridge.log("MACH1: gotControl: \(gotControl ?? false)")
        Bridge.log("MACH1: control is nil \(gotControl == nil)")

        UltraliteManager.shared.currentDevice?.isConnected.bind(listener: isConnectedListener!)
        UltraliteManager.shared.currentDevice?.batteryLevel.bind(listener: batteryLevelListener!)

        if isConnected {
            ready = true
            return
        }

        if !isLinked {
            if peripheral == nil {
                Bridge.log("Mach1Manager: No peripheral found or stored with ID: \(id)")
                CONNECTING_DEVICE = id
                UltraliteManager.shared.startScan(callback: foundDevice2)
                return
            }
            Bridge.log("Mach1Manager: Connecting to peripheral with ID: \(id)")
            UltraliteManager.shared.link(device: peripheral!, callback: linked)
            UltraliteManager.shared.currentDevice?.isConnected.bind(listener: isConnectedListener!)
            UltraliteManager.shared.currentDevice?.batteryLevel.bind(listener: batteryLevelListener!)
            return
        }
    }

    func clearDisplay() {
        guard let device = UltraliteManager.shared.currentDevice else {
            Bridge.log("Mach1Manager: No current device")
            ready = false
            return
        }

        if !device.isConnected.value {
            Bridge.log("Mach1Manager: Device not connected")
            ready = false
            return
        }

        device.screenOff()
    }

    func getConnectedBluetoothName() -> String? {
        UltraliteManager.shared.currentDevice?.peripheral?.name
    }

    func disconnect() {
        UltraliteManager.shared.stopScan()
        ready = false
    }

    func sendTextWall(_ text: String) {
        //    displayTextWall(text)
        guard let device = UltraliteManager.shared.currentDevice else {
            Bridge.log("Mach1Manager: No current device")
            ready = false
            return
        }

        if !device.isConnected.value {
            Bridge.log("Mach1Manager: Device not connected")
            ready = false
            return
        }

        Bridge.log("MACH1: Sending text: \(text)")

        device.sendText(text: text)
        device.canvas.commit()
    }

    func sendDoubleTextWall(_ topText: String, _ bottomText: String) {
        guard let device = UltraliteManager.shared.currentDevice else {
            Bridge.log("Mach1Manager: No current device")
            ready = false
            return
        }

        if !device.isConnected.value {
            Bridge.log("Mach1Manager: Device not connected")
            ready = false
            return
        }

        Bridge.log("MACH1: Sending double text wall - top: \(topText), bottom: \(bottomText)")

        // Clean the text (remove any special characters if needed)
        let cleanedTopText = topText
        let cleanedBottomText = bottomText

        // Count newlines in top text
        let newlineCount = cleanedTopText.filter { $0 == "\n" }.count

        // Calculate rows to add between top and bottom (3 minus existing newlines)
        let rowsTop = 3 - newlineCount

        // Build combined text
        var combinedText = cleanedTopText

        // Add empty lines between top and bottom
        for _ in 0 ..< rowsTop {
            combinedText += "\n"
        }

        // Add bottom text
        combinedText += cleanedBottomText

        // Send the combined text
        device.sendText(text: combinedText)
        device.canvas.commit()
    }

    func emitDiscoveredDevice(_ name: String) {
        // Use the standardized typed message function
        let body = [
            "compatible_glasses_search_result": [
                "model_name": "Mentra Mach1",
                "device_name": "\(name)",
                "device_address": "",
            ],
        ]
        Bridge.sendTypedMessage("compatible_glasses_search_result", body: body)
    }

    func foundDevice(_ device: CBPeripheral) {
        // log the found devices:
        Bridge.log(device.name ?? "Unknown Device")

        guard let name = device.name else { return }

        // just get the part inside the brackets
        let deviceName = name.split(separator: "[").last?.split(separator: "]").first

        guard let deviceName else { return }

        let id = String(deviceName)

        // Store the peripheral by its identifier
        discoveredPeripherals[id] = device
        emitDiscoveredDevice(id)
    }

    func foundDevice2(_ device: CBPeripheral) {
        guard let name = device.name else { return }

        // just get the part inside the brackets
        let deviceName = name.split(separator: "[").last?.split(separator: "]").first

        guard let deviceName else { return }

        let id = String(deviceName)

        discoveredPeripherals[id] = device

        if id == CONNECTING_DEVICE {
            connectById(id)
        }
    }

    func findCompatibleDevices() {
        setup()
        Bridge.log("@@@@@@@@@@@@@@@@@@@@@ FINDING COMPATIBLE DEVICES @@@@@@@@@@@@@@@@@@@@@@")
        UltraliteManager.shared.setBluetoothManger()
        let scanResult = UltraliteManager.shared.startScan(callback: foundDevice)
        Bridge.log("Mach1: \(scanResult)")
        if scanResult == UltraliteSDK.UltraliteManager.BluetoothScanResult.BLUETOOTH_PERMISSION_NEEDED {
            // call this function again in 5 seconds:
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                self.findCompatibleDevices()
            }
        }
    }

    func displayBitmap(base64ImageData: String) async -> Bool {
        guard let bmpData = Data(base64Encoded: base64ImageData) else {
            Bridge.log("MACH1: Failed to decode base64 image data")
            return false
        }

        Bridge.log("MACH1: ✅ Successfully decoded base64 image data to \(bmpData.count) bytes")

        // Convert data to UIImage
        guard let uiImage = UIImage(data: bmpData) else {
            Bridge.log("MACH1: Failed to create UIImage from data")
            return false
        }

        // Resize the image to 620x460
        let targetSize = CGSize(width: 620, height: 460)
        UIGraphicsBeginImageContextWithOptions(targetSize, false, 0.0)
        uiImage.draw(in: CGRect(origin: .zero, size: targetSize))
        let resizedImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()

        guard let resizedImage,
              let cgImage = resizedImage.cgImage
        else {
            Bridge.log("MACH1: Failed to resize image or get CGImage")
            return false
        }

        guard let device = UltraliteManager.shared.currentDevice else {
            Bridge.log("MACH1: No current device")
            MentraManager.shared.forgetSmartGlasses()
            return false
        }

        if !device.isConnected.value {
            Bridge.log("MACH1: Device not connected")
            return false
        }

        Bridge.log("MACH1: Sending bitmap")

        // Draw the background image at position (50, 80)
        //      device.canvas.drawBackground(image: cgImage, x: 50, y: 80)
        device.canvas.drawBackground(image: cgImage, x: 50, y: 80)
        device.canvas.commit()

        return true
    }

    func forget() {
        UltraliteManager.shared.unlink()
    }

    func setBrightness(_ brightness: Int) {
        guard let device = UltraliteManager.shared.currentDevice else {
            Bridge.log("Mach1Manager: No current device")
            ready = false
            return
        }

        device.setIntProperty(Ultralite.Property.brightness, value: Int64(brightness))
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        //    if let device = UltraliteManager.shared.currentDevice, device.isConnected.value == true {
        //      // we have a device and are connected
        //      draw()
        //    }
        //    else if UltraliteManager.shared.currentDevice != nil {
        //      //      // we have a device but it isn't connected
        //      //            isConnectedListener = BondListener(listener: { [weak self] value in
        //      //              if value {
        //      //                draw()
        //      //              }
        //      //            })
        //      //            UltraliteManager.shared.currentDevice?.isConnected.bind(listener: isConnectedListener!)
        //    }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)

        //    if UltraliteManager.shared.currentDevice == nil {
        //      // we have no device, show show the user the picker
        //      showPairingPicker()
        //    }
    }

    func draw() {
        //    guard let device = UltraliteManager.shared.currentDevice else {
        //      return
        //    }
        //
        //    // start control
        //    layout = .canvas
        //    startControl()
        //
        //    if let image = UIImage(systemName: "face.smiling")?.cgImage {
        //      // draw something to the screen
        //      device.canvas.drawBackground(image: image, x: 100, y: 100)
        //      // don't forget to commit, this is a common mistake.
        //      device.canvas.commit()
        //    }
    }

    override func onTapEvent(taps: Int) {
        Bridge.log("MACH1: Tap Event: \(taps)")
        //    draw()
    }
}
