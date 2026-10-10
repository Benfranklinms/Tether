package dev.benfranklin.tether

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * The CI emulator run, in a fixed order: the reply / MediaStore tests first (they only need a
 * quiet system), then the real-call tests, which order themselves so the decline runs last
 * (rejecting a call is the hardest thing on the emulator's modem). scripts/emulator-tests.sh
 * runs only this class.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(DeviceTests::class, DeviceCallTests::class)
class DeviceSuite
