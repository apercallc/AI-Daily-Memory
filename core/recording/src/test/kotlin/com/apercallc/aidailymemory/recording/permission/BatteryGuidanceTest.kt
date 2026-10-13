package com.apercallc.aidailymemory.recording.permission

import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryGuidanceTest {
    @Test
    fun `samsung is detected regardless of case or padding`() {
        assertEquals(DeviceVendor.SAMSUNG, BatteryGuidance.vendorOf("samsung"))
        assertEquals(DeviceVendor.SAMSUNG, BatteryGuidance.vendorOf("SAMSUNG"))
        assertEquals(DeviceVendor.SAMSUNG, BatteryGuidance.vendorOf(" Samsung "))
    }

    @Test
    fun `other makers and missing values fall back to generic guidance`() {
        assertEquals(DeviceVendor.OTHER, BatteryGuidance.vendorOf("Google"))
        assertEquals(DeviceVendor.OTHER, BatteryGuidance.vendorOf(""))
        assertEquals(DeviceVendor.OTHER, BatteryGuidance.vendorOf(null))
    }
}
