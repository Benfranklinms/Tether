import Foundation

/// Calls `onChange` (debounced) when entries in a folder are added, removed or renamed, e.g. a received
/// photo deleted or moved to the Trash in Finder. One kqueue vnode source (O_EVTONLY) per folder.
final class FolderWatcher {
    let path: String
    private let source: DispatchSourceFileSystemObject
    private var pending: DispatchWorkItem?

    /// nil when the folder does not exist yet; the engine tries again on the next check.
    init?(path: String, queue: DispatchQueue, onChange: @escaping () -> Void) {
        let fd = open(path, O_EVTONLY)
        guard fd >= 0 else { return nil }
        self.path = path
        source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: [.write, .delete, .rename], queue: queue)
        source.setEventHandler { [weak self] in
            guard let self else { return }
            self.pending?.cancel()
            let work = DispatchWorkItem(block: onChange)
            self.pending = work
            queue.asyncAfter(deadline: .now() + 0.5, execute: work)
        }
        source.setCancelHandler { close(fd) }
        source.resume()
    }

    deinit {
        pending?.cancel()
        source.cancel()
    }
}
