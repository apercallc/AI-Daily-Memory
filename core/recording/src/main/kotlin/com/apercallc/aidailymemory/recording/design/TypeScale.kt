package com.apercallc.aidailymemory.recording.design

/**
 * Which generic font family each Material text role uses, shared by the phone and the watch.
 *
 * Phone (`compose-material3`) and watch (`wear-compose-material3`) build their type scale from
 * two different `Typography` classes, so this cannot be one Compose `Typography` object the way
 * [BrandTokens] is one color palette. Instead it is the plain decision each platform applies to
 * its own `Typography.copy(...)` call: which roles are serif (editorial/heading voice) and which
 * are monospace (status/label voice). See `ui/theme/Tokens.kt` (phone) and `WearMainActivity.kt`
 * (watch) for where this gets turned into an actual `FontFamily`.
 */
enum class TypeFamily { SERIF, MONOSPACE }

object TypeScale {
    val display = TypeFamily.SERIF
    val headline = TypeFamily.SERIF
    val title = TypeFamily.SERIF
    val label = TypeFamily.MONOSPACE
}
