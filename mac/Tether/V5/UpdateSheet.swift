import AppKit
import SwiftUI
import TetherCore

/// What 0.5.2 brings, shown once after updating (bundled, so it works offline).
enum WhatsNew {
    static let version = "0.5.2"
    /// Kept in step with the 0.5.2 section of CHANGELOG.md (which becomes the release notes).
    static let notes = """
    - **Animations in both apps.** Pages slide the way you move, notifications and messages open with a hero \
    transition, photos zoom out of their tile and back, lists cascade in and swipe away. Reduce Motion turns it into a fade.
    - **Videos play in Photos**, fetched over Wi-Fi (Bluetooth when that's all there is) with a progress ring and the macOS controls.
    - **The Wi-Fi fast path comes back by itself** when the network changes or the Mac joins your phone's hotspot.
    - **Shelf** — a pinned list on both devices: text, links, Wi-Fi, addresses, snippets and files.
    - **Insert from Phone** — choose Take Photo or Scan Document, use your phone's camera, and it lands in the app you're using.
    - **Find my Mac** from your phone: a loud chime and a full-screen "Found it", or lock it.
    - **Instant Hotspot** (best effort) — one click turns on your phone's hotspot and joins it. Save the hotspot name and \
    password once in Tether on the phone and turn on Tether control.
    - **Update popup** — new versions show up here with their notes, and this sheet after each update.
    """
}

extension AppModel {
    private static let skipKey = "updateSkippedVersion"
    private static let snoozeKey = "updateSnoozedUntil"
    private static let seenKey = "whatsNewSeen"

    /// A newer release was found: show the popup unless skipped or snoozed (a manual check always shows it).
    func maybePromptUpdate(_ info: UpdateInfo, manual: Bool) {
        let d = UserDefaults.standard
        let skipped = d.string(forKey: Self.skipKey)
        let snoozed = Int64(d.double(forKey: Self.snoozeKey))
        guard manual || UpdatePrompt.shouldShow(latest: info.version, current: Engine.version, skipped: skipped,
                                                snoozedUntil: snoozed, now: Self.nowMs) else { return }
        windows?.show(.main)
        v5.updateSheet = true
    }

    /// The sidebar / menu-bar pill: an update is waiting and wasn't skipped.
    var updatePill: Bool {
        guard let info = v5.update.info, Versions.newer(info.version, than: Engine.version) else { return false }
        return UserDefaults.standard.string(forKey: Self.skipKey) != info.version
    }

    func snoozeUpdate() {
        UserDefaults.standard.set(Double(Self.nowMs + UpdatePrompt.snoozeMs), forKey: Self.snoozeKey)
        v5.updateSheet = false
    }

    func skipUpdate() {
        if let v = v5.update.info?.version { UserDefaults.standard.set(v, forKey: Self.skipKey) }
        v5.updateSheet = false
        objectWillChange.send()
    }

    func maybeShowWhatsNew() {
        let d = UserDefaults.standard
        let seen = d.string(forKey: Self.seenKey)
        // A fresh install (no previous version recorded) gets no sheet; an update from an older version does.
        let previous = seen ?? (d.object(forKey: "mainPage") != nil ? "0.5.1" : nil)
        if UpdatePrompt.showWhatsNew(current: Engine.version, seen: previous) && Engine.version == WhatsNew.version {
            windows?.show(.main)
            v5.whatsNew = true
        }
        d.set(Engine.version, forKey: Self.seenKey)
    }
}

/// The modal update sheet: springs up over a blurred, dimmed window.
struct UpdateSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var info: UpdateInfo? { v5.update.info }
    private var size: String? { (info?.macZip ?? info?.macDmg).flatMap { $0.size > 0 ? formatBytes($0.size) : nil } }

    var body: some View {
        SheetChrome(shown: shown, reduce: reduce, dismiss: { m.snoozeUpdate() }, content: {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    Image(systemName: "arrow.down.circle").font(.system(size: 26, weight: .light)).foregroundColor(Mono.text)
                        .symbolBounce(shown)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Update available").font(Typo.sans(20, .medium)).foregroundColor(Mono.text)
                        Text("Tether \(info?.version ?? "")\(size.map { " · \($0)" } ?? "") · you have \(Engine.version)")
                            .font(Typo.mono(11)).foregroundColor(Mono.muted)
                    }
                }
                MarkdownNotes(text: UpdatePrompt.changelog(info?.notes ?? ""))
                    .frame(maxHeight: 240)
                progress
                HStack(spacing: 8) {
                    GhostButton(title: "Skip this version") { m.skipUpdate() }
                    Spacer()
                    GhostButton(title: "Later") { m.snoozeUpdate() }
                    MonoButton(title: busy ? "Updating…" : "Update now") { m.installUpdate() }
                        .frame(width: 140).disabled(busy)
                }
            }
        })
        .onAppear { shown = true }
    }

    private var busy: Bool {
        switch v5.update.phase {
        case .downloading, .installing: return true
        default: return false
        }
    }

    @ViewBuilder private var progress: some View {
        switch v5.update.phase {
        case .downloading(let f):
            VStack(alignment: .leading, spacing: 6) {
                FluidProgress(value: f).frame(height: 4)
                Text("Downloading · \(Int(f * 100))%").font(Typo.mono(10)).foregroundColor(Mono.muted).contentTransition(.numericText())
            }
            .transition(.opacity.combined(with: .move(edge: .top)))
        case .installing:
            Text("Installing — Tether relaunches by itself").font(Typo.sans(12)).foregroundColor(Mono.muted).transition(.opacity)
        case .failed(let e):
            Text(e).font(Typo.sans(12)).foregroundColor(Mono.text).transition(.opacity)
        default:
            EmptyView()
        }
    }
}

/// The one-time "What's new in 0.5.2" sheet.
struct WhatsNewSheet: View {
    @ObservedObject var v5: V5State
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        SheetChrome(shown: shown, reduce: reduce, dismiss: { v5.whatsNew = false }, content: {
            VStack(alignment: .leading, spacing: 14) {
                Text("What's new in \(WhatsNew.version)").font(Typo.sans(22, .medium)).foregroundColor(Mono.text)
                MarkdownNotes(text: WhatsNew.notes).frame(maxHeight: 320)
                HStack {
                    Spacer()
                    MonoButton(title: "Continue") { v5.whatsNew = false }.frame(width: 140)
                }
            }
        })
        .onAppear { shown = true }
    }
}

/// Blurred backdrop + a card that springs up from below.
struct SheetChrome<Content: View>: View {
    let shown: Bool
    let reduce: Bool
    let dismiss: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial).opacity(shown ? 1 : 0)
                .overlay(Mono.bg.opacity(shown ? 0.45 : 0))
                .ignoresSafeArea()
                .onTapGesture(perform: dismiss)
            content
                .padding(22)
                .frame(width: 500)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Mono.surface))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Mono.line))
                .shadow(color: .black.opacity(0.4), radius: 34, y: 18)
                .offset(y: shown || reduce ? 0 : 140)
                .scaleEffect(shown || reduce ? 1 : 0.94)
                .opacity(shown ? 1 : 0)
        }
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.5, dampingFraction: 0.78), value: shown)
        .background(Button("", action: dismiss).keyboardShortcut(.cancelAction).opacity(0))
    }
}

/// Release notes rendered as Markdown (inline styles, line breaks kept; "#" headings become bold lines).
struct MarkdownNotes: View {
    let text: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(text.components(separatedBy: "\n").enumerated()), id: \.offset) { _, line in
                    row(line)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .textSelection(.enabled)
        }
    }

    @ViewBuilder private func row(_ raw: String) -> some View {
        let line = raw.trimmingCharacters(in: .whitespaces)
        if line.isEmpty {
            Color.clear.frame(height: 2)
        } else if line.hasPrefix("#") {
            Text(String(line.drop { $0 == "#" }).trimmingCharacters(in: .whitespaces))
                .font(Typo.sans(13, .semibold)).foregroundColor(Mono.text).padding(.top, 4)
        } else if line.hasPrefix("- ") || line.hasPrefix("* ") {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("•").font(Typo.sans(13)).foregroundColor(Mono.muted)
                Text(md(String(line.dropFirst(2)))).font(Typo.sans(13)).foregroundColor(Mono.text)
                    .fixedSize(horizontal: false, vertical: true)
            }
        } else {
            Text(md(line)).font(Typo.sans(13)).foregroundColor(Mono.text).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func md(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
    }
}

/// "Update" pill for the sidebar and the menu-bar popover.
struct UpdatePill: View {
    @EnvironmentObject var m: AppModel
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Button { m.v5.updateSheet = true } label: {
            HStack(spacing: 5) {
                Circle().fill(Mono.text).frame(width: 6, height: 6).scaleEffect(pulse && !reduce ? 1.35 : 1)
                Text("Update").font(Typo.sans(11, .semibold))
            }
            .foregroundColor(Mono.text)
            .padding(.horizontal, 10).frame(height: 22)
            .background(Capsule().fill(Mono.surfaceHigh))
            .overlay(Capsule().stroke(Mono.line))
        }
        .buttonStyle(PressStyle())
        .help("Tether \(m.v5.update.info?.version ?? "") is available")
        .onAppear {
            guard !reduce else { return }
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) { pulse = true }
        }
        .transition(.scale.combined(with: .opacity))
    }
}
