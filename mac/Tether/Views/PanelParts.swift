import SwiftUI
import TetherCore

/// The panel's quick actions (0.4.0 tidy): three pinned tiles, and a "More" chevron that
/// reveals the other three. Which tiles are pinned and whether More is open are remembered.
struct ActionGrid: View {
    @EnvironmentObject var m: AppModel
    /// Opens a window (through the panel when hosted in it).
    var open: (WindowID) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var expanded: Bool { m.settings.panel.moreExpanded }

    var body: some View {
        let cols = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)
        VStack(spacing: 6) {
            LazyVGrid(columns: cols, spacing: 8) {
                ForEach(m.settings.panel.pinned) { tile($0) }
                if expanded {
                    ForEach(m.settings.panel.more) { t in
                        tile(t).transition(.scale(scale: 0.9).combined(with: .opacity))
                    }
                }
            }
            .tetherGlassGroup(spacing: 8)
            Button {
                withAnimation(Motion.spring(reduce)) { m.settings.panel.moreExpanded.toggle() }
            } label: {
                HStack(spacing: 4) {
                    Text(expanded ? "Less" : "More").font(Typo.sans(10, .medium))
                    Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                }
                .foregroundColor(Mono.muted)
                .padding(.horizontal, 10).frame(height: 18)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(expanded ? "Fewer actions" : "More actions")
        }
        .padding(.horizontal, 16).padding(.bottom, 8)
    }

    @ViewBuilder private func tile(_ t: PanelTile) -> some View {
        switch t {
        case .clipboard:
            ActionTile(title: t.title, symbol: t.symbol, enabled: m.isConnected) { m.sendClipboardNow() }
        case .file:
            ActionTile(title: t.title, symbol: t.symbol) { m.chooseFiles() }
        case .messages:
            ActionTile(title: t.title, symbol: t.symbol, enabled: m.supports(Caps3.sms) && m.settings.smsEnabled) { m.openMessages() }
        case .downloads:
            ActionTile(title: t.title, symbol: t.symbol) { m.openDownloads() }
        case .findPhone:
            ActionTile(title: m.ringing || m.ringRequested ? "Stop ringing" : t.title,
                       symbol: m.ringing ? "speaker.wave.3.fill" : t.symbol, enabled: m.supports(Caps.ring)) {
                if m.ringing || m.ringRequested { m.stopRing() } else { m.ring() }
            }
        case .screen:
            ActionTile(title: t.title, symbol: t.symbol, enabled: m.supports(Caps.screen)) {
                open(.screen)
                if m.screen.phase != .live { m.startScreen() }
            }
        }
    }
}

/// Now playing as one compact row: artwork, title, play/pause, and the toggle back to full.
struct CompactNowPlaying: View {
    @EnvironmentObject var m: AppModel
    let np: NowPlayingPayload

    var body: some View {
        HStack(spacing: 10) {
            Group {
                if let img = m.artwork {
                    Image(nsImage: img).resizable().scaledToFill()
                } else {
                    Image(systemName: "music.note").font(.system(size: 11)).foregroundColor(Mono.muted)
                        .frame(maxWidth: .infinity, maxHeight: .infinity).background(Mono.surfaceHigh)
                }
            }
            .frame(width: 28, height: 28)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(np.title.isEmpty ? "Playing" : np.title).font(Typo.sans(12, .semibold)).foregroundColor(Mono.text).lineLimit(1)
                if !np.artist.isEmpty {
                    Text(np.artist).font(Typo.sans(10)).foregroundColor(Mono.muted).lineLimit(1)
                }
            }
            Spacer(minLength: 4)
            Button { m.media(MediaCmds.toggle) } label: {
                Image(systemName: np.playing ? "pause.fill" : "play.fill").font(.system(size: 13)).foregroundColor(Mono.text)
                    .frame(width: 28, height: 28).contentShape(Rectangle())
            }
            .buttonStyle(PressStyle()).help(np.playing ? "Pause" : "Play").accessibilityLabel(np.playing ? "Pause" : "Play")
            PlayerSizeToggle(compact: true)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .tetherCard()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Now playing on your phone: \(np.title)")
    }
}

/// Switches the now-playing card between full and compact (remembered).
struct PlayerSizeToggle: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce
    let compact: Bool

    var body: some View {
        Button {
            withAnimation(Motion.spring(reduce)) { m.settings.panel.compactPlayer.toggle() }
        } label: {
            Image(systemName: compact ? "chevron.down" : "chevron.up").font(.system(size: 9, weight: .semibold))
                .foregroundColor(Mono.muted).frame(width: 20, height: 20).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(compact ? "Show full player" : "Collapse player")
        .accessibilityLabel(compact ? "Show full player" : "Collapse player")
    }
}

/// Settings › General › Panel: choose and order the three pinned actions.
struct PinnedTilesEditor: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(m.settings.panel.pinned.enumerated()), id: \.element) { i, t in
                Row(title: "Pinned \(i + 1)", detail: nil, divider: true) {
                    HStack(spacing: 6) {
                        Picker("Pinned \(i + 1)", selection: Binding(get: { t }, set: { m.settings.panel.pin($0, at: i) })) {
                            ForEach(PanelTile.allCases) { Text($0.title).tag($0) }
                        }
                        .labelsHidden().fixedSize()
                        arrow("chevron.up", "Move up", enabled: i > 0) { m.settings.panel.move(from: i, to: i - 1) }
                        arrow("chevron.down", "Move down", enabled: i < PanelLayout.pinnedCount - 1) {
                            m.settings.panel.move(from: i, to: i + 1)
                        }
                    }
                }
            }
            Row(title: "Others", detail: m.settings.panel.more.map(\.title).joined(separator: ", ") + " (under More)", divider: false) {
                GhostButton(title: "Reset") { m.settings.panel.reset() }
            }
        }
    }

    private func arrow(_ symbol: String, _ label: String, enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 10, weight: .semibold)).foregroundColor(enabled ? Mono.text : Mono.muted)
                .frame(width: 22, height: 22).tetherControl(Circle()).contentShape(Circle())
        }
        .buttonStyle(.plain).disabled(!enabled).help(label).accessibilityLabel(label)
    }
}
