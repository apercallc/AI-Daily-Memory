package com.apercallc.aidailymemory.recording.permission

/** Device makers whose background-app limits need their own instructions. */
enum class DeviceVendor { SAMSUNG, OTHER }

/**
 * Picks which battery instructions to show. Samsung's One UI keeps its own "Sleeping apps" and
 * "Deep sleeping apps" lists on top of Android's battery optimization, so allowing unrestricted
 * use alone is not enough there.
 */
object BatteryGuidance {
    fun vendorOf(manufacturer: String?): DeviceVendor =
        if (manufacturer.orEmpty().trim().equals("samsung", ignoreCase = true)) {
            DeviceVendor.SAMSUNG
        } else {
            DeviceVendor.OTHER
        }
}
