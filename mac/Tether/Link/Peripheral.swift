import CoreBluetooth
import Foundation
import TetherCore

enum BluetoothStatus: Equatable {
    case unknown, on, off, unauthorized, unsupported
}

protocol PeripheralDelegate: AnyObject {
    /// A phone subscribed; `maxFragment` is its notify payload limit (ATT MTU - 3).
    func linkUp(maxFragment: Int)
    func fragment(_ data: Data)
    func linkDown()
    func bluetooth(_ status: BluetoothStatus)
}

/// The Mac's side of the Bluetooth link: a GATT peripheral that advertises Tether's service
/// and exchanges fragments with one phone at a time over a single characteristic (write
/// without response from the phone, notify to the phone), with notify flow control.
/// All callbacks arrive on `queue`, the engine queue.
final class Peripheral: NSObject, CBPeripheralManagerDelegate {
    private let queue: DispatchQueue
    private weak var delegate: PeripheralDelegate?
    private var manager: CBPeripheralManager!
    private let serviceUUID = CBUUID(string: TetherProtocol.serviceUUID)
    private let charUUID = CBUUID(string: TetherProtocol.characteristicUUID)
    private var characteristic: CBMutableCharacteristic?
    private var central: CBCentral?
    private var outbox: [Data] = []
    private var wantAdvertising = false
    private var serviceAdded = false

    init(queue: DispatchQueue, delegate: PeripheralDelegate) {
        self.queue = queue
        self.delegate = delegate
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: queue, options: [CBPeripheralManagerOptionShowPowerAlertKey: false])
    }

    /// Advertise while paired or pairing; idle otherwise so nearby phones don't connect for nothing.
    func setAdvertising(_ on: Bool) {
        wantAdvertising = on
        applyAdvertising()
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
        delegate?.linkDown()
        // CoreBluetooth has no "disconnect central" call for peripherals; re-publishing the
        // service forces the link to reset.
        republish()
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
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
        Log.info("ble.state", ["state": "\(p.state.rawValue)"])
    }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            Log.error("ble.add_service_failed", error)
            return
        }
        serviceAdded = true
        applyAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ p: CBPeripheralManager, error: Error?) {
        if let error { Log.error("ble.advertise_failed", error) } else { Log.info("ble.advertising") }
    }

    func peripheralManager(_ p: CBPeripheralManager, central c: CBCentral, didSubscribeTo ch: CBCharacteristic) {
        guard ch.uuid == charUUID else { return }
        if let current = central, current.identifier != c.identifier {
            Log.info("ble.second_central_ignored")
            return
        }
        central = c
        outbox.removeAll()
        p.setDesiredConnectionLatency(.low, for: c)
        Log.info("ble.subscribed", ["max": "\(c.maximumUpdateValueLength)"])
        delegate?.linkUp(maxFragment: c.maximumUpdateValueLength)
    }

    func peripheralManager(_ p: CBPeripheralManager, central c: CBCentral, didUnsubscribeFrom ch: CBCharacteristic) {
        guard c.identifier == central?.identifier else { return }
        central = nil
        outbox.removeAll()
        Log.info("ble.unsubscribed")
        delegate?.linkDown()
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests where r.characteristic.uuid == charUUID {
            if central == nil || r.central.identifier == central?.identifier, let v = r.value {
                delegate?.fragment(v)
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
        publish()
    }

    private func applyAdvertising() {
        guard manager.state == .poweredOn, serviceAdded else { return }
        if wantAdvertising && !manager.isAdvertising {
            manager.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
                CBAdvertisementDataLocalNameKey: "Tether",
            ])
        } else if !wantAdvertising && manager.isAdvertising {
            manager.stopAdvertising()
        }
    }

    private func reset() {
        if central != nil {
            central = nil
            delegate?.linkDown()
        }
        outbox.removeAll()
        characteristic = nil
        serviceAdded = false
    }
}
