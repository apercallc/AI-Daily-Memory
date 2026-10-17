package com.apercallc.aidailymemory.recording.design

/**
 * The one palette for the website, the web app, the phone app and the watch (docs/design).
 *
 * Plain ARGB values with no UI dependency so every client builds its theme from the same numbers.
 * The first group per mode mirrors the CSS custom properties in `website/app/app.css`; a test fails
 * if they drift apart. The second group is the Material roles those colors do not name (tinted
 * containers and layered surfaces), derived once here so phone and watch agree.
 *
 * Red is the brand mark and the recording color. It is also the primary button color, so on the
 * phone and watch a recording state is never shown by a button alone: it always has a status card,
 * label or indicator that uses the fixed status colors in the app (AGENTS.md rule 1).
 */
object BrandTokens {
    /** Warm paper, ink and red. Matches `:root` in app.css. */
    object Light {
        const val PAPER = 0xFFF6F1E7L
        const val PAPER_DEEP = 0xFFEFE8D9L
        const val CARD = 0xFFFBF8F1L
        const val INK = 0xFF211B14L
        const val INK_SOFT = 0xFF57503FL
        const val INK_FAINT = 0xFF6E6555L
        const val RULE = 0xFFD9CFBAL
        const val RED = 0xFFC8361FL
        const val RED_DEEP = 0xFF9C2612L
        const val SLATE = 0xFF3D5A6CL
        const val OK = 0xFF2F6B3AL
        const val WARN = 0xFF8A5A00L
        // Deliberately distinct from WARN: "paused" is a calm, user-initiated state, not an
        // alert, and this app's color semantics are safety signals (AGENTS.md) -- collapsing
        // the two to one hue would make a paused session indistinguishable from an actual
        // warning at a glance. Same amber family, a visibly different shade.
        const val PAUSED = 0xFF7A5900L
        const val FOCUS = 0xFF1C5FB8L
        const val WHITE = 0xFFFFFFFFL

        // Material roles built on the palette.
        const val RED_CONTAINER = 0xFFF6DDD6L
        const val ON_RED_CONTAINER = 0xFF5A1308L
        const val SLATE_CONTAINER = 0xFFDCE6ECL
        const val ON_SLATE_CONTAINER = 0xFF17262FL
        const val WARN_CONTAINER = 0xFFF3E3BFL
        const val ON_WARN_CONTAINER = 0xFF3A2600L
        const val ERROR = 0xFFB3261EL
        const val ERROR_CONTAINER = 0xFFF9DDD6L
        const val ON_ERROR_CONTAINER = 0xFF410E0BL
        const val SURFACE_LOWEST = 0xFFFFFDF8L
        const val SURFACE_LOW = 0xFFF9F5EBL
        const val SURFACE_HIGH = 0xFFF2EBDDL
        const val SURFACE_HIGHEST = 0xFFEAE2D1L
        const val OUTLINE = 0xFF857A66L
    }

    /** The same palette in the dark. Matches the dark `:root` in app.css. */
    object Dark {
        const val PAPER = 0xFF17130EL
        const val PAPER_DEEP = 0xFF1F1912L
        const val CARD = 0xFF221C14L
        const val INK = 0xFFF1EAD9L
        const val INK_SOFT = 0xFFCDC3ACL
        const val INK_FAINT = 0xFFA89E88L
        const val RULE = 0xFF3B3326L
        const val RED = 0xFFEF6A52L
        const val RED_DEEP = 0xFFFF8A73L
        const val SLATE = 0xFF8FB2C7L
        const val OK = 0xFF7BC589L
        const val WARN = 0xFFE0B04AL
        const val PAUSED = 0xFFF5BF48L
        const val FOCUS = 0xFF7FB2FFL

        const val ON_RED = 0xFF3A0B03L
        const val RED_CONTAINER = 0xFF7A2415L
        const val ON_RED_CONTAINER = 0xFFFFDAD2L
        const val ON_SLATE = 0xFF0F2430L
        const val SLATE_CONTAINER = 0xFF2A4352L
        const val ON_SLATE_CONTAINER = 0xFFD3E6F2L
        const val ON_WARN = 0xFF3A2900L
        const val WARN_CONTAINER = 0xFF5C4200L
        const val ON_WARN_CONTAINER = 0xFFFFE3A8L
        const val ERROR = 0xFFFFB4ABL
        const val ON_ERROR = 0xFF690005L
        const val ERROR_CONTAINER = 0xFF93000AL
        const val ON_ERROR_CONTAINER = 0xFFFFDAD6L
        const val SURFACE_LOWEST = 0xFF120F0AL
        const val SURFACE_LOW = 0xFF1B160FL
        const val SURFACE_HIGH = 0xFF2B241AL
        const val SURFACE_HIGHEST = 0xFF352D21L
        const val OUTLINE = 0xFF8F8570L

        /** Watch only: the OLED background, a shade under [PAPER] so the screen stays dark. */
        const val WATCH_BACKGROUND = 0xFF0E0B07L
    }

    /** The saturated dot beside "Recording": identical in every mode and on every device. */
    const val RECORDING_DOT = 0xFFFF3B30L

    /** Shape radii in dp, shared by phone and watch (the web uses 8 / 12 / 999 px). */
    object Radius {
        const val SMALL = 8
        const val MEDIUM = 12
        const val LARGE = 16
    }

    /**
     * Spacing in dp shared by phone and watch. The phone's full 4dp-grid scale lives in its own
     * richer `Spacing` (ui/theme/Tokens.kt, M3-specific); the watch's compact layout only ever
     * needs these few sizes, so they are named and shared here instead of staying ad hoc dp
     * literals scattered through Wear composables.
     */
    object Spacing {
        /** The watch's tightest gap: vertical padding on a single-line notice. */
        const val HAIRLINE = 2
        const val XS = 4
        const val SM = 8
        /** Minimum touch target for any interactive element (Android accessibility). */
        const val MIN_TOUCH_TARGET = 48
    }
}
