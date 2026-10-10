import AppKit
import Combine
import SwiftUI
import TetherCore

/// 0.5.1: an incoming or ongoing call floats over every app and Space (top right, like a
/// Continuity banner) with Answer / Decline / Silence, then becomes a small live-call bar with
/// Mute and End. It shows whether Tether's windows are open, closed or behind other windows.
final class FloatingCallController {
    private weak var model: AppModel?
    private var panel: NSPanel?
    private var bag = Set<AnyCancellable>()
    /// Calls the user closed the bar for (stays closed for the rest of that call).
    private var dismissed: Set<String> = []

    init(model: AppModel) {
        self.model = model
        model.$call.combineLatest(model.$liveCall)
            .receive(on: RunLoop.main)
            .sink { [weak self] call, live in self?.update(call: call, live: live) }
            .store(in: &bag)
    }

    func dismiss(id: String) {
        dismissed.insert(id)
        hide()
    }

    private func update(call: CallStatePayload?, live: CallLivePayload?) {
        guard let m = model, m.settings.callAlerts, !m.demo else { return hide() }
        let id = live?.id ?? call?.id
        guard let id, !dismissed.contains(id) else {
            if call == nil && live == nil { dismissed.removeAll() }
            return hide()
        }
        show()
    }

    private func show() {
        guard let m = model else { return }
        if panel == nil {
            let size = NSSize(width: 360, height: 150)
            let p = NSPanel(contentRect: NSRect(origin: .zero, size: size), styleMask: [.nonactivatingPanel, .borderless],
                            backing: .buffered, defer: false)
            p.level = .statusBar
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
            p.isFloatingPanel = true
            p.hidesOnDeactivate = false
            p.isOpaque = false
            p.backgroundColor = .clear
            p.hasShadow = true
            p.isMovableByWindowBackground = true
            p.becomesKeyOnlyIfNeeded = true
            let root = FloatingCallView(onClose: { [weak self] id in self?.dismiss(id: id) })
                .environmentObject(m)
                .environment(\.tetherStyle, m.settings.appearance)
            let host = NSHostingView(rootView: root)
            host.frame = NSRect(origin: .zero, size: size)
            p.contentView = host
            panel = p
        }
        guard let p = panel else { return }
        let fit = p.contentView?.fittingSize ?? p.frame.size
        p.setContentSize(NSSize(width: 360, height: max(80, fit.height)))
        if let screen = NSScreen.main ?? NSScreen.screens.first {
            let v = screen.visibleFrame
            let o = CallBannerPlacement.origin(visible: (Double(v.minX), Double(v.minY), Double(v.width), Double(v.height)),
                                               size: (Double(p.frame.width), Double(p.frame.height)))
            p.setFrameOrigin(NSPoint(x: o.x, y: o.y))
        }
        if !p.isVisible { p.orderFrontRegardless() }
    }

    private func hide() {
        panel?.orderOut(nil)
    }
}

/// The banner's content: springs in from the top; incoming → live call.
struct FloatingCallView: View {
    @EnvironmentObject var m: AppModel
    var onClose: (String) -> Void
    @State private var shown = false
    @State private var ring = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Group {
            if let live = m.liveCall, live.isOffhook {
                liveBar(live)
            } else if let c = m.call {
                incoming(c)
            }
        }
        .padding(14)
        .frame(width: 360)
        .tetherBackground()
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Mono.line))
        .offset(y: shown || reduce ? 0 : -30)
        .opacity(shown ? 1 : 0)
        .scaleEffect(shown || reduce ? 1 : 0.96, anchor: .top)
        .onAppear {
            withAnimation(reduce ? .easeOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.78)) { shown = true }
            if !reduce { withAnimation(.easeOut(duration: 1.3).repeatForever(autoreverses: false)) { ring = true } }
        }
        .animation(Motion.spring(reduce), value: m.liveCall?.isOffhook)
    }

    private func incoming(_ c: CallStatePayload) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().stroke(Mono.text.opacity(0.35), lineWidth: 1).scaleEffect(ring ? 1.45 : 1).opacity(ring ? 0 : 1)
                    Image(systemName: "phone.fill").font(.system(size: 15)).foregroundColor(Mono.text).symbolPulse(!reduce)
                }
                .frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text(c.caller).font(Typo.sans(15, .semibold)).foregroundColor(Mono.text).lineLimit(1)
                    Text("Incoming call · \(m.peer?.name ?? "your phone")").font(Typo.sans(11)).foregroundColor(Mono.muted).lineLimit(1)
                }
                Spacer(minLength: 4)
                close(c.id)
            }
            HStack(spacing: 8) {
                MonoButton(title: "Silence", filled: false) { m.callAction(id: c.id, action: CallActions.silence) }
                MonoButton(title: "Decline", filled: false) { m.callAction(id: c.id, action: CallActions.decline) }
                if m.supports(Caps4.callControl) { MonoButton(title: "Answer") { m.answerCall(id: c.id) } }
            }
            if let note = m.callNote { Text(note).font(Typo.sans(11)).foregroundColor(Mono.muted).lineLimit(2) }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Incoming call from \(c.caller)")
    }

    private func liveBar(_ live: CallLivePayload) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "phone.fill").font(.system(size: 12)).foregroundColor(Mono.bg)
                .frame(width: 30, height: 30).background(Circle().fill(Mono.accent))
            VStack(alignment: .leading, spacing: 1) {
                Text(live.caller).font(Typo.sans(13, .semibold)).foregroundColor(Mono.text).lineLimit(1)
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    let now = Int64(ctx.date.timeIntervalSince1970 * 1000) + m.phoneClockSkew
                    Text(CallClock.elapsed(since: live.since, phoneNow: now)).font(Typo.mono(11)).foregroundColor(Mono.muted)
                        .rollingNumber()
                }
            }
            Spacer(minLength: 4)
            if live.canMute && !m.callMuteBlocked {
                Button { m.callControl(CallActions4.mute, on: !live.muted) } label: {
                    Image(systemName: live.muted ? "mic.slash.fill" : "mic.fill").font(.system(size: 12))
                        .foregroundColor(live.muted ? Mono.bg : Mono.text)
                        .frame(width: 30, height: 30).background(Circle().fill(live.muted ? Mono.accent : Mono.surfaceHigh))
                }
                .buttonStyle(PressStyle()).accessibilityLabel(live.muted ? "Unmute" : "Mute")
            }
            if live.canEnd {
                Button { m.callControl(CallActions4.end) } label: {
                    Text("End").font(Typo.sans(12, .semibold)).foregroundColor(Mono.bg)
                        .padding(.horizontal, 14).frame(height: 30).background(Capsule().fill(Mono.accent))
                }
                .buttonStyle(PressStyle()).accessibilityLabel("End call")
            }
            close(live.id)
        }
    }

    private func close(_ id: String) -> some View {
        Button { onClose(id) } label: {
            Image(systemName: "xmark").font(.system(size: 9, weight: .semibold)).foregroundColor(Mono.muted)
                .frame(width: 20, height: 20).background(Circle().fill(Mono.surfaceHigh))
        }
        .buttonStyle(PressStyle())
        .help("Hide for this call (it stays in Tether)")
        .accessibilityLabel("Hide call banner")
    }
}
