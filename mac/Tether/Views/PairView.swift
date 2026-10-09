import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import TetherCore

/// Pairing window. One hero mark travels between stages (matchedGeometryEffect):
/// QR code -> six-digit code to compare -> linked.
struct PairView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce
    @Environment(\.dismiss) private var dismiss
    @Namespace private var ns

    enum Stage: Equatable {
        case starting
        case qr(String)
        case code(sas: String, name: String)
        case linking(String)
        case done(String)
        case bluetooth(BluetoothStatus)
        case failed(String)

        var key: Int {
            switch self {
            case .starting: return 0
            case .qr: return 1
            case .code: return 2
            case .linking: return 3
            case .done: return 4
            case .bluetooth: return 5
            case .failed: return 6
            }
        }
    }

    private var stage: Stage {
        if m.justPaired { return .done(m.peer?.name ?? "your phone") }
        if !m.demo, [.off, .unauthorized, .unsupported].contains(m.bluetooth) { return .bluetooth(m.bluetooth) }
        if let f = m.pairFailure { return .failed(f) }
        switch m.link {
        case let .pairing(sas, name, auto):
            return auto ? .linking(name) : .code(sas: sas, name: name)
        case .handshaking where m.pairingURI != nil:
            return .linking("your phone")
        default:
            break
        }
        if let uri = m.pairingURI { return .qr(uri) }
        return .starting
    }

    var body: some View {
        let s = stage
        VStack(spacing: 0) {
            ZStack {
                switch s {
                case .starting: StartingStage(ns: ns)
                case .qr(let uri): QRStage(uri: uri, ns: ns)
                case let .code(sas, name): CodeStage(sas: sas, name: name, ns: ns)
                case .linking(let name): LinkingStage(name: name, ns: ns)
                case .done(let name): DoneStage(name: name, ns: ns) { close() }
                case .bluetooth(let status): BluetoothStage(status: status, ns: ns)
                case .failed(let reason): FailedStage(reason: reason, ns: ns)
                }
            }
            .id(s.key)
            .transition(stageTransition)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(28)
        .frame(width: 420, height: 540)
        .background(Mono.bg)
        .preferredColorScheme(.dark)
        .animation(Motion.spring(reduce), value: s.key)
        .onAppear {
            if m.pairingURI == nil && !m.demo { m.startPairing() }
        }
        .onDisappear {
            if !m.justPaired { m.stopPairing() }
            m.justPaired = false
        }
    }

    private var stageTransition: AnyTransition {
        guard !reduce else { return .opacity }
        return .asymmetric(
            insertion: .move(edge: .trailing).combined(with: .opacity),
            removal: .move(edge: .leading).combined(with: .opacity)
        )
    }

    private func close() {
        m.justPaired = false
        dismiss()
    }
}

// MARK: stages

private struct StageTitle: View {
    var title: String
    var subtitle: String?

    var body: some View {
        VStack(spacing: 6) {
            Text(title).font(Typo.sans(22, .light)).foregroundColor(Mono.text).multilineTextAlignment(.center)
            if let subtitle {
                Text(subtitle).font(Typo.sans(12)).foregroundColor(Mono.muted).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct StartingStage: View {
    var ns: Namespace.ID

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            TetherMark(linked: false, pulse: true, size: 72).matchedGeometryEffect(id: "mark", in: ns)
            StageTitle(title: "Getting ready…")
            Spacer()
        }
    }
}

private struct QRStage: View {
    @EnvironmentObject var m: AppModel
    var uri: String
    var ns: Namespace.ID
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var shown = false

    var body: some View {
        VStack(spacing: 20) {
            TetherMark(linked: false, pulse: true, size: 40).matchedGeometryEffect(id: "mark", in: ns)
            StageTitle(title: "Pair your phone", subtitle: "Open Tether on Android and scan this code.")
            ZStack {
                RoundedRectangle(cornerRadius: 22).fill(Mono.text)
                if let img = QRCode.image(uri) {
                    Image(nsImage: img).interpolation(.none).resizable().scaledToFit().padding(18)
                        .accessibilityLabel("Pairing QR code")
                }
            }
            .frame(width: 220, height: 220)
            .scaleEffect(shown || reduce ? 1 : 0.86)
            .opacity(shown || reduce ? 1 : 0)
            .blur(radius: shown || reduce ? 0 : 8)
            .onAppear { withAnimation(Motion.bouncy(reduce)?.delay(0.08)) { shown = true } }
            Text("No camera? Tap “Pair without camera” and compare codes.")
                .font(Typo.sans(11)).foregroundColor(Mono.muted).multilineTextAlignment(.center)
            Spacer(minLength: 0)
            PairStatusLine(text: m.pairStatus)
        }
    }
}

private struct CodeStage: View {
    @EnvironmentObject var m: AppModel
    var sas: String
    var name: String
    var ns: Namespace.ID

    var body: some View {
        VStack(spacing: 22) {
            TetherMark(linked: false, pulse: false, size: 40).matchedGeometryEffect(id: "mark", in: ns)
            StageTitle(title: "Same code on \(name)?", subtitle: "Make sure it matches before you confirm.")
            Spacer(minLength: 0)
            SasCode(code: sas)
            Spacer(minLength: 0)
            VStack(spacing: 8) {
                MonoButton(title: "They match") { m.confirmPairing() }
                MonoButton(title: "Not the same", filled: false) { m.rejectPairing() }
            }
        }
    }
}

/// Six digits that drop in one after another, grouped 3 + 3.
struct SasCode: View {
    private static let drop = Animation.spring(response: 0.5, dampingFraction: 0.7)
    var code: String
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var revealed = false

    var body: some View {
        let digits = Array(code)
        HStack(spacing: 8) {
            ForEach(Array(digits.enumerated()), id: \.offset) { i, ch in
                Text(String(ch))
                    .font(Typo.mono(34, .medium))
                    .foregroundColor(Mono.text)
                    .frame(width: 40, height: 56)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Mono.surface))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Mono.line))
                    .offset(y: revealed || reduce ? 0 : 14)
                    .opacity(revealed || reduce ? 1 : 0)
                    .blur(radius: revealed || reduce ? 0 : 5)
                    .animation(reduce ? nil : Self.drop.delay(Double(i) * 0.07), value: revealed)
                    .padding(.leading, i == 3 ? 10 : 0)
            }
        }
        .onAppear { revealed = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Code \(digits.map(String.init).joined(separator: " "))")
    }
}

private struct LinkingStage: View {
    @EnvironmentObject var m: AppModel
    var name: String
    var ns: Namespace.ID

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            TetherMark(linked: false, pulse: true, size: 72).matchedGeometryEffect(id: "mark", in: ns)
            StageTitle(title: "Linking with \(name)…", subtitle: "Keep both devices close.")
            Spacer()
            PairStatusLine(text: m.pairStatus)
        }
    }
}

/// Live one-line status at the bottom of the pair window ("Visible over Bluetooth · waiting…").
struct PairStatusLine: View {
    var text: String
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Mono.text.opacity(0.7)).frame(width: 5, height: 5)
            Text(text)
                .font(Typo.sans(11))
                .foregroundColor(Mono.muted)
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .id(text)
                .transition(.opacity)
        }
        .animation(reduce ? nil : .easeInOut(duration: 0.25), value: text)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

/// Bluetooth is off, not allowed, or missing: say so and offer the fix instead of spinning.
private struct BluetoothStage: View {
    @EnvironmentObject var m: AppModel
    var status: BluetoothStatus
    var ns: Namespace.ID

    private var copy: (String, String) {
        switch status {
        case .off: return ("Turn on Bluetooth", "Tether links to your phone over Bluetooth. Turn it on in Control Center.")
        case .unauthorized:
            return ("Allow Bluetooth", "Open System Settings › Privacy & Security › Bluetooth and switch Tether on, then reopen Tether.")
        default: return ("Bluetooth unavailable", "This Mac doesn't offer Bluetooth Low Energy.")
        }
    }

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            TetherMark(linked: false, pulse: false, size: 72).matchedGeometryEffect(id: "mark", in: ns)
            StageTitle(title: copy.0, subtitle: copy.1)
            Spacer()
            if status == .unauthorized {
                MonoButton(title: "Open Bluetooth settings") { m.openBluetoothPrivacy() }
            }
        }
    }
}

/// The phone connected but the secure link never finished: explain, retry, export logs.
private struct FailedStage: View {
    @EnvironmentObject var m: AppModel
    var reason: String
    var ns: Namespace.ID

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            TetherMark(linked: false, pulse: false, size: 72).matchedGeometryEffect(id: "mark", in: ns)
            StageTitle(title: "Couldn't finish linking", subtitle: reason)
            Spacer()
            VStack(spacing: 8) {
                MonoButton(title: "Try again") { m.retryPairing() }
                MonoButton(title: "Export diagnostics", filled: false) { m.exportLogs() }
            }
        }
    }
}

private struct DoneStage: View {
    var name: String
    var ns: Namespace.ID
    var onDone: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var linked = false
    @State private var ripple = false

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            ZStack {
                Circle().stroke(Mono.text.opacity(ripple ? 0 : 0.5), lineWidth: 1)
                    .frame(width: 96, height: 96)
                    .scaleEffect(ripple ? 1.9 : 0.6)
                TetherMark(linked: linked, pulse: false, size: 96).matchedGeometryEffect(id: "mark", in: ns)
            }
            StageTitle(title: "Linked", subtitle: "\(name) and this Mac are paired.")
            Spacer()
            MonoButton(title: "Done", action: onDone)
        }
        .onAppear {
            if reduce {
                linked = true
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                linked = true
                withAnimation(.easeOut(duration: 1.1)) { ripple = true }
            }
        }
    }
}

/// Crisp QR rendering via CoreImage (dark modules on a light card scan reliably).
enum QRCode {
    static func image(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let colored = output.applyingFilter("CIFalseColor", parameters: [
            "inputColor0": CIColor(red: 0x09 / 255, green: 0x09 / 255, blue: 0x0A / 255),
            "inputColor1": CIColor(red: 0xF2 / 255, green: 0xF2 / 255, blue: 0xF2 / 255),
        ])
        let scaled = colored.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let ctx = CIContext()
        guard let cg = ctx.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: scaled.extent.width, height: scaled.extent.height))
    }
}
