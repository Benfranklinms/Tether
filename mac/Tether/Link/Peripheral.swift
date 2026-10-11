import CoreBluetooth
import Foundation
import TetherCore

enum BluetoothStatus: Equatable {
    case unknown, on, off, unauthorized, unsupported
}

/// What the radio side is doing, for the pairing window's status line and the logs.
struct RadioDiag: Equatable, CustomStringConvertible {
    var advertising = false
    var advertiseError: String?
    var serviceReady = false
    /// A phone has written to us or subscribed on this link.
    var phoneSeen = false
    var subscribed = false
    var earlyWrites = 0

    var description: String {
        "adv=\(advertising) svc=\(serviceReady) phone=\(phoneSeen) sub=\(subscribed) early=\(earlyWrites)"
            + (advertiseError.map { " advErr=\($0)" } ?? "")
    }
}

protocol PeripheralDelegate: AnyObject {
    /// A phone subscribed; `maxFragment` is its notify payload limit (ATT MTU - 3).
    func linkUp(maxFragment: Int)
    func fragment(_ data: Data)
    func linkDown()
    func bluetooth(_ status: BluetoothStatus)
    func radio(_ diag: RadioDiag)
    /// The current phone has a working secure session (a newer subscriber must not steal it).
    func linkEstablished() -> Bool
    /// A session is up or pairing is open: a quiet central must not be treated as stale.
    func linkBusy() -> Bool
}

/// The Mac's side of the Bluetooth link: a GATT peripheral that advertises Tether's service
/// and exchanges fragments with one phone at a time over a single characteristic (write
/// without response from the phone, notify to the phone), with notify flow control.
/// All callbacks arrive on `queue`, the engine queue.
///
/// Writes that arrive before the phone's subscription is reported (CoreBluetooth can deliver
/// them first) are held and replayed once the link is up instead of being dropped, which
/// would leave the handshake waiting forever.
final class Peripheral: NSObject, CBPeripheralManagerDelegate {
    /// Largest fragment we ever notify; 244 fits one LE data-length-extended packet.
    static let maxNotify = 244
    private static let maxEarlyWrites = 64

    private let queue: DispatchQueue
    private weak var delegate: PeripheralDelegate?
    private var manager: CBPeripheralManager!
    private let serviceUUID = CBUUID(string: TetherProtocol.serviceUUID)
    private let charUUID = CBUUID(string: TetherProtocol.characteristicUUID)
    private var characteristic: CBMutableCharacteristic?
    private var central: CBCentral?
    private var outbox: [Data] = []
    private var early: [(UUID, Data)] = []
    private var wantAdvertising = false
    private var serviceAdded = false
    private var advertiseRetry: DispatchWorkItem?
    private var shutDown = false
    private var advWatchdog: DispatchSourceTimer?
    /// Last time the current central wrote to us (ms since boot): a central that has gone quiet
    /// while no secure session is up is stale (the phone crashed without a disconnect).
    private var lastHeard: UInt64 = 0
    private static let staleCentralMs: UInt64 = 45_000
    private var diag = RadioDiag() { didSet { if diag != oldValue { delegate?.radio(diag) } } }

    init(queue: DispatchQueue, delegate: PeripheralDelegate) {
        self.queue = queue
        self.delegate = delegate
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: queue, options: [CBPeripheralManagerOptionShowPowerAlertKey: true])
        startAdvertisingWatchdog()
    }

    private static func nowMs() -> UInt64 { DispatchTime.now().uptimeNanoseconds / 1_000_000 }

    /// Every 10 s: advertising that should be on but isn't is started again, and a central that
    /// has been silent for 45 s without a secure session is dropped (0.5.4: after a phone crash the
    /// Mac kept the dead connection, stopped advertising, and the phone couldn't find it for minutes).
    private func startAdvertisingWatchdog() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .seconds(10), repeating: .seconds(10), leeway: .seconds(2))
        t.setEventHandler { [weak self] in self?.advertisingWatchdogTick() }
        t.resume()
        advWatchdog = t
    }

    private func advertisingWatchdogTick() {
        guard !shutDown, manager.state == .poweredOn, serviceAdded else { return }
        if central != nil, !(delegate?.linkBusy() ?? true), Self.nowMs() - lastHeard > Self.staleCentralMs {
            Log.warn("ble.stale_central_dropped")
            dropCentral()
            return
        }
        if wantAdvertising && !manager.isAdvertising {
            Log.warn("ble.adv_watchdog_restart", ["linked": "\(central != nil)"])
            diag.advertising = false
            applyAdvertising()
        }
    }

    /// The secure session over Bluetooth ended (timeout, protocol error, the phone went away).
    /// If no new session is up shortly, forget the central and advertise again from scratch so
    /// the phone can find this Mac at once instead of waiting on a dead connection.
    func linkLost() {
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self, !self.shutDown, !(self.delegate?.linkEstablished() ?? false) else { return }
            if self.central != nil {
                Log.info("ble.link_lost_reset")
                self.central = nil
                self.outbox.removeAll()
                self.diag.subscribed = false
                self.republish()
            } else {
                self.refreshAdvertising()
            }
        }
    }

    /// Advertise while paired or pairing; idle otherwise so nearby phones don't connect for nothing.
    func setAdvertising(_ on: Bool) {
        wantAdvertising = on
        applyAdvertising()
    }

    /// Restart advertising from scratch (after wake, or when the pairing window opens).
    func refreshAdvertising() {
        guard manager.state == .poweredOn, serviceAdded else { return }
        if manager.isAdvertising { manager.stopAdvertising() }
        diag.advertising = false
        applyAdvertising()
    }

    /// Stop everything and detach from CoreBluetooth (before the Engine makes a fresh manager:
    /// the in-app equivalent of quitting and reopening Tether).
    func shutdown() {
        shutDown = true
        advertiseRetry?.cancel()
        advWatchdog?.cancel()
        advWatchdog = nil
        if manager.isAdvertising { manager.stopAdvertising() }
        manager.removeAllServices()
        manager.delegate = nil
        if central != nil {
            central = nil
            delegate?.linkDown()
        }
        outbox.removeAll()
        early.removeAll()
        characteristic = nil
        serviceAdded = false
        Log.info("ble.shutdown")
    }

    func send(_ fragment: Data) {
        guard central != nil else { return }
        outbox.append(fragment)
        flush()
    }

    /// Drop the current phone (e.g. it isn't the paired one).
    func dropCentral() {
        guard central != nil else { return }
        central = nil
        outbox.removeAll()
        diag.subscribed = false
        delegate?.linkDown()
        // CoreBluetooth has no "disconnect central" call for peripherals; re-publishing the
        // service forces the link to reset.
        republish()
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        Log.info("ble.state", ["state": "\(p.state.rawValue)", "auth": "\(CBManager.authorization.rawValue)"])
        switch p.state {
        case .poweredOn:
            delegate?.bluetooth(.on)
            publish()
        case .poweredOff:
            delegate?.bluetooth(.off)
            reset()
        case .unauthorized:
            delegate?.bluetooth(.unauthorized)
            reset()
        case .unsupported:
            delegate?.bluetooth(.unsupported)
        default:
            delegate?.bluetooth(.unknown)
        }
    }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            Log.error("ble.add_service_failed", error)
            diag.advertiseError = "service: \(error.localizedDescription)"
            return
        }
        Log.info("ble.service_added")
        serviceAdded = true
        diag.serviceReady = true
        applyAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ p: CBPeripheralManager, error: Error?) {
        let code = error.map { ($0 as NSError).code }
        switch LinkPolicy.advertiseResult(errorCode: code) {
        case .advertising:
            // "Advertising has already started" (CBError 9) means we are advertising.
            Log.info(code == nil ? "ble.advertising" : "ble.advertising_already")
            diag.advertising = true
            diag.advertiseError = nil
        case .retry:
            Log.error("ble.advertise_failed", error)
            diag.advertising = false
            diag.advertiseError = error?.localizedDescription
            scheduleAdvertiseRetry()
        }
    }

    /// Stop and start advertising again shortly (CoreBluetooth sometimes refuses once).
    private func scheduleAdvertiseRetry() {
        advertiseRetry?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, !self.shutDown else { return }
            Log.info("ble.advertise_retry")
            self.refreshAdvertising()
        }
        advertiseRetry = w
        queue.asyncAfter(deadline: .now() + 3, execute: w)
    }

    func peripheralManager(_ p: CBPeripheralManager, central c: CBCentral, didSubscribeTo ch: CBCharacteristic) {
        guard ch.uuid == charUUID else { return }
        let established = delegate?.linkEstablished() ?? false
        switch LinkPolicy.subscriber(current: central?.identifier, incoming: c.identifier, currentLinkEstablished: established) {
        case .ignore:
            Log.info("ble.second_central_ignored")
            return
        case .replace:
            // A stale central (its disconnect never reported, or the phone back under a new
            // identifier) must not block the new link: drop it and take the new one.
            Log.info("ble.central_replaced", ["same": "\(central?.identifier == c.identifier)"])
            central = nil
            outbox.removeAll()
            delegate?.linkDown()
        case .accept:
            break
        }
        central = c
        lastHeard = Self.nowMs()
        outbox.removeAll()
        p.setDesiredConnectionLatency(.low, for: c)
        let maxFragment = min(c.maximumUpdateValueLength, Self.maxNotify)
        Log.info("ble.subscribed", ["max": "\(c.maximumUpdateValueLength)", "early": "\(early.count)"])
        diag.phoneSeen = true
        diag.subscribed = true
        delegate?.linkUp(maxFragment: maxFragment)
        // Replay anything this phone wrote before the subscription was reported.
        let replay = early.filter { $0.0 == c.identifier }.map { $0.1 }
        early.removeAll()
        for f in replay { delegate?.fragment(f) }
    }

    func peripheralManager(_ p: CBPeripheralManager, central c: CBCentral, didUnsubscribeFrom ch: CBCharacteristic) {
        guard c.identifier == central?.identifier else { return }
        central = nil
        outbox.removeAll()
        diag.subscribed = false
        Log.info("ble.unsubscribed")
        delegate?.linkDown()
        refreshAdvertising()
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests where r.characteristic.uuid == charUUID {
            guard let v = r.value else { continue }
            if let current = central, r.central.identifier == current.identifier {
                lastHeard = Self.nowMs()
                delegate?.fragment(v)
            } else {
                // Not subscribed yet (or the subscription callback hasn't arrived): hold it.
                diag.phoneSeen = true
                if early.count < Self.maxEarlyWrites { early.append((r.central.identifier, v)) }
                diag.earlyWrites = early.count
                Log.info("ble.early_write", ["bytes": "\(v.count)"])
            }
        }
        if let first = requests.first { p.respond(to: first, withResult: .success) }
    }

    func peripheralManagerIsReady(toUpdateSubscribers p: CBPeripheralManager) {
        flush()
    }

    // MARK: - internals

    private func flush() {
        guard let ch = characteristic, let c = central else { return }
        while let next = outbox.first {
            guard manager.updateValue(next, for: ch, onSubscribedCentrals: [c]) else { return } // wait for isReady
            outbox.removeFirst()
        }
    }

    private func publish() {
        guard manager.state == .poweredOn, characteristic == nil else { return }
        let ch = CBMutableCharacteristic(
            type: charUUID, properties: [.writeWithoutResponse, .write, .notify], value: nil, permissions: [.writeable]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [ch]
        characteristic = ch
        manager.add(service)
    }

    private func republish() {
        manager.stopAdvertising()
        manager.removeAllServices()
        characteristic = nil
        serviceAdded = false
        diag.advertising = false
        diag.serviceReady = false
        publish()
    }

    private func applyAdvertising() {
        guard manager.state == .poweredOn, serviceAdded else { return }
        if wantAdvertising && !manager.isAdvertising {
            // 128-bit service UUID (18 bytes) + flags (3) + name "Tether" (8) = 29 of 31 bytes,
            // so both fit the legacy advertising packet; macOS moves the name to the scan
            // response if it needs to, and the phone matches on either.
            manager.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
                CBAdvertisementDataLocalNameKey: "Tether",
            ])
        } else if !wantAdvertising && manager.isAdvertising {
            manager.stopAdvertising()
            diag.advertising = false
        }
    }

    private func reset() {
        if central != nil {
            central = nil
            delegate?.linkDown()
        }
        outbox.removeAll()
        early.removeAll()
        characteristic = nil
        serviceAdded = false
        diag = RadioDiag()
    }
}
