import SwiftUI
import WidgetKit

/// Battery and now-playing widgets. The app writes a small JSON snapshot into the shared app
/// group container (WidgetBridge); the widgets only read it.
struct Snapshot: Codable {
    var phoneName: String?
    var connected: Bool
    var battery: Int?
    var charging: Bool
    var songTitle: String?
    var songArtist: String?
    var playing: Bool
    var updated: Int64

    static let group = "group.in.benfranklin.tether"

    static func read() -> Snapshot? {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("widget.json"), let d = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: d)
    }
}

struct Entry: TimelineEntry {
    let date: Date
    let snap: Snapshot?
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> Entry {
        Entry(date: Date(), snap: Snapshot(phoneName: "Phone", connected: true, battery: 76, charging: false,
                                           songTitle: "Song", songArtist: "Artist", playing: true, updated: 0))
    }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(Entry(date: Date(), snap: Snapshot.read() ?? placeholder(in: context).snap))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        let e = Entry(date: Date(), snap: Snapshot.read())
        completion(Timeline(entries: [e], policy: .after(Date().addingTimeInterval(15 * 60))))
    }
}

private enum WMono {
    static let text = Color.primary
    static let muted = Color.secondary
}

struct BatteryView: View {
    let entry: Entry
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(entry.snap?.phoneName ?? "Tether").font(.system(size: 12, weight: .medium)).foregroundColor(WMono.muted).lineLimit(1)
            if let s = entry.snap, let b = s.battery {
                Text("\(b)%").font(.system(size: 34, weight: .light, design: .monospaced)).foregroundColor(WMono.text)
                ProgressView(value: Double(b), total: 100).tint(WMono.text)
                Text(s.charging ? "Charging" : (s.connected ? "Connected" : "Last seen")).font(.system(size: 11)).foregroundColor(WMono.muted)
            } else {
                Text("Open Tether").font(.system(size: 13)).foregroundColor(WMono.muted)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .widgetURL(URL(string: "tether://open"))
        .containerBackground(for: .widget) { Color(nsColor: .windowBackgroundColor) }
    }
}

struct NowPlayingView: View {
    let entry: Entry
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.snap?.playing == true ? "NOW PLAYING" : "PAUSED").font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundColor(WMono.muted)
            Text(entry.snap?.songTitle ?? "Nothing playing").font(.system(size: 15, weight: .medium)).foregroundColor(WMono.text).lineLimit(2)
            Text(entry.snap?.songArtist ?? "").font(.system(size: 12)).foregroundColor(WMono.muted).lineLimit(1)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .widgetURL(URL(string: "tether://open"))
        .containerBackground(for: .widget) { Color(nsColor: .windowBackgroundColor) }
    }
}

struct BatteryWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TetherBattery", provider: Provider()) { BatteryView(entry: $0) }
            .configurationDisplayName("Phone battery")
            .description("Your phone's battery from Tether.")
            .supportedFamilies([.systemSmall])
    }
}

struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TetherNowPlaying", provider: Provider()) { NowPlayingView(entry: $0) }
            .configurationDisplayName("Now playing on phone")
            .description("What your phone is playing.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct TetherWidgetBundle: WidgetBundle {
    var body: some Widget {
        BatteryWidget()
        NowPlayingWidget()
    }
}
