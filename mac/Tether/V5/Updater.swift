import AppKit
import CryptoKit
import Foundation
import TetherCore

/// In-app updates for the Mac. Default source: the private Benfranklinms/Tether GitHub releases,
/// read with a fine-grained read-only token kept in the Keychain. Optional alternative: a custom
/// public https feed (tether-update.json format). The ZIP is preferred, the DMG is the fallback;
/// both are checked against the release's SHA-256 before the app is replaced.
final class MacUpdater: NSObject, URLSessionTaskDelegate {
    static let shared = MacUpdater()
    weak var model: AppModel?

    private lazy var session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)

    // The token is only ever sent to api.github.com: a redirect elsewhere (the asset CDN) drops it.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        var r = request
        if !GitHubReleases.redirectKeepsAuth(to: request.url) { r.setValue(nil, forHTTPHeaderField: "Authorization") }
        completionHandler(r)
    }

    private func set(_ f: @escaping (inout UpdateState) -> Void) {
        DispatchQueue.main.async { if let m = self.model { f(&m.v5.update) } }
    }

    private func request(_ url: URL, binary: Bool) -> URLRequest {
        var r = URLRequest(url: url, timeoutInterval: binary ? 600 : 30)
        let custom = model?.settings.updateFeed?.isEmpty == false
        let token = custom ? nil : Keychain.githubToken
        for (k, v) in GitHubReleases.headers(for: url, token: token, binary: binary) { r.setValue(v, forHTTPHeaderField: k) }
        return r
    }

    /// Source URL: the custom feed when set, else the private releases API (needs a token).
    var source: URL? {
        if let f = model?.settings.updateFeed, !f.isEmpty { return f.hasPrefix("https://") ? URL(string: f) : nil }
        return URL(string: GitHubReleases.latestURL)
    }

    func check(manual: Bool) {
        let custom = model?.settings.updateFeed?.isEmpty == false
        guard custom || Keychain.githubToken != nil else {
            set { $0.phase = .failed(GitHubReleases.noTokenMessage) }
            return
        }
        guard let url = source else {
            set { $0.phase = .failed("The update feed must be an https:// URL") }
            return
        }
        set { $0.phase = .checking }
        session.dataTask(with: request(url, binary: false)) { [weak self] data, resp, err in
            guard let self else { return }
            let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            Log.info("update.check", ["status": "\(status)", "custom": "\(custom)", "token": custom ? "-" : "set"])
            guard err == nil, status == 200, let data, let info = UpdateFeed.parse(data) else {
                let msg = err != nil ? "Couldn't reach GitHub"
                    : (status == 200 ? "No usable release" : GitHubReleases.explain(status: status))
                self.set { $0.phase = .failed(msg); $0.lastCheck = Date() }
                return
            }
            let newer = Versions.newer(info.version, than: Engine.version)
            self.set {
                $0.info = info
                $0.lastCheck = Date()
                $0.phase = newer ? .available : .upToDate
            }
            if newer && !manual {
                DispatchQueue.main.async {
                    self.model?.notifier.postLocal(id: "update-\(info.version)", title: "Tether \(info.version) is available",
                                                   body: "Open Settings › Updates to install it.")
                }
            }
        }.resume()
    }

    /// Download, verify and install the available version, then relaunch.
    func install() {
        guard let info = model?.v5.update.info, let asset = info.macZip ?? info.macDmg else {
            return set { $0.phase = .failed("This release has no Mac download") }
        }
        let isZip = info.macZip != nil
        let custom = model?.settings.updateFeed?.isEmpty == false
        // Private repo: the asset API URL with Accept: application/octet-stream; public feed: the plain URL.
        guard let url = URL(string: (!custom ? asset.apiURL : nil) ?? asset.url) else { return }
        set { $0.phase = .downloading(0) }
        let task = session.downloadTask(with: request(url, binary: true)) { [weak self] tmp, resp, err in
            guard let self else { return }
            let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            guard err == nil, status == 200, let tmp else {
                return self.set { $0.phase = .failed(err != nil ? "Download failed" : GitHubReleases.explain(status: status)) }
            }
            do {
                let file = Paths.sub("update").appendingPathComponent(isZip ? "Tether.zip" : "Tether.dmg")
                try? FileManager.default.removeItem(at: file)
                try FileManager.default.moveItem(at: tmp, to: file)
                guard let want = asset.sha256, try Self.sha256(file) == want else {
                    throw UpdateError(message: asset.sha256 == nil ? "The release has no checksum" : "Checksum mismatch")
                }
                self.set { $0.phase = .installing }
                let app = try isZip ? Self.unzip(file) : Self.mountAndCopy(file)
                try Self.verify(app, version: info.version)
                try Self.replaceAndRelaunch(with: app)
            } catch {
                Log.error("update.install_failed", error)
                self.set { $0.phase = .failed((error as? UpdateError)?.message ?? "Couldn't install the update") }
            }
        }
        task.resume()
        observe(task)
    }

    private var progress: NSKeyValueObservation?
    private func observe(_ task: URLSessionDownloadTask) {
        progress = task.progress.observe(\.fractionCompleted) { [weak self] p, _ in
            let f = p.fractionCompleted
            self?.set { if case .downloading = $0.phase { $0.phase = .downloading(f) } }
        }
    }

    struct UpdateError: Error { let message: String }

    static func sha256(_ url: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        var sha = SHA256()
        while true {
            let d = h.readData(ofLength: 1 << 20)
            if d.isEmpty { break }
            sha.update(data: d)
        }
        return sha.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func run(_ tool: String, _ args: [String]) throws {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tool)
        p.arguments = args
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try p.run()
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { throw UpdateError(message: "\((tool as NSString).lastPathComponent) failed") }
    }

    static func unzip(_ zip: URL) throws -> URL {
        let out = Paths.sub("update").appendingPathComponent("unzipped")
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        try run("/usr/bin/ditto", ["-x", "-k", zip.path, out.path])
        return out.appendingPathComponent("Tether.app")
    }

    static func mountAndCopy(_ dmg: URL) throws -> URL {
        let mount = Paths.sub("update").appendingPathComponent("mnt")
        try? FileManager.default.createDirectory(at: mount, withIntermediateDirectories: true)
        try run("/usr/bin/hdiutil", ["attach", "-nobrowse", "-readonly", "-mountpoint", mount.path, dmg.path])
        defer { try? run("/usr/bin/hdiutil", ["detach", mount.path, "-force"]) }
        let out = Paths.sub("update").appendingPathComponent("unzipped")
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        try run("/usr/bin/ditto", [mount.appendingPathComponent("Tether.app").path, out.appendingPathComponent("Tether.app").path])
        return out.appendingPathComponent("Tether.app")
    }

    /// Same bundle id, the advertised version, and a valid signature.
    static func verify(_ app: URL, version: String) throws {
        guard let b = Bundle(url: app), b.bundleIdentifier == Bundle.main.bundleIdentifier else {
            throw UpdateError(message: "The download isn't Tether")
        }
        let v = b.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        guard v == version else { throw UpdateError(message: "Version mismatch (\(v))") }
        try run("/usr/bin/codesign", ["--verify", "--deep", "--strict", app.path])
    }

    /// A tiny script waits for this process to quit, swaps the bundle and opens the new one.
    static func replaceAndRelaunch(with app: URL) throws {
        let current = Bundle.main.bundleURL
        let pid = ProcessInfo.processInfo.processIdentifier
        let script = """
        while kill -0 \(pid) 2>/dev/null; do sleep 0.2; done
        rm -rf "\(current.path).old"; mv "\(current.path)" "\(current.path).old" && \
        /usr/bin/ditto "\(app.path)" "\(current.path)" && rm -rf "\(current.path).old" || mv "\(current.path).old" "\(current.path)"
        /usr/bin/xattr -dr com.apple.quarantine "\(current.path)" 2>/dev/null
        open "\(current.path)"
        """
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", script]
        try p.run()
        DispatchQueue.main.async { NSApp.terminate(nil) }
    }
}

extension AppModel {
    func saveGithubToken(_ token: String) {
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard GitHubReleases.looksLikeToken(t) else { return toast("That doesn't look like a GitHub token") }
        if Keychain.write(Keychain.githubService, t) {
            v5.update.tokenSet = true
            Log.info("update.token_saved", ["token": GitHubReleases.redact(t)])
            MacUpdater.shared.check(manual: true)
        } else {
            toast("Couldn't save the token to the Keychain")
        }
    }

    func removeGithubToken() {
        Keychain.write(Keychain.githubService, nil)
        v5.update.tokenSet = false
        v5.update.phase = .idle
    }

    func checkForUpdates(manual: Bool = true) {
        MacUpdater.shared.model = self
        MacUpdater.shared.check(manual: manual)
    }

    func installUpdate() {
        MacUpdater.shared.model = self
        MacUpdater.shared.install()
    }

    /// Daily background check (when on and a source is configured).
    func scheduleUpdateChecks() {
        guard !demo else { return }
        MacUpdater.shared.model = self
        let t = Timer(timeInterval: 6 * 3600, repeats: true) { [weak self] _ in self?.dailyUpdateCheck() }
        RunLoop.main.add(t, forMode: .common)
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in self?.dailyUpdateCheck() }
    }

    private func dailyUpdateCheck() {
        guard settings.autoUpdateCheck, v5.update.tokenSet || settings.updateFeed?.isEmpty == false else { return }
        if let last = v5.update.lastCheck, Date().timeIntervalSince(last) < 20 * 3600 { return }
        MacUpdater.shared.check(manual: false)
    }
}
