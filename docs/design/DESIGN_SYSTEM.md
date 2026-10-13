# Design system

AI Daily Memory uses a shared paper, ink, and red palette across the website, phone, and watch.
`core/recording/.../design/BrandTokens.kt` is the source for the palette and semantic colors;
`website/app/app.css` mirrors its web colors, and `BrandTokensTest` checks they stay in sync.

## Color and type

- Light surfaces use warm paper and card tones; dark surfaces use deep brown-black tones. Red is
  the brand accent and recording signal. Slate, green, and amber communicate secondary, success,
  and warning states.
- Android Material 3 schemes and the Wear Material 3 scheme are built from `BrandTokens`. Safety
  states remain fixed semantic tokens regardless of the selected system appearance.
- Android and Wear typography follows Material 3. The web app uses the native system sans-serif
  stack for navigation, headings, controls, and reading text, with monospace reserved for
  timestamps and technical values. It does not download fonts or send font requests to a third
  party.

## Layout

- Phone app: compact windows use the bottom navigation bar. At 600 dp width and above, the app
  switches to a labeled navigation rail and centers screen content within a 960 dp maximum.
- Web app: the app shell uses the shared warm-paper palette, with the main reading column capped
  at 1320 px. Wide layouts use a persistent top navigation; phone layouts use a fixed, labeled
  bottom navigation with safe-area spacing. The notes library keeps its folder sidebar at tablet
  and desktop widths, then moves folder controls and filters into horizontal rows on phones.
  Settings use in-page section links on wide layouts and scrollable section chips on phones.
- Wear OS: use Wear Material 3 components and the dark palette, with the OLED background token.

## Accessibility

Interactive controls keep at least 48 dp on Android and 44 px on the web. Controls need clear
labels, keyboard focus remains visible, motion respects `prefers-reduced-motion`, and layouts
must reflow with large text. Recording state remains visible and is never communicated by color
alone. Validate TalkBack, 200% font scaling, and representative phone/tablet/browser sizes on
devices before release; automated builds do not establish those physical-device checks.
