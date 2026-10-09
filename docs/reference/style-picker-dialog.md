# Style picker: centred dialog

> Historical UI check from 2026-09-07. The current production catalogue contains
> four selectable styles and six unavailable placeholders; see
> `MontageStyleCatalog.kt` and REV-027 in [todo.md](../../todo.md).

Implemented variant 2 on 2026-09-07. The heading and Cancel/Apply actions remain outside the scrolling style list. Selection is staged until Apply; unavailable styles cannot be selected.

## Verified

- `testDebugUnitTest lintDebug assembleDebug`: BUILD SUCCESSFUL. Three pre-existing lint warnings remain.
- Debug APK installed on Android API 36 emulator, 1080 × 2400.
- Eight-entry debug fixture scrolled from the first to the last style; selected and applied entry 8. Result text: `Применён: Тестовый стиль 8`.
- Heading bounds stayed `[94,303][986,388]`; Apply stayed `[550,1982][986,2118]` before and after scrolling.
- Actual main screen still selected Sigma after the fixture test. Production catalogue retains two entries; six dummy entries exist only in the debug preview activity.
- Actual two-entry dialog and scrolled eight-entry dialog visually inspected.

Screenshots: `artifacts/design/style-picker/real-two-styles.png`, `eight-top.png`, `eight-bottom.png`, `eight-selected.png`.

Samsung hardware, landscape and enlarged system fonts were not covered by this UI run.
