import AppKit
import SwiftUI
import TetherCore

/// Settings › Appearance: Dark / Light / System and Pure Mono / Liquid Glass.
struct AppearancePage5: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        SectionLabel(text: "Mode")
        Card {
            Row(title: "Appearance", detail: "System follows your Mac.", divider: true) {
                MonoSegmented(options: ColorMode.allCases, selection: $m.settings.colorMode, label: { $0.title }).fixedSize()
            }
            Row(title: "Look", detail: m.settings.appearance == .glass ? GlassSupport.support : "Solid black, greys and white.",
                divider: false) {
                MonoSegmented(options: AppearanceStyle.allCases, selection: $m.settings.appearance, label: { $0.title }).fixedSize()
            }
        }
        SectionLabel(text: "Where Tether lives")
        Card {
            Row(title: "Show in", detail: "The menu bar keeps a slim popover; the Dock opens the full window.", divider: false) {
                MonoSegmented(options: AppPresence.allCases, selection: $m.settings.presence, label: { $0.title }).fixedSize()
            }
        }
    }
}

/// Settings › Mac: shortcuts, launch, Focus, calls, battery, codes, Continue, proximity unlock.
struct MacPage5: View {
    @EnvironmentObject var m: AppModel
    @State private var password = ""
    @State private var showUnlockSetup = false

    var body: some View {
        SectionLabel(text: "Startup")
        Card {
            ToggleRow(title: "Open at login", isOn: Binding(get: { m.launchAtLogin }, set: { m.setLaunchAtLogin($0) }), divider: false)
        }
        SectionLabel(text: "Keyboard shortcuts")
        Card {
            ToggleRow(title: "Send clipboard  \(m.settings.hotkeys.sendClipboard.display)", isOn: $m.settings.hotkeys.sendClipboard.enabled)
            ToggleRow(title: "Send a file  \(m.settings.hotkeys.sendFile.display)", isOn: $m.settings.hotkeys.sendFile.enabled)
            ToggleRow(title: "Open Tether  \(m.settings.hotkeys.openTether.display)", isOn: $m.settings.hotkeys.openTether.enabled,
                      divider: !Hotkeys.shared.failed.isEmpty)
            if !Hotkeys.shared.failed.isEmpty {
                Row(title: "Taken by another app: \(Hotkeys.shared.failed.joined(separator: " "))", divider: false) { EmptyView() }
            }
        }
        SectionLabel(text: "From your phone")
        Card {
            ToggleRow(title: "Copy one-time codes automatically", detail: "Codes from texts and notifications go to the clipboard.",
                      isOn: $m.settings.otpAutoCopy)
            ToggleRow(title: "Continue on Mac alerts", detail: "A banner when a photo, download, link or song is ready to continue.",
                      isOn: $m.settings.continueNotify)
            ToggleRow(title: "Fully charged alert", isOn: $m.settings.chargedAlert)
            ToggleRow(title: "Low battery alert (20%)", isOn: $m.settings.lowBatteryAlert, divider: false)
        }
        SectionLabel(text: "Calls")
        Card {
            ToggleRow(title: "Pause Mac media during calls", detail: "Resumes when the call ends.", isOn: $m.settings.callMediaPause)
            ToggleRow(title: "Lower Mac volume during calls", detail: "Restored afterwards unless you changed it.",
                      isOn: $m.settings.callDuck, divider: false)
        }
        SectionLabel(text: "Focus")
        Card {
            ToggleRow(title: "Sync Focus and Do Not Disturb", isOn: $m.settings.focusSync)
            Row(title: "Phone DND on runs shortcut", detail: "Create it in Shortcuts with “Set Focus”.") {
                TextField("", text: $m.settings.focusOnShortcut).textFieldStyle(.roundedBorder).frame(width: 170)
            }
            Row(title: "Phone DND off runs shortcut") {
                TextField("", text: $m.settings.focusOffShortcut).textFieldStyle(.roundedBorder).frame(width: 170)
            }
            Row(title: "Mac Focus → phone", detail: "In Shortcuts › Automation › Focus, add “Open URL” with tether://dnd?on=1 "
                    + "(and ?on=0 when it ends).",
                divider: false) {
                GhostButton(title: "Copy URL") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString("tether://dnd?on=1", forType: .string)
                    m.toast("Copied")
                }
            }
        }
        unlock
    }

    @ViewBuilder private var unlock: some View {
        SectionLabel(text: "Proximity unlock")
        Card {
            ToggleRow(title: "Unlock this Mac when my phone is near",
                      detail: "Off by default. Your Mac password is stored in your Keychain and typed "
                              + "into the lock screen only when the Mac is locked, your paired phone is "
                              + "near, and the encrypted link is up.",
                      isOn: Binding(get: { m.settings.proximityUnlock },
                          set: { on in if on { showUnlockSetup = true } else { disableUnlock() } }),
                      divider: m.settings.proximityUnlock)
            if m.settings.proximityUnlock {
                Row(title: "Accessibility", detail: m.v5.accessibilityTrusted ? "Allowed" : "Required to type the password.",
                    divider: false) {
                    if !m.v5.accessibilityTrusted { GhostButton(title: "Allow…") { Accessibility.request() } }
                }
            }
        }
        if showUnlockSetup {
            Card {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Warning").font(Typo.sans(13, .semibold)).foregroundColor(Mono.text)
                    Text("Anyone holding your unlocked, paired phone next to this Mac can unlock "
                            + "it. Bluetooth distance is an estimate. Don't use this on a shared or work "
                            + "Mac. You can turn it off any time.")
                        .font(Typo.sans(12)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
                    SecureField("Mac login password", text: $password).textFieldStyle(.roundedBorder)
                    HStack {
                        MonoButton(title: "Confirm with Touch ID or password") { enableUnlock() }
                        MonoButton(title: "Cancel", filled: false) {
                            password = ""
                            showUnlockSetup = false
                        }
                    }
                }
                .padding(14)
            }
        }
    }

    private func enableUnlock() {
        guard !password.isEmpty else { return m.toast("Enter your Mac password") }
        OwnerAuth.confirm("turn on proximity unlock") { ok in
            guard ok else { return m.toast("Not confirmed") }
            if Keychain.write(Keychain.unlockService, password) {
                m.v5.unlockReady = true
                m.settings.proximityUnlock = true
                if !Accessibility.trusted { Accessibility.request() }
                Log.info("unlock.enabled")
            }
            password = ""
            showUnlockSetup = false
        }
    }

    private func disableUnlock() {
        Keychain.write(Keychain.unlockService, nil)
        m.v5.unlockReady = false
        m.settings.proximityUnlock = false
        Log.info("unlock.disabled")
    }
}

/// Settings › Updates: private GitHub releases (token) or a custom public feed.
struct UpdatesPage5: View {
    @EnvironmentObject var m: AppModel
    @State private var token = ""
    @State private var feed = ""

    private var u: UpdateState { m.v5.update }

    var body: some View {
        SectionLabel(text: "Tether \(Engine.version)")
        Card {
            Row(title: statusTitle, detail: statusDetail, divider: true) {
                switch u.phase {
                case .available: GhostButton(title: "Install \(u.info?.version ?? "")") { m.installUpdate() }
                case .checking, .installing, .downloading: ProgressView().controlSize(.small)
                default: GhostButton(title: "Check now") { m.checkForUpdates() }
                }
            }
            ToggleRow(title: "Check automatically (daily)", isOn: $m.settings.autoUpdateCheck, divider: false)
        }
        if let notes = u.info?.notes, u.phase == .available, !notes.isEmpty {
            Card {
                ScrollView { Text(notes).font(Typo.sans(12)).foregroundColor(Mono.text).frame(maxWidth: .infinity, alignment: .leading) }
                    .frame(maxHeight: 160).padding(14)
            }
        }
        SectionLabel(text: "GitHub token")
        Card {
            if u.tokenSet {
                Row(title: "Token saved in your Keychain", detail: "Read-only access to Tether releases.", divider: false) {
                    GhostButton(title: "Remove") { m.removeGithubToken() }
                }
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Optional: a GitHub token").font(Typo.sans(13, .medium)).foregroundColor(Mono.text)
                    Text("Updates come from Tether's public GitHub releases without one. A fine-grained, "
                            + "read-only token (Contents: Read-only) only raises GitHub's rate limit.")
                        .font(Typo.sans(12)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        SecureField("github_pat_…", text: $token).textFieldStyle(.roundedBorder)
                        GhostButton(title: "Save") {
                            m.saveGithubToken(token)
                            token = ""
                        }
                    }
                    Button("Create a token on GitHub") {
                        if let url = URL(string: GitHubReleases.newTokenURL) { NSWorkspace.shared.open(url) }
                    }
                    .buttonStyle(.plain).font(Typo.sans(12, .semibold)).foregroundColor(Mono.text).underline()
                }
                .padding(14)
            }
        }
        SectionLabel(text: "Custom feed (optional)")
        Card {
            Row(title: "Public feed URL", detail: "Leave empty to use GitHub releases. Must be https and in Tether's feed "
                    + "format.", divider: false) {
                TextField("https://…", text: $feed, onCommit: { m.settings.updateFeed = feed.isEmpty ? nil : feed })
                    .textFieldStyle(.roundedBorder).frame(width: 220)
            }
        }
        .onAppear { feed = m.settings.updateFeed ?? "" }
    }

    private var statusTitle: String {
        switch u.phase {
        case .idle: return "Updates"
        case .checking: return "Checking…"
        case .upToDate: return "Tether is up to date"
        case .available: return "Tether \(u.info?.version ?? "") is available"
        case .downloading(let f): return "Downloading… \(Int(f * 100))%"
        case .installing: return "Installing…"
        case .failed(let msg): return msg
        }
    }

    private var statusDetail: String? {
        u.lastCheck.map { "Last checked \($0.formatted(date: .abbreviated, time: .shortened))" }
    }
}
