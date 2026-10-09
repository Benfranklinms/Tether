import SwiftUI

/// iOS-style switch in the mono palette: the knob springs across and the track fills white.
struct MonoToggleStyle: ToggleStyle {
    @Environment(\.accessibilityReduceMotion) private var reduce
    @Environment(\.isEnabled) private var enabled

    /// Draws only the switch; the label is kept for VoiceOver through the accessibility representation.
    func makeBody(configuration: Configuration) -> some View {
        ZStack(alignment: configuration.isOn ? .trailing : .leading) {
            Capsule()
                .fill(configuration.isOn ? Mono.accent : Mono.surfaceHigh)
                .overlay(Capsule().stroke(configuration.isOn ? Color.clear : Mono.line))
                .frame(width: 34, height: 20)
            Circle()
                .fill(configuration.isOn ? Mono.bg : Mono.muted)
                .frame(width: 16, height: 16)
                .padding(2)
        }
        .animation(Motion.bouncy(reduce), value: configuration.isOn)
        .contentShape(Capsule())
        .onTapGesture { if enabled { configuration.isOn.toggle() } }
        .opacity(enabled ? 1 : 0.4)
        .accessibilityRepresentation {
            Toggle(isOn: configuration.$isOn) { configuration.label }
        }
    }
}

/// A segmented control whose selection pill slides between options (matchedGeometryEffect).
struct MonoSegmented<T: Hashable>: View {
    let options: [T]
    @Binding var selection: T
    var label: (T) -> String
    var mono = false
    @Namespace private var ns
    @Environment(\.accessibilityReduceMotion) private var reduce
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options, id: \.self) { option in
                let on = option == selection
                Button {
                    withAnimation(Motion.spring(reduce)) { selection = option }
                } label: {
                    Text(label(option))
                        .font(mono ? Typo.mono(11, .medium) : Typo.sans(11, on ? .semibold : .medium))
                        .foregroundColor(on ? Mono.bg : Mono.muted)
                        .padding(.horizontal, 10)
                        .frame(minHeight: 22)
                        .background {
                            if on {
                                Capsule().fill(Mono.accent).matchedGeometryEffect(id: "pill", in: ns)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(2)
        .background(Capsule().fill(Mono.surfaceHigh))
        .overlay(Capsule().stroke(Mono.line))
        .opacity(enabled ? 1 : 0.4)
    }
}

/// A grouped card of rows with hairline separators.
struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .background(RoundedRectangle(cornerRadius: 12).fill(Mono.surface))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Mono.line))
    }
}

/// One settings row: title, optional detail, trailing control.
struct Row<Trailing: View>: View {
    var title: String
    var detail: String?
    var divider = true
    @ViewBuilder var trailing: Trailing

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(Typo.sans(13, .medium)).foregroundColor(Mono.text)
                    if let detail {
                        Text(detail).font(Typo.sans(11)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                trailing
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            if divider { Rectangle().fill(Mono.line).frame(height: 1).padding(.leading, 14) }
        }
    }
}

/// A row with a mono switch bound to `isOn`.
struct ToggleRow: View {
    var title: String
    var detail: String?
    @Binding var isOn: Bool
    var divider = true

    var body: some View {
        Row(title: title, detail: detail, divider: divider) {
            Toggle(isOn: $isOn) { Text(title) }.toggleStyle(MonoToggleStyle()).fixedSize()
        }
    }
}

struct SectionLabel: View {
    var text: String

    var body: some View {
        Text(text.uppercased())
            .font(Typo.mono(10, .medium))
            .tracking(0.8)
            .foregroundColor(Mono.muted)
            .padding(.leading, 4)
            .padding(.top, 6)
    }
}

/// Small outlined text button.
struct GhostButton: View {
    var title: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Typo.sans(12, .medium))
                .foregroundColor(Mono.text)
                .padding(.horizontal, 12)
                .frame(minHeight: 26)
                .overlay(Capsule().stroke(Mono.line))
                .contentShape(Capsule())
        }
        .buttonStyle(PressStyle())
    }
}

/// Square monogram used for apps in lists.
struct Monogram: View {
    var text: String
    var size: CGFloat = 28

    var body: some View {
        Text(String(text.prefix(1)).uppercased())
            .font(Typo.sans(size * 0.43, .semibold))
            .foregroundColor(Mono.muted)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.25).fill(Mono.surfaceHigh))
    }
}
