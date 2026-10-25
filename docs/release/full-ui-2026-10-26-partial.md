# Полный UI-прогон: незавершённый результат

Снимок: `5a717be291a68324b97372988e745ee1e1cbd0a4`.
Команда: `tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`.
Это отдельный полный прогон после [выборочной проверки V2](integration-2026-10-26-v2.md).

При повторной проверке состояния 2026-10-26 handle процесса уже отсутствовал,
процесс runner не найден, `adb` не обнаружил `emulator-5556`. Причина остановки
не установлена. Лог заканчивается стартом сценария №98 из 264, без его результата
и без итогового результата instrumentation. Это не успешный полный прогон.

- Permissions: XML подтверждает **2/2 PASS**, failures/errors/skipped = 0.
- Screens: завершились **97/264** сценариев: **94 PASS, 3 FAIL**.
- Сценарий №98 начат, результат отсутствует; ещё 166 не начаты.
- Итоговый screens XML, последующие restart/recovery gates и полная проверка
  медиасвидетельств этим запуском не подтверждены.

| Сценарий | Наблюдаемая ошибка |
| --- | --- |
| CaptureReviewScreenTest.automaticChoiceSurvivesRecreationAfterOpeningSpecificResultTake | NoActivityResumedException, строка 86, проверка выбранного дубля после открытия picker |
| DraftPreviewControllerDeviceTest.directSurfaceFirstReverseAndSourceChangePreserveInputsWithoutPreviewFile | Expected direct frame 0, awaitFrame:332, вызов:360 |
| DraftPreviewControllerDeviceTest.pausedNormalMinuteDropsProducerAnalysisAndKeepsOnlyBoundedOwnedPages | Удерживается исходный project или producer float array, строка 106 |

Ранее успешные выборочные проверки этих preview-сценариев не отменяют новые failures.
Причины трёх ошибок пока не установлены; приписывать их прежнему Settings ANR
оснований нет. Следующий шаг — воспроизвести затронутые классы, проверить lifecycle,
готовность кадра и освобождение producer-данных, затем повторить полный прогон.
Не увеличивать таймауты и не исключать сценарии только ради зелёного результата.

Локальные исходные свидетельства: `veycad-integration-full-ui.log`,
`veycad-full-ui-live-logcat.txt`, `veycad-capture-review-failure-logcat.txt`
в каталоге `D:/CodexWork`; permissions XML — в checkout, `build/reports/ui-tests/permissions.xml`.
Они не являются опубликованным CI-артефактом и не доказывают физическую device/media-приёмку.
