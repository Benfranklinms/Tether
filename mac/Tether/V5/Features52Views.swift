import AppKit
import SwiftUI
import TetherCore

// MARK: - Find my Mac

/// The phone asked the Mac to make itself found: a loud looping chime at full volume and a
/// full-screen Pure Mono "Found it" overlay on every display, until someone presses Found it
/// (or Esc / Return), or the phone stops it. The volume the user had is restored afterwards.
final class FindMacAlarm {
    static let shared = FindMacAlarm()
    private var windows: [NSWindow] = []
    private var sound: NSSound?
    private var savedVolume: Double?

    var isRinging: Bool { sound != nil }

    func start(model: AppModel) {
        guard sound == nil else { return }
        if !model.demo {
            savedVolume = MacMedia.volume()
            MacMedia.setVolume(1)
            let s = NSSound(data: AlarmTone.wav())
            s?.loops = true
            s?.volume = 1
            s?.play()
            sound = s
        }
        NSApp.activate(ignoringOtherApps: true)
        for screen in NSScreen.screens {
            let w = KeyableWindow(contentRect: screen.frame, styleMask: [.borderless], backing: .buffered, defer: false, screen: screen)
            w.level = .screenSaver
            w.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
            w.isOpaque = false
            w.backgroundColor = .clear
            w.isReleasedWhenClosed = false
            let host = NSHostingView(rootView: FindMacOverlay(phone: model.peer?.name ?? "your phone") { [weak model] in
                model?.foundMac()
            })
            HostingSizing.harden(host) // 0.5.6: the window is screen-sized; the view never resizes it
            w.contentView = host
            w.setFrame(screen.frame, display: true)
            w.makeKeyAndOrderFront(nil)
            windows.append(w)
        }
        Log.info("findmac.ring")
    }

    func stop() {
        sound?.stop()
        sound = nil
        if let v = savedVolume { MacMedia.setVolume(v) }
        savedVolume = nil
        let ws = windows
        windows = []
        // Let the overlay fade out first.
        for w in ws { w.animator().alphaValue = 0 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { ws.forEach { $0.orderOut(nil) } }
    }
}

private final class KeyableWindow: NSWindow {
    override var canBecomeKey: Bool { true }
}

struct FindMacOverlay: View {
    let phone: String
    let found: () -> Void
    @State private var shown = false
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        ZStack {
            Color.black.opacity(shown ? 0.94 : 0).ignoresSafeArea()
            VStack(spacing: 26) {
                ZStack {
                    ForEach(0..<3, id: \.self) { i in
                        Circle().stroke(Color.white.opacity(0.35), lineWidth: 1.5)
                            .frame(width: 120, height: 120)
                            .scaleEffect(pulse ? 2.4 : 1)
                            .opacity(pulse ? 0 : 0.8)
                            .animation(reduce ? nil : .easeOut(duration: 2).repeatForever(autoreverses: false).delay(Double(i) * 0.6),
                                       value: pulse)
                    }
                    Image(systemName: "laptopcomputer").font(.system(size: 52, weight: .light)).foregroundColor(.white)
                        .symbolRinging(!reduce)
                }
                .frame(height: 200)
                Text("This Mac is ringing").font(Typo.sans(48, .semibold)).foregroundColor(.white)
                    .lineLimit(1).minimumScaleFactor(0.6)
                Text("\(phone) is looking for it. Press Found it to stop the sound.").font(Typo.sans(16))
                    .foregroundColor(.white.opacity(0.6)).multilineTextAlignment(.center)
                Button(action: found) {
                    Text("Found it").font(Typo.sans(16, .semibold)).foregroundColor(.black)
                        .padding(.horizontal, 40).frame(height: 48)
                        .background(Capsule().fill(Color.white))
                }
                .buttonStyle(PressStyle())
                .keyboardShortcut(.defaultAction)
                Button("", action: found).keyboardShortcut(.cancelAction).opacity(0).frame(width: 0, height: 0)
            }
            .scaleEffect(shown || reduce ? 1 : 0.9)
            .opacity(shown ? 1 : 0)
        }
        .onAppear {
            withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.5, dampingFraction: 0.75)) { shown = true }
            pulse = true
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Your phone is looking for this Mac. Press Found it to stop the sound.")
    }
}

// MARK: - Insert from Phone and Instant Hotspot cards

/// Insert from Phone progress (popover + main window): the camera glyph pulses while the phone
/// has the camera open, morphs to a check when the picture is inserted.
struct CaptureCard: View {
    @EnvironmentObject var m: AppModel
    let session: CaptureSession
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var done: Bool { session.state == CaptureStates.done }
    private var failed: Bool { session.state == CaptureStates.failed }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(done ? Mono.accent : Mono.surfaceHigh).frame(width: 34, height: 34)
                Image(systemName: done ? "checkmark" : failed ? "exclamationmark"
                      : (session.kind == CaptureKinds.scan ? "doc.viewfinder" : "camera"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(done ? Mono.bg : Mono.text)
                    .symbolReplace()
                    .symbolBreathe(!done && !failed)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(session.kind == CaptureKinds.scan ? "SCAN DOCUMENT" : "TAKE PHOTO").font(Typo.mono(9, .medium)).tracking(0.8)
                    .foregroundColor(Mono.muted)
                Text(done || failed ? (session.reason ?? CaptureText.status(kind: session.kind, state: session.state, reason: nil))
                     : CaptureText.line(kind: session.kind, state: session.state, reason: session.reason))
                    .font(Typo.sans(12, .medium)).foregroundColor(Mono.text).lineLimit(2)
                    .id(session.state).transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            Spacer(minLength: 4)
            if !done {
                Button { m.cancelCapture() } label: {
                    Image(systemName: "xmark").font(.system(size: 9, weight: .semibold)).foregroundColor(Mono.muted)
                        .frame(width: 20, height: 20).background(Circle().fill(Mono.surfaceHigh))
                }
                .buttonStyle(PressStyle()).accessibilityLabel("Cancel")
            }
        }
        .padding(12)
        .tetherCard(highlighted: !failed)
        .clipped()
        .animation(Motion.bouncy(reduce), value: session.state)
    }
}

struct HotspotCard: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce

    private func line(_ h: HotspotSession) -> String {
        if h.joined { return "Connected to \(h.ssid ?? "your phone")" }
        if h.state == HotspotStates.failed { return h.reason ?? "Couldn't turn on the hotspot" }
        return HotspotText.status(h.state ?? "asking", reason: nil, joining: h.joining)
    }

    var body: some View {
        let h = m.v5.hotspot
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(h.joined ? Mono.accent : Mono.surfaceHigh).frame(width: 34, height: 34)
                Image(systemName: h.joined ? "checkmark" : h.state == HotspotStates.failed ? "exclamationmark" : "personalhotspot")
                    .font(.system(size: 14, weight: .semibold)).foregroundColor(h.joined ? Mono.bg : Mono.text)
                    .symbolReplace()
                    .symbolSweep(!h.joined && h.state != HotspotStates.failed)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("INSTANT HOTSPOT").font(Typo.mono(9, .medium)).tracking(0.8).foregroundColor(Mono.muted)
                Text(line(h))
                    .font(Typo.sans(12, .medium)).foregroundColor(Mono.text).lineLimit(2)
                    .id((h.state ?? "") + "\(h.joining)\(h.joined)")
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            Spacer(minLength: 4)
            if h.state == HotspotStates.failed {
                GhostButton(title: "Retry") { m.startHotspot() }
            }
            Button { m.dismissHotspot() } label: {
                Image(systemName: "xmark").font(.system(size: 9, weight: .semibold)).foregroundColor(Mono.muted)
                    .frame(width: 20, height: 20).background(Circle().fill(Mono.surfaceHigh))
            }
            .buttonStyle(PressStyle()).accessibilityLabel("Close")
        }
        .padding(12)
        .tetherCard(highlighted: h.joined)
        .clipped()
        .animation(Motion.bouncy(reduce), value: h)
    }
}
