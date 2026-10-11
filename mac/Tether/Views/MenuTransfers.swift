import SwiftUI
import TetherCore

/// The transfers tab (0.5.3): the latest three with "See all (N)", or everything grouped by day.
struct MenuTransfers: View {
    @EnvironmentObject var m: AppModel
    @State private var allTransfers = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    var body: some View {
        let all = m.visibleTransfers
        if allTransfers {
            let midnight = Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000)
            ForEach(TransferList.sections(all, .all, now: nowMs, startOfToday: midnight), id: \.title) { sec in
                Text(sec.title.uppercased()).font(Typo.mono(9, .medium)).tracking(0.8).foregroundColor(Mono.muted)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.top, 10)
                ForEach(sec.items, id: \.id) { t in TransferRow(t: t).transition(.opacity) }
            }
            Button("Show fewer") { withAnimation(Motion.spring(reduce)) { allTransfers = false } }
                .buttonStyle(.plain).font(Typo.sans(11, .semibold)).foregroundColor(Mono.muted).padding(10)
        } else {
            let recent = TransferList.home(all, now: nowMs)
            ForEach(recent, id: \.id) { t in
                TransferRow(t: t)
                    .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
            }
            if all.count > recent.count {
                Button { withAnimation(Motion.spring(reduce)) { allTransfers = true } } label: {
                    HStack {
                        Text("See all").font(Typo.sans(12, .semibold)).foregroundColor(Mono.text)
                        Spacer()
                        Text("\(all.count)").font(Typo.mono(11)).foregroundColor(Mono.muted)
                        Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundColor(Mono.muted)
                    }
                    .padding(.horizontal, 16).frame(height: 34).contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel("See all \(all.count) transfers")
            }
        }
    }
}
