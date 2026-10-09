import AppKit
import Foundation
import MediaPlayer
import TetherCore

/// Locks the Mac for "lock when my phone walks away". Unlocking is always the user's job
/// (Touch ID / password): Tether never unlocks anything.
enum ScreenLocker {
    private typealias LockFn = @convention(c) () -> Void

    /// Locks immediately. Uses the system's lock-screen entry point when available (the same
    /// one as  › Lock Screen), else sleeps the display, which locks when the Mac requires a
    /// password after sleep. Returns false only if neither could be started.
    @discardableResult
    static func lock() -> Bool {
        if let fn = loginLockFunction() {
            fn()
            return true
        }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        p.arguments = ["displaysleepnow"]
        do {
            try p.run()
            return true
        } catch {
            Log.error("proximity.lock_failed", error)
            return false
        }
    }

    private static func loginLockFunction() -> LockFn? {
        let path = "/System/Library/PrivateFrameworks/login.framework/Versions/Current/login"
        guard let h = dlopen(path, RTLD_LAZY), let sym = dlsym(h, "SACLockScreenImmediate") else { return nil }
        return unsafeBitCast(sym, to: LockFn.self)
    }

    /// The login window is already showing (screen locked).
    static func isLocked() -> Bool {
        guard let d = CGSessionCopyCurrentDictionary() as? [String: Any] else { return false }
        return (d["CGSSessionScreenIsLocked"] as? Bool) == true || (d["CGSSessionScreenIsLocked"] as? Int) == 1
    }

    /// Someone is viewing or controlling this Mac through Screen Sharing / Remote Management.
    /// Locking then would cut them off, so proximity lock never fires while it runs.
    static func isScreenBeingShared() -> Bool {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pgrep")
        p.arguments = ["-x", "screensharingd"]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do {
            try p.run()
            p.waitUntilExit()
            return p.terminationStatus == 0
        } catch {
            return false
        }
    }
}

/// Publishes the phone's track to macOS Now Playing so the keyboard's media keys, Control
/// Center and the lock screen control the phone (opt-in: "Mac media keys" in Settings).
final class MediaKeys {
    private let center = MPRemoteCommandCenter.shared()
    private let info = MPNowPlayingInfoCenter.default()
    private var targets: [(MPRemoteCommand, Any)] = []
    private let send: (String, Int64?) -> Void

    init(send: @escaping (String, Int64?) -> Void) {
        self.send = send
        add(center.togglePlayPauseCommand) { $0(MediaCmds.toggle, nil) }
        add(center.playCommand) { $0(MediaCmds.play, nil) }
        add(center.pauseCommand) { $0(MediaCmds.pause, nil) }
        add(center.nextTrackCommand) { $0(MediaCmds.next, nil) }
        add(center.previousTrackCommand) { $0(MediaCmds.prev, nil) }
        let seek = center.changePlaybackPositionCommand
        let t = seek.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.send(MediaCmds.seek, Int64(e.positionTime * 1000))
            return .success
        }
        targets.append((seek, t))
    }

    private func add(_ cmd: MPRemoteCommand, _ run: @escaping ((String, Int64?) -> Void) -> Void) {
        cmd.isEnabled = true
        let t = cmd.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            run(self.send)
            return .success
        }
        targets.append((cmd, t))
    }

    func update(_ np: NowPlayingPayload?, artwork: NSImage?, skew: Int64) {
        guard let np else {
            info.nowPlayingInfo = nil
            info.playbackState = .stopped
            return
        }
        center.nextTrackCommand.isEnabled = np.canNext
        center.previousTrackCommand.isEnabled = np.canPrev
        center.changePlaybackPositionCommand.isEnabled = np.canSeek
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var d: [String: Any] = [
            MPMediaItemPropertyTitle: np.title,
            MPMediaItemPropertyArtist: np.artist,
            MPMediaItemPropertyAlbumTitle: np.album,
            MPMediaItemPropertyPlaybackDuration: Double(np.durationMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(np.position(at: now, skew: -skew)) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: np.playing ? Double(np.speed) : 0,
        ]
        if let artwork {
            d[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: artwork.size) { _ in artwork }
        }
        info.nowPlayingInfo = d
        info.playbackState = np.playing ? .playing : .paused
    }

    func stop() {
        for (cmd, t) in targets { cmd.removeTarget(t) }
        targets.removeAll()
        info.nowPlayingInfo = nil
        info.playbackState = .stopped
    }
}
