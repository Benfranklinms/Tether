import AppKit
import Foundation
import TetherCore

/// 0.2.0 features: notification actions, battery, find my phone, media, screen sharing.
extension AppModel {
    /// Open (or bring forward) one of the app's windows.
    func open(_ id: WindowID) {
        windows?.show(id)
    }

    func closeWindow(_ id: WindowID) {
        windows?.close(id)
    }

    func supports(_ cap: String) -> Bool { isConnected && peerCaps.contains(cap) }

    /// Reply / notification button from the menu or a banner.
    func performAction(key: String, action: String, text: String?) {
        guard !demo else { return }
        actionStatus[key] = "sending"
        engine?.notifAction(key: key, action: action, text: text)
        haptic()
    }

    func actionResult(key: String, ok: Bool, reason: String?) {
        if ok {
            actionStatus[key] = "sent"
        } else {
            let why: String
            switch reason {
            case "gone": why = "That notification is gone on your phone"
            case "disabled": why = "Replies are off on your phone"
            case "offline": why = "Not connected"
            case "empty": why = "Type a reply first"
            default: why = "Your phone couldn't do that"
            }
            actionStatus[key] = why
        }
        Log.info("notify.action_result", ["ok": "\(ok)", "reason": reason ?? "-"])
    }

    func batteryUpdate(_ b: BatteryPayload) {
        battery = b
        batteryUpdate5(b)
        if settings.lowBatteryAlert && lowBattery.update(level: b.level, charging: b.charging) {
            notifier.postLocal(id: "low-battery", title: "\(peer?.name ?? "Phone") battery is low",
                               body: "\(b.level)% left. Plug it in soon.", sound: true)
            Log.info("battery.low_alert", ["level": "\(b.level)"])
        }
    }

    func ring() {
        guard supports(Caps.ring) else { return toast(isConnected ? "Update Tether on your phone" : "Not connected") }
        ringRequested = true
        haptic()
        engine?.ring(true)
    }

    func stopRing() {
        ringRequested = false
        engine?.ring(false)
    }

    func ringUpdate(_ on: Bool, reason: String?) {
        ringing = on
        if !on {
            ringRequested = false
            switch reason {
            case "disabled": toast("Ringing is off on your phone")
            case "no_sound": toast("Your phone couldn't play a sound")
            default: break
            }
        }
    }

    func mediaReceived(_ r: TransferRecord) {
        guard let path = r.ref else { return }
        let what = r.kind == "photo" ? "Photo" : "Screenshot"
        notifier.postMedia(id: r.id, title: "\(what) from \(peer?.name ?? "your phone")", body: r.name,
                           file: URL(fileURLWithPath: path))
    }

    /// Received screenshots/photos, newest first (for the menu's recent strip).
    var recentMedia: [TransferRecord] {
        transfers.filter { $0.direction == .IN && PhoneKinds.isMedia($0.kind) && $0.status == .DONE && $0.ref != nil }.prefix(12).map { $0 }
    }

    func startScreen() {
        guard supports(Caps.screen) else {
            screen.phase = isConnected ? .unsupported : .ended("Not connected")
            return
        }
        guard onWifi else {
            screen.phase = .needsWifi
            return
        }
        screen.phase = .requesting
        screen.hasVideo = false
        engine?.startScreen(maxSize: settings.screenQuality)
    }

    func stopScreen() {
        guard screen.phase == .live || screen.phase == .consent || screen.phase == .requesting else { return }
        screen.phase = .ended("Stopped")
        screen.hasVideo = false
        engine?.stopScreen()
    }

    func screenInput(_ input: ScreenInputPayload) {
        guard screen.phase == .live, screen.control, settings.screenControl else { return }
        engine?.screenInput(input)
    }

    func screenUpdate(_ st: ScreenStatePayload) {
        switch st.state {
        case ScreenStates.consent: screen.phase = .consent
        case ScreenStates.live:
            if st.w > 0 && st.h > 0 { screen.size = CGSize(width: st.w, height: st.h) }
            screen.rotation = st.rotation
            screen.control = st.control && settings.screenControl
            screen.phase = .live
        case ScreenStates.needsWifi: screen.phase = .needsWifi
        case ScreenStates.denied:
            screen.phase = .denied(st.reason == "disabled" ? "Screen sharing is off on your phone (Tether › Settings › On your Mac)"
                                   : "Your phone declined screen sharing")
        case "ended": screen.phase = .ended(st.reason ?? "Stopped")
        default:
            let why: String
            switch st.reason {
            case "wifi": why = "Stopped: the Wi-Fi link dropped"
            case "phone": why = "Stopped on your phone"
            case "link": why = "Stopped: your phone disconnected"
            default: why = st.state == ScreenStates.error ? "Your phone couldn't capture the screen" : "Screen sharing ended"
            }
            screen.phase = .ended(why)
        }
        if screen.phase != .live { screen.hasVideo = false }
        Log.info("screen.state", ["state": st.state, "reason": st.reason ?? "-", "w": "\(st.w)", "h": "\(st.h)"])
    }

    func chooseMediaFolder() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = "Use folder"
        if panel.runModal() == .OK, let url = panel.url { settings.mediaFolder = url.path }
    }

    /// Trackpad feedback for deliberate actions (no-op on Macs without Force Touch).
    func haptic() {
        NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now)
    }
}
