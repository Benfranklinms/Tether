package dev.benfranklin.tether

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * The CI emulator run, in a fixed order: the 0.5.3 hold/drag crash scenarios (Home panels, Shelf
 * and Transfers rows) first, then the reply / MediaStore tests first (they only need a
 * quiet system), then the real-call tests, which order themselves so the decline runs last
 * (rejecting a call is the hardest thing on the emulator's modem). scripts/emulator-tests.sh
 * runs only this class.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(GestureDeviceTest::class, DeviceTests::class, DeviceCallTests::class)
class DeviceSuite
