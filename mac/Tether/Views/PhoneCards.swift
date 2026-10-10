import SwiftUI
import TetherCore

/// Incoming call in the menu-bar panel: caller, Decline, Silence (0.3.0).
struct CallBanner: View {
    @EnvironmentObject var m: AppModel
    let call: CallStatePayload
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().stroke(Mono.text.opacity(0.35), lineWidth: 1)
                        .scaleEffect(pulse && !reduce ? 1.35 : 1).opacity(pulse && !reduce ? 0 : 1)
                    Image(systemName: "phone.fill").font(.system(size: 14)).foregroundColor(Mono.text)
                }
                .frame(width: 34, height: 34)
                VStack(alignment: .leading, spacing: 2) {
                    Text(call.caller).font(Typo.sans(15, .semibold)).foregroundColor(Mono.text).lineLimit(1)
                    Text(subtitle).font(Typo.sans(11)).foregroundColor(Mono.muted).lineLimit(1)
                }
                Spacer()
            }
            HStack(spacing: 8) {
                MonoButton(title: "Silence", filled: false) { m.callAction(id: call.id, action: CallActions.silence) }
                MonoButton(title: "Decline", filled: !canAnswer) { m.callAction(id: call.id, action: CallActions.decline) }
                if canAnswer {
                    MonoButton(title: "Answer") { m.answerCall(id: call.id) }
                        .help(m.settings.speakerOnAnswer ? "Answers on your phone's speaker" : "Answers on your phone")
                }
            }
            if let note = m.callNote {
                Text(note).font(Typo.sans(11)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .tetherCard(highlighted: true)
        .onAppear {
            guard !reduce else { return }
            withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { pulse = true }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Incoming call from \(call.caller)")
    }

    /// A 0.4 phone with answer access (the live card says so; before it arrives, the caps do).
    private var canAnswer: Bool {
        guard m.supports(Caps4.callControl) else { return false }
        if let l = m.liveCall, l.id == call.id { return l.canAnswer }
        return true
    }

    private var subtitle: String {
        if let n = call.number, n != call.caller { return "Incoming call · \(n)" }
        return "Incoming call on \(m.peer?.name ?? "your phone")"
    }
}

/// What's playing on the phone, with transport, seek and volume (0.3.0).
struct NowPlayingCard: View {
    @EnvironmentObject var m: AppModel
    let np: NowPlayingPayload
    @State private var scrubbing: Double?
    @State private var volume: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                art
                VStack(alignment: .leading, spacing: 2) {
                    Text(np.title.isEmpty ? "Playing" : np.title).font(Typo.sans(13, .semibold)).foregroundColor(Mono.text).lineLimit(1)
                    Text([np.artist, np.app].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(Typo.sans(11)).foregroundColor(Mono.muted).lineLimit(1)
                }
                Spacer(minLength: 4)
                transport
                PlayerSizeToggle(compact: false)
            }
            if np.durationMs > 0 { progress }
            if np.volume != nil { volumeRow }
        }
        .padding(12)
        .tetherCard()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Now playing on your phone: \(np.title), \(np.artist)")
    }

    private var art: some View {
        Group {
            if let img = m.artwork {
                Image(nsImage: img).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note").font(.system(size: 16)).foregroundColor(Mono.muted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(Mono.surfaceHigh)
            }
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Mono.line))
    }

    private var transport: some View {
        HStack(spacing: 4) {
            icon("backward.fill", "Previous", enabled: np.canPrev) { m.media(MediaCmds.prev) }
            icon(np.playing ? "pause.fill" : "play.fill", np.playing ? "Pause" : "Play", enabled: true, big: true) {
                m.media(MediaCmds.toggle)
            }
            icon("forward.fill", "Next", enabled: np.canNext) { m.media(MediaCmds.next) }
        }
    }

    private func icon(_ symbol: String, _ label: String, enabled: Bool, big: Bool = false,
                      _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: big ? 15 : 11)).foregroundColor(enabled ? Mono.text : Mono.muted)
                .frame(width: big ? 32 : 26, height: big ? 32 : 26)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle()).disabled(!enabled).help(label).accessibilityLabel(label)
    }

    private var progress: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
            let pos = scrubbing ?? Double(m.nowPlayingPosition(at: ctx.date))
            let dur = Double(np.durationMs)
            VStack(spacing: 3) {
                Slider(value: Binding(get: { min(pos, dur) }, set: { scrubbing = $0 }), in: 0...max(dur, 1)) { editing in
                    if !editing, let s = scrubbing {
                        m.media(MediaCmds.seek, pos: Int64(s))
                        scrubbing = nil
                    }
                }
                .controlSize(.mini).tint(Mono.text).disabled(!np.canSeek)
                .accessibilityLabel("Position")
                HStack {
                    Text(Self.time(pos)).font(Typo.mono(9)).foregroundColor(Mono.muted)
                    Spacer()
                    Text("-" + Self.time(max(0, dur - pos))).font(Typo.mono(9)).foregroundColor(Mono.muted)
                }
            }
        }
    }

    private var volumeRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "speaker.fill").font(.system(size: 9)).foregroundColor(Mono.muted)
            Slider(value: Binding(get: { volume ?? Double(np.volume ?? 0) }, set: { volume = $0 }), in: 0...1) { editing in
                if !editing, let v = volume {
                    m.media(MediaCmds.volume, vol: Float(v))
                    volume = nil
                }
            }
            .controlSize(.mini).tint(Mono.text).accessibilityLabel("Phone volume")
            Image(systemName: "speaker.wave.3.fill").font(.system(size: 9)).foregroundColor(Mono.muted)
        }
    }

    static func time(_ ms: Double) -> String {
        let s = Int(ms / 1000)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}
