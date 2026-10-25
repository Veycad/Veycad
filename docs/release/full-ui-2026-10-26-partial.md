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

## Последующая диагностика и повторная проверка

Исходный незавершённый прогон выше остаётся FAIL/INCOMPLETE. Последующие
результаты не подменяют его и не доказывают успешность всего набора.

- [PR #85](https://github.com/Veycad/Veycad/pull/85): на viewport 320×480
  нижние действия перекрывали кнопку выбора дубля. Исправлена компоновка;
  прошли 4 проверки review и 11 recording/mode, clean baselineVerify.
- [PR #86](https://github.com/Veycad/Veycad/pull/86): сам тест удерживал
  referent через weakRef.get() перед GC. ReferenceQueue подтверждает сборку
  всех 481 объектов без изменения срока ожидания. Финальный запуск класса:
  2 PASS / 1 FAIL (ожидание overlap frame); baselineVerify прошёл.
- [PR #87](https://github.com/Veycad/Veycad/pull/87): диагностика таймаутов
  показала worker внутри GLES20.glGetAttribLocation на SwiftShader. С теми же
  APK на host GPU оба сценария прошли ожидания кадров; итог 1 PASS / 1 FAIL,
  поскольку overlap затем упал на ограничении PCM-буфера, исправляемом в #80.
  Это не production-исправление renderer и не доказательство работы на телефоне.
- [PR #88](https://github.com/Veycad/Veycad/pull/88): два тестовых fixture
  использовали MediaMetadataRetriever как AutoCloseable, недоступный на API 26.
  Старый APK воспроизвёл ClassCastException. После исправления release/finally
  прошли lintUiTest, baselineVerify (1392 JVM + 338 Python), сборка UI APK и
  recovery на API 26: PID 5598 → 5675, 6 файлов проекта без изменения хешей,
  2 проверенных исходных ассета, 4 удалённых временных файла. При обновлении
  тестового APK исходный ролик сохранил SHA256 и mtime.

Для повторного полного прогона создан локальный снимок V3
`b9105cdc3bf5b96b8439a10bcc23730141179693`: V2 + #85–88.
При объединении сохранены оба добавления в начале DraftPreviewControllerDeviceTest:
проверка остановки и диагностический helper. Основная ветка не изменена,
одобрение слияния этим снимком не подразумевается.

Команда: `tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`.
Среда: API 36, host GPU NVIDIA RTX 3060 Laptop, viewport 320×480, density 160.
На момент записи runner запущен; результата ещё нет. Лог:
`D:/CodexWork/veycad-integration-v3-full-ui.log`. Физическая arm64/no-GMS
приёмка, просмотр/прослушивание результатов и проверка подписанного release APK
остаются открытыми. API 26 AVD содержит Google APIs и не закрывает no-GMS gate.

### V3: обнаруженные ошибки до завершения прогона

Runner продолжает работу; следующий список не является итоговым XML-отчётом.
На этапе запуска сценария №135 зарегистрированы четыре ошибки:

| Сценарий | Свидетельство |
| --- | --- |
| CaptureReviewScreenTest.automaticChoiceSurvivesRecreationAfterOpeningSpecificResultTake | SyntheticVideo.create:57, Synthetic AVC encoding timed out до открытия Activity |
| GlesFrameCompositorDeviceTest.surface_texture_metadata_rotation_is_not_applied_twice | Строка 386, compositionPixels отличаются уже в байте 0: -1 против 0 |
| GlesFrameCompositorDeviceTest.supplied_manual_window_has_display_encoder_parity_and_centered_legacy_parity | Строка 197, display/encoder compositionPixels: 0 против -1 в байте 0 |
| ManualMvpScreenTest.importFourFormatsTrimUndoReopenAndLandscapeExportShareOneRevision | Строка 124, Espresso RootViewWithoutFocusException после перехода в landscape |

Для первой ошибки logcat показывает работу кодера вплоть до лимита 20 секунд:
MPEG4Writer получил 1735 кадров из ожидаемых 1770 (59 секунд × 30 fps).
Это свидетельство незавершённой генерации, а не доказательство причины замедления.
Прежняя ошибка перекрытия picker здесь не воспроизведена: сценарий не дошёл до неё.
Таймауты и pixel assertions не ослаблялись. Причины GLES-различий и ошибки
landscape пока не установлены; нельзя объявлять их просто особенностью эмулятора.

Все четыре DraftPreviewControllerDeviceTest в V3 завершились успешно, включая
native overlap/export и проверку освобождения producer-данных; это узкий PASS,
который не отменяет четыре ошибки выше. После завершения полного прогона нужны
изолированные воспроизведения ошибок на тех же APK и сбор более точных данных.
Локальные логи: `veycad-integration-v3-full-ui.log`,
`veycad-integration-v3-live-logcat.txt`, `veycad-integration-v3-gles-logcat.txt`.
