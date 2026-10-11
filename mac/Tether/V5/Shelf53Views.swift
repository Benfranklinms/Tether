import AppKit
import SwiftUI
import TetherCore

// 0.5.3 Shelf card pieces: hover actions, the leading thumbnail / document, the document icon.

/// Copy · Open · Rename · More, in one quiet capsule that appears on hover.
struct ShelfHoverActions: View {
    @EnvironmentObject var m: AppModel
    let item: ShelfItem
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var copied: Bool { m.v5.succeeded.contains("shelf-copy-\(item.id)") || m.v5.succeeded.contains("shelf-\(item.id)") }
    private var primary: String { ShelfDetect.action(item.kind) }

    var body: some View {
        HStack(spacing: 2) {
            icon(copied ? "checkmark" : "doc.on.doc", help: copied ? "Copied" : "Copy") {
                m.copyShelf(item)
                m.flashSuccess("shelf-copy-\(item.id)")
            }
            if primary != "Copy" {
                icon(item.kind == ShelfKinds.address ? "map" : "arrow.up.forward.square", help: primary) { m.shelfPrimary(item) }
            }
            icon("pencil", help: "Rename") { m.v5.shelfRenaming = item.id }
            Menu {
                Button("Open details") { withAnimation(Motion.hero(reduce)) { m.v5.shelfDetail = item.id } }
                Button("Copy") { m.copyShelf(item) }
                Divider()
                Button("Remove") { withAnimation(Motion.spring(reduce)) { m.removeFromShelf(item.id) } }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 12, weight: .medium)).foregroundColor(Mono.text)
                    .frame(width: 30, height: 28).contentShape(Rectangle())
            }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            .help("More")
        }
        .padding(3)
        .background(Capsule().fill(Mono.bg.opacity(0.85)))
        .overlay(Capsule().stroke(Mono.line))
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.3, dampingFraction: 0.6), value: copied)
    }

    private func icon(_ symbol: String, help: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 12, weight: .medium)).foregroundColor(Mono.text)
                .frame(width: 30, height: 28).contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(scale: 0.85))
        .help(help)
        .accessibilityLabel(help)
    }
}

/// The leading visual: the image itself, a paper document with its type ("PDF"), or the kind's
/// symbol on a soft square — as in the mockup.
struct ShelfLeading: View {
    let item: ShelfItem
    let state: ShelfCache.State
    let size: CGFloat

    var body: some View {
        if let f = item.file, ShelfMeta.hasPicture(item) {
            ShelfFileThumb(item: item, state: state, side: size) {
                MacDocIcon(badge: ShelfMeta.badge(mime: f.mime, name: f.name), size: size)
            }
        } else if let f = item.file {
            MacDocIcon(badge: ShelfMeta.badge(mime: f.mime, name: f.name), size: size)
        } else {
            Image(systemName: Self.symbol(item.kind)).font(.system(size: size * 0.4, weight: .regular)).foregroundColor(Mono.text)
                .frame(width: size, height: size)
                .background(RoundedRectangle(cornerRadius: size * 0.26, style: .continuous).fill(Mono.surfaceHigh))
        }
    }

    static func symbol(_ kind: String) -> String {
        switch kind {
        case ShelfKinds.link: return "globe"
        case ShelfKinds.wifi: return "wifi"
        case ShelfKinds.address: return "mappin.and.ellipse"
        case ShelfKinds.snippet: return "text.alignleft"
        default: return "text.quote"
        }
    }
}

/// A sheet of paper with a folded corner and its file type on a tab in the inverse tone.
struct MacDocIcon: View {
    let badge: String
    let size: CGFloat

    var body: some View {
        ZStack(alignment: .bottom) {
            DocShape().fill(Mono.accent).frame(width: size * 0.74, height: size * 0.92)
                .overlay(alignment: .topTrailing) {
                    DocFold().fill(Color(white: 0.72)).frame(width: size * 0.22, height: size * 0.22)
                }
                .overlay {
                    VStack(alignment: .leading, spacing: size * 0.06) {
                        ForEach(0..<3, id: \.self) { i in
                            Capsule().fill(Color(white: 0.6)).frame(width: size * (i == 2 ? 0.3 : 0.46), height: max(1, size * 0.035))
                        }
                    }
                    .offset(y: -size * 0.08)
                }
            Text(badge).font(.system(size: max(7, size * 0.17), weight: .semibold, design: .monospaced)).foregroundColor(Mono.accent)
                .lineLimit(1).fixedSize()
                .padding(.horizontal, size * 0.06).padding(.vertical, 1)
                .background(RoundedRectangle(cornerRadius: size * 0.05).fill(Mono.bg)) // inverse of the paper in both appearances
                .padding(.bottom, size * 0.14)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

private struct DocShape: Shape {
    func path(in r: CGRect) -> Path {
        let fold = r.width * 0.3
        let c = r.width * 0.08
        var p = Path()
        p.move(to: CGPoint(x: r.minX + c, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX - fold, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.minY + fold))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - c))
        p.addQuadCurve(to: CGPoint(x: r.maxX - c, y: r.maxY), control: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX + c, y: r.maxY))
        p.addQuadCurve(to: CGPoint(x: r.minX, y: r.maxY - c), control: CGPoint(x: r.minX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + c))
        p.addQuadCurve(to: CGPoint(x: r.minX + c, y: r.minY), control: CGPoint(x: r.minX, y: r.minY))
        p.closeSubpath()
        return p
    }
}

private struct DocFold: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY))
        p.closeSubpath()
        return p
    }
}
