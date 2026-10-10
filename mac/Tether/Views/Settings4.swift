import SwiftUI
import TetherCore

/// Settings › Features › Calls (0.4.0): alerts, answering, calling and phone links.
struct CallsSettings: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        SectionLabel(text: "Calls")
        Card {
            ToggleRow(title: "Incoming call alerts",
                      detail: "Caller name with Answer, Decline and Silence. Missed calls become notifications.",
                      isOn: $m.settings.callAlerts)
            ToggleRow(title: "Speaker on when answering from the Mac",
                      detail: "Answering here turns on the phone's speaker so you can talk without picking it up.",
                      isOn: $m.settings.speakerOnAnswer)
            ToggleRow(title: "Make calls from this Mac", detail: "Dial pad, contact search, recent calls and call back in the panel.",
                      isOn: $m.settings.callsEnabled)
            Row(title: "Phone number links", detail: m.isPhoneLinkHandler
                ? "Clicking a number anywhere on this Mac calls through your phone."
                : "Make Tether open tel: links (numbers in Safari, Mail, Contacts…).") {
                if m.isPhoneLinkHandler {
                    Text("Tether").font(Typo.sans(12)).foregroundColor(Mono.muted)
                } else {
                    GhostButton(title: "Use Tether") { m.useTetherForPhoneLinks() }
                }
            }
            Row(title: "When I click a number", divider: false) {
                MonoSegmented(options: TelLinkMode.allCases, selection: $m.settings.telLinks) { $0 == .ask ? "Show first" : "Call now" }
                    .fixedSize()
            }
        }
        Text("Calls go through your phone and its SIM. The audio stays on the phone or its earbuds: Android doesn't let apps "
             + "send phone-call audio to a computer, so use the speaker or earbuds to talk.")
            .font(Typo.sans(11)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true).padding(.horizontal, 4)
    }
}

/// Settings › General › Panel: compact player and the pinned actions.
struct PanelSettings: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        SectionLabel(text: "Panel")
        Card {
            ToggleRow(title: "Compact player", detail: "Now playing as one row: artwork, title and play/pause.",
                      isOn: $m.settings.panel.compactPlayer)
            ToggleRow(title: "Show all actions", detail: "Keep More open under the three pinned actions.",
                      isOn: $m.settings.panel.moreExpanded)
            PinnedTilesEditor()
        }
    }
}

/// Settings › Notifications: Android 15+ one-time codes status and the fix.
struct CodesSettings: View {
    @EnvironmentObject var m: AppModel
    @State private var open = false

    var body: some View {
        if let a = m.notifAccess, a.sdk >= 35 {
            SectionLabel(text: "One-time codes")
            Card {
                Row(title: a.redacting ? "Hidden by Android" : (a.granted == true ? "Codes are shown" : "Not seen hidden yet"),
                    detail: a.redacting ? "Your phone shows \"Sensitive notification content hidden\" to Tether."
                        : "Android 15+ can hide one-time codes from apps like Tether.",
                    divider: open) {
                    GhostButton(title: open ? "Hide steps" : "Show steps") { open.toggle() }
                }
                if open {
                    VStack(alignment: .leading, spacing: 8) {
                        CodesSteps(oem: a.oem)
                        GhostButton(title: "Copy command") { m.copyAdbCommand() }
                    }
                    .padding(14)
                }
            }
        }
    }
}
