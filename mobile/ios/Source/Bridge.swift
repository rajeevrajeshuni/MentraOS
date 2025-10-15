//
//  Bridge.swift
//  AOS
//
//  Created by Matthew Fosse on 3/4/25.
//

import Foundation
import React

// Use BridgeModule to emit events
// has commands for the core to use to send messages to the mantle
// also has a handleCommand function for the core / bridge module to use to
// communicate with the rest of the core
@objc(Bridge)
class Bridge: RCTEventEmitter {
    override init() {
        super.init()
    }

    @objc
    override static func requiresMainQueueSetup() -> Bool {
        return false
    }

    static func log(_ message: String) {
        let msg = "SWIFT:\(message)"
        BridgeModule.emitEvent(withName: "CoreMessageEvent", body: msg)
    }

    static func sendEvent(withName: String, body: String) {
        BridgeModule.emitEvent(withName: withName, body: body)
    }

    static func showBanner(type: String, message: String) {
        let data = ["type": type, "message": message] as [String: Any]
        Bridge.sendTypedMessage("show_banner", body: data)
    }

    static func sendHeadUp(_ isUp: Bool) {
        let data = ["position": isUp]
        Bridge.sendTypedMessage("head_up", body: data)
    }

    static func sendPairFailureEvent(_ error: String) {
        let data = ["error": error]
        Bridge.sendTypedMessage("pair_failure", body: data)
    }

    static func sendMicData(_ data: Data) {
        let base64String = data.base64EncodedString()
        let body = ["base64": base64String]
        Bridge.sendTypedMessage("mic_data", body: body)
    }

    static func saveSetting(_ key: String, _ value: Any) {
        let body = ["key": key, "value": value]
        Bridge.sendTypedMessage("save_setting", body: body)
    }

    static func sendVadStatus(_ isSpeaking: Bool) {
        let vadMsg: [String: Any] = [
            "type": "VAD",
            "status": isSpeaking,
        ]

        let jsonData = try! JSONSerialization.data(withJSONObject: vadMsg)
        if let jsonString = String(data: jsonData, encoding: .utf8) {
            Bridge.sendWSText(jsonString)
        }
    }

    static func sendBatteryStatus(level: Int, charging: Bool) {
        let vadMsg: [String: Any] = [
            "type": "glasses_battery_update",
            "level": level,
            "charging": charging,
            "timestamp": Date().timeIntervalSince1970 * 1000,
            // TODO: time remaining
        ]

        let jsonData = try! JSONSerialization.data(withJSONObject: vadMsg)
        if let jsonString = String(data: jsonData, encoding: .utf8) {
            Bridge.sendWSText(jsonString)
        }
    }

    static func sendLocationUpdate(lat: Double, lng: Double, accuracy: Double?, correlationId: String?) {
        do {
            var event: [String: Any] = [
                "type": "location_update",
                "lat": lat,
                "lng": lng,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ]

            if let acc = accuracy {
                event["accuracy"] = acc
            }

            if let corrId = correlationId {
                event["correlationId"] = corrId
            }

            let jsonData = try JSONSerialization.data(withJSONObject: event)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error building location_update JSON: \(error)")
        }
    }

    static func sendGlassesConnectionState(modelName: String, status: String) {
        do {
            let event: [String: Any] = [
                "type": "glasses_connection_state",
                "modelName": modelName,
                "status": status,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ]
            let jsonData = try JSONSerialization.data(withJSONObject: event)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error building location_update JSON: \(error)")
        }
    }

    static func updateAsrConfig(languages: [[String: Any]]) {
        do {
            let configMsg: [String: Any] = [
                "type": "config",
                "streams": languages,
            ]

            let jsonData = try JSONSerialization.data(withJSONObject: configMsg)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error building config message: \(error)")
        }
    }

    func sendCoreStatus(status: [String: Any]) {
        do {
            let event: [String: Any] = [
                "type": "core_status_update",
                "status": ["status": status],
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ]

            let jsonData = try JSONSerialization.data(withJSONObject: event)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error building core_status_update JSON: \(error)")
        }
    }

    func sendAudioPlayResponse(requestId: String, success: Bool, error: String? = nil, duration: Double? = nil) {
        Bridge.log("ServerComms: Sending audio play response - requestId: \(requestId), success: \(success), error: \(error ?? "none")")
        let message: [String: Any] = [
            "type": "audio_play_response",
            "requestId": requestId,
            "success": success,
            "error": error as Any,
            "duration": duration as Any,
        ].compactMapValues { $0 }

        do {
            let jsonData = try JSONSerialization.data(withJSONObject: message)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
                Bridge.log("ServerComms: Sent audio play response to server")
            }
        } catch {
            Bridge.log("ServerComms: Failed to serialize audio play response: \(error)")
        }
    }

    // MARK: - App Lifecycle

    func startApp(packageName: String) {
        do {
            let msg: [String: Any] = [
                "type": "start_app",
                "packageName": packageName,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ]

            let jsonData = try JSONSerialization.data(withJSONObject: msg)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error building start_app JSON: \(error)")
        }
    }

    func stopApp(packageName: String) {
        do {
            let msg: [String: Any] = [
                "type": "stop_app",
                "packageName": packageName,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ]

            let jsonData = try JSONSerialization.data(withJSONObject: msg)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error building stop_app JSON: \(error)")
        }
    }

    // MARK: - Hardware Events

    static func sendButtonPress(buttonId: String, pressType: String) {
        // Send as typed message so it gets handled locally by MantleBridge.tsx
        // This allows the React Native layer to process it before forwarding to server
        let body: [String: Any] = [
            "buttonId": buttonId,
            "pressType": pressType,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000),
        ]
        Bridge.sendTypedMessage("button_press", body: body)
    }

    static func sendPhotoResponse(requestId: String, photoUrl: String) {
        do {
            let event: [String: Any] = [
                "type": "photo_response",
                "requestId": requestId,
                "photoUrl": photoUrl,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ]

            let jsonData = try JSONSerialization.data(withJSONObject: event)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error building photo_response JSON: \(error)")
        }
    }

    static func sendVideoStreamResponse(appId: String, streamUrl: String) {
        do {
            let event: [String: Any] = [
                "type": "video_stream_response",
                "appId": appId,
                "streamUrl": streamUrl,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ]

            let jsonData = try JSONSerialization.data(withJSONObject: event)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error building video_stream_response JSON: \(error)")
        }
    }

    static func sendHeadPosition(isUp: Bool) {
        do {
            let event: [String: Any] = [
                "type": "head_position",
                "position": isUp ? "up" : "down",
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ]

            let jsonData = try JSONSerialization.data(withJSONObject: event)
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                Bridge.sendWSText(jsonString)
            }
        } catch {
            Bridge.log("ServerComms: Error sending head position: \(error)")
        }
    }

    /**
     * Send transcription result to server
     * Used by AOSManager to send pre-formatted transcription results
     * Matches the Java ServerComms structure exactly
     */
    static func sendLocalTranscription(transcription: [String: Any]) {
        guard let text = transcription["text"] as? String, !text.isEmpty else {
            Bridge.log("Skipping empty transcription result")
            return
        }

        Bridge.sendTypedMessage("local_transcription", body: transcription)
    }

    // core bridge funcs:

    static func sendStatus(_ statusObj: [String: Any]) {
        let body = ["status": statusObj]
        Bridge.sendTypedMessage("status", body: body)
    }

    static func sendGlassesSerialNumber(_ serialNumber: String, style: String, color: String) {
        let body = [
            "glasses_serial_number": [
                "serial_number": serialNumber,
                "style": style,
                "color": color,
            ],
        ]
        Bridge.sendTypedMessage("glasses_serial_number", body: body)
    }

    override func supportedEvents() -> [String] {
        // don't add to this list, use a typed message instead
        return ["CoreMessageEvent", "WIFI_SCAN_RESULTS"]
    }

    // Arbitrary WS Comms (dont use these, make a dedicated function for your use case):
    static func sendWSText(_ msg: String) {
        let data = ["text": msg]
        Bridge.sendTypedMessage("ws_text", body: data)
    }

    static func sendWSBinary(_ data: Data) {
        let base64String = data.base64EncodedString()
        let body = ["base64": base64String]
        Bridge.sendTypedMessage("ws_bin", body: body)
    }

    // don't call this function directly, instead
    // make a function above that calls this function:
    static func sendTypedMessage(_ type: String, body: [String: Any]) {
        var body = body
        body["type"] = type
        let jsonData = try! JSONSerialization.data(withJSONObject: body)
        let jsonString = String(data: jsonData, encoding: .utf8)
        BridgeModule.emitEvent(withName: "CoreMessageEvent", body: jsonString!)
    }

    // handle commands from the mantle:
    @objc static func handleCommand(_ command: String) -> Any {
        // Bridge.log("CommandBridge: Received command: \(command)")
        let m = MentraManager.shared

        // Define command types enum
        enum CommandType: String {
            case request_status
            case connect_wearable
            case disconnect_wearable
            case search_for_compatible_device_names
            case ping
            case forget_smart_glasses
            case toggle_updating_screen
            case show_dashboard
            case request_wifi_scan
            case send_wifi_credentials
            case set_hotspot_state
            case query_gallery_status
            case send_gallery_mode_active
            case photo_request
            case start_buffer_recording
            case stop_buffer_recording
            case save_buffer_video
            case start_video_recording
            case stop_video_recording
            case start_rtmp_stream
            case stop_rtmp_stream
            case keep_rtmp_stream_alive
            case set_auth_secret_key
            case set_stt_model_details
            case get_stt_model_path
            case check_stt_model_available
            case validate_stt_model
            case extract_tar_bz2
            case display_event
            case display_text
            case update_settings
            case set_button_photo_size
            case set_button_video_settings
            case set_button_max_recording_time
            case set_button_camera_led
            case microphone_state_change
            case restart_transcriber
            case unknown
        }

        // Try to parse JSON
        guard let data = command.data(using: .utf8) else {
            Bridge.log("CommandBridge: Could not convert command string to data")
            return 0
        }

        do {
            if let jsonDict = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] {
                // Extract command type
                guard let commandString = jsonDict["command"] as? String else {
                    Bridge.log("CommandBridge: Invalid command format: missing 'command' field")
                    return 0
                }

                let commandType = CommandType(rawValue: commandString) ?? .unknown
                let params = jsonDict["params"] as? [String: Any]

                // Process based on command type
                switch commandType {
                case .set_auth_secret_key:
                    guard let params = params,
                          let userId = params["userId"] as? String,
                          let authSecretKey = params["authSecretKey"] as? String
                    else {
                        Bridge.log("CommandBridge: set_auth_secret_key invalid params")
                        break
                    }
                    m.setAuthCreds(authSecretKey, userId)
                case .display_event:
                    guard let params else {
                        Bridge.log("CommandBridge: display_event invalid params")
                        break
                    }
                    m.handle_display_event(params)
                case .display_text:
                    guard let params else {
                        Bridge.log("CommandBridge: display_text invalid params")
                        break
                    }
                    m.handle_display_text(params)
                case .request_status:
                    m.handle_request_status()
                case .connect_wearable:
                    guard let params = params, let modelName = params["model_name"] as? String,
                          let deviceName = params["device_name"] as? String
                    else {
                        Bridge.log("CommandBridge: connect_wearable invalid params")
                        m.handle_connect_wearable("")
                        break
                    }
                    m.handle_connect_wearable(deviceName, modelName: modelName)
                case .disconnect_wearable:
                    m.disconnectWearable()
                case .forget_smart_glasses:
                    m.forgetSmartGlasses()
                case .search_for_compatible_device_names:
                    guard let params = params, let modelName = params["model_name"] as? String else {
                        Bridge.log("CommandBridge: search_for_compatible_device_names invalid params")
                        break
                    }
                    m.handleSearchForCompatibleDeviceNames(modelName)
                case .show_dashboard:
                    m.showDashboard()
                case .toggle_updating_screen:
                    guard let params = params, let enabled = params["enabled"] as? Bool else {
                        Bridge.log("CommandBridge: toggle_updating_screen invalid params")
                        break
                    }
                    m.toggleUpdatingScreen(enabled)
                case .request_wifi_scan:
                    m.requestWifiScan()
                case .send_wifi_credentials:
                    guard let params = params, let ssid = params["ssid"] as? String,
                          let password = params["password"] as? String
                    else {
                        Bridge.log("CommandBridge: send_wifi_credentials invalid params")
                        break
                    }
                    m.sendWifiCredentials(ssid, password)
                case .set_hotspot_state:
                    guard let params = params, let enabled = params["enabled"] as? Bool else {
                        Bridge.log("CommandBridge: set_hotspot_state invalid params")
                        break
                    }
                    m.setGlassesHotspotState(enabled)
                case .query_gallery_status:
                    Bridge.log("CommandBridge: Querying gallery status")
                    m.queryGalleryStatus()
                case .send_gallery_mode_active:
                    guard let params = params, let active = params["active"] as? Bool else {
                        Bridge.log("CommandBridge: send_gallery_mode_active invalid params")
                        break
                    }
                    Bridge.log("CommandBridge: Sending gallery mode active: \(active)")
                    m.sendGalleryModeActive(active)
                case .photo_request:
                    guard let params = params,
                          let requestId = params["requestId"] as? String,
                          let appId = params["appId"] as? String,
                          let size = params["size"] as? String
                    else {
                        Bridge.log("CommandBridge: photo_request invalid params")
                        break
                    }
                    m.handle_photo_request(requestId, appId, size, params["webhookUrl"] as? String)
                case .start_buffer_recording:
                    Bridge.log("CommandBridge: Starting buffer recording")
                    m.handle_start_buffer_recording()
                case .stop_buffer_recording:
                    Bridge.log("CommandBridge: Stopping buffer recording")
                    m.handle_stop_buffer_recording()
                case .save_buffer_video:
                    guard let params = params,
                          let requestId = params["request_id"] as? String,
                          let durationSeconds = params["duration_seconds"] as? Int
                    else {
                        Bridge.log("CommandBridge: save_buffer_video invalid params")
                        break
                    }
                    Bridge.log("CommandBridge: Saving buffer video: requestId=\(requestId), duration=\(durationSeconds)s")
                    m.handle_save_buffer_video(requestId, durationSeconds)
                case .start_video_recording:
                    guard let params = params,
                          let requestId = params["request_id"] as? String,
                          let save = params["save"] as? Bool
                    else {
                        Bridge.log("CommandBridge: start_video_recording invalid params")
                        break
                    }
                    Bridge.log("CommandBridge: Starting video recording: requestId=\(requestId), save=\(save)")
                    m.handle_start_video_recording(requestId, save)
                case .stop_video_recording:
                    guard let params = params,
                          let requestId = params["request_id"] as? String
                    else {
                        Bridge.log("CommandBridge: stop_video_recording invalid params")
                        break
                    }
                    Bridge.log("CommandBridge: Stopping video recording: requestId=\(requestId)")
                    m.stopVideoRecording(requestId: requestId)
                case .start_rtmp_stream:
                    guard let params = params else {
                        Bridge.log("CommandBridge: start_rtmp_stream invalid params")
                        break
                    }
                    Bridge.log("CommandBridge: Starting RTMP stream")
                    m.onRtmpStreamStartRequest(params)
                case .stop_rtmp_stream:
                    Bridge.log("CommandBridge: Stopping RTMP stream")
                    m.onRtmpStreamStop()
                case .keep_rtmp_stream_alive:
                    guard let params = params else {
                        Bridge.log("CommandBridge: keep_rtmp_stream_alive invalid params")
                        break
                    }
                    Bridge.log("CommandBridge: RTMP stream keep alive")
                    m.onRtmpStreamKeepAlive(params)
                case .unknown:
                    Bridge.log("CommandBridge: Unknown command type: \(commandString)")
                    m.handle_request_status()
                case .ping:
                    break
                case .microphone_state_change:
                    guard let msg = params else {
                        Bridge.log("CommandBridge: microphone_state_change invalid params")
                        break
                    }

                    let bypassVad = msg["bypassVad"] as? Bool ?? false
                    var requiredDataStrings: [String] = []
                    if let requiredDataArray = msg["requiredData"] as? [String] {
                        requiredDataStrings = requiredDataArray
                    } else if let requiredDataArray = msg["requiredData"] as? [Any] {
                        // Handle case where it might come as mixed array
                        requiredDataStrings = requiredDataArray.compactMap { $0 as? String }
                    }
                    // Convert string array to enum array
                    var requiredData = SpeechRequiredDataType.fromStringArray(requiredDataStrings)
                    Bridge.log("ServerComms: requiredData = \(requiredDataStrings), bypassVad = \(bypassVad)")
                    m.handle_microphone_state_change(requiredData, bypassVad)
                case .update_settings:
                    guard let params else {
                        Bridge.log("CommandBridge: update_settings invalid params")
                        break
                    }
                    m.handle_update_settings(params)
                // Button settings:
                case .set_button_photo_size:
                    guard let params = params,
                          let size = params["size"] as? String
                    else {
                        Bridge.log("CommandBridge: set_button_photo_size invalid params")
                        break
                    }
                    m.setButtonPhotoSize(size)
                case .set_button_video_settings:
                    guard let params = params,
                          let width = params["width"] as? Int,
                          let height = params["height"] as? Int,
                          let fps = params["fps"] as? Int
                    else {
                        Bridge.log("CommandBridge: set_button_video_settings invalid params")
                        break
                    }
                    m.setButtonVideoSettings(width: width, height: height, fps: fps)
                case .set_button_max_recording_time:
                    guard let params = params,
                          let minutes = params["minutes"] as? Int
                    else {
                        Bridge.log("CommandBridge: set_button_max_recording_time invalid params")
                        break
                    }
                    m.setButtonMaxRecordingTime(minutes)
                case .set_button_camera_led:
                    guard let params = params,
                          let enabled = params["enabled"] as? Bool
                    else {
                        Bridge.log("CommandBridge: set_button_camera_led invalid params")
                        break
                    }
                    m.setButtonCameraLed(enabled)
                // STT:
                case .set_stt_model_details:
                    guard let params = params,
                          let path = params["path"] as? String,
                          let languageCode = params["languageCode"] as? String
                    else {
                        Bridge.log("CommandBridge: set_stt_model_details invalid params")
                        break
                    }
                    STTTools.setSttModelDetails(path, languageCode)
                case .get_stt_model_path:
                    return STTTools.getSttModelPath()
                case .check_stt_model_available:
                    return STTTools.checkSTTModelAvailable()
                case .validate_stt_model:
                    guard let params = params,
                          let path = params["path"] as? String
                    else {
                        Bridge.log("CommandBridge: validate_stt_model invalid params")
                        break
                    }
                    return STTTools.validateSTTModel(path)
                case .extract_tar_bz2:
                    guard let params = params,
                          let sourcePath = params["source_path"] as? String,
                          let destinationPath = params["destination_path"] as? String
                    else {
                        Bridge.log("CommandBridge: extract_tar_bz2 invalid params")
                        break
                    }
                    return STTTools.extractTarBz2(sourcePath: sourcePath, destinationPath: destinationPath)
                case .restart_transcriber:
                    m.restartTranscriber()
                }
            }
        } catch {
            Bridge.log("CommandBridge: Error parsing JSON command: \(error.localizedDescription)")
        }
        return 0
    }
}
