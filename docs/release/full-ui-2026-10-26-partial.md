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
Runner завершился с ошибкой; итог и ограничения зафиксированы ниже. Лог:
`D:/CodexWork/veycad-integration-v3-full-ui.log`. Физическая arm64/no-GMS
приёмка, просмотр/прослушивание результатов и проверка подписанного release APK
остаются открытыми. API 26 AVD содержит Google APIs и не закрывает no-GMS gate.

### V3: обнаруженные ошибки до завершения прогона

Промежуточный срез на этапе запуска сценария №135: четыре ошибки.
Полный список зарегистрированных падений дополнен ниже; итогового XML нет:

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

Изолированная проверка двух упавших GLES-сценариев на API 26:
**2/2 PASS**, failures/errors/skipped = 0, 5,034 секунды. AVD
`VeycadRelease_API26`, host GPU RTX 3060 Laptop (OpenGL ES 2.0 translator).
Установлены те же V3 APK: app SHA-256
`06b1baceeab691b2c7d7826951c2ba1b919371ac1aac9121ab830a463e398dbf`,
test SHA-256 `3b1347db8ccb435a0cdc5deba118baf3c95d5e155c7ef42f3e7f38b7c44069c6`.
XML и logcat сохранены в `D:/CodexWork/veycad-v3-gles-api26-*`.
Это не отменяет failures на API 36 и не доказывает причину: после полного
прогона требуется изолированное повторение на том же API 36 AVD, затем
разделение влияния версии Android, GL-драйвера и порядка тестов.


### V3: остановка и сохранённые результаты

Run ID `20261025-223903-734`, снимок `b9105cdc3bf5b96b8439a10bcc23730141179693`.
Runner завершился с exit 1: `INSTRUMENTATION_ABORTED: System has crashed`.
Из instrumentation.txt: 235 стартов, 227 terminal PASS, 7 terminal FAIL.
Итого завершены 234/264 сценария; №235 начат без результата, ещё 29 не начаты.
Седьмое падение произошло при закрытии Activity, а не при проверке выбора стиля.

Дополнительно к четырём ошибкам выше:

| Сценарий | Свидетельство |
| --- | --- |
| RenderProgressScreenTest.new_operation_shows_progress_without_scrolling_and_updates_preserve_user_position | Строка 38: progressTitle остаётся видимым после прокрутки |
| SmartFramingDeviceTest.compactCacheUsesExactPhysicalIdentityAndCorruptionDoesNotReachReady | HybridProject:116 отвергает разные снимки с одним ID ревизии в fixture |
| StylePickerScreenTest.all_styles_opens_the_same_picker_and_cancel_preserves_the_selected_style | UiTestFixtureRule.after:74: ActivityScenario.close ждёт DESTROYED, последнее состояние PAUSED |

Прерванный №235: StyleVideoPreviewScreenTest.a_manual_preview_keeps_moving_after_one_loop_and_releases_the_view_on_cancel.
Причина системного падения пока не установлена. `DeadSystemException` в SystemUI
и launcher подтверждает потерю Android system service, но не определяет виновника.
Итоговый screens/tests.xml содержит 59 байт, не является валидным XML;
фазы atomic_import_restart и project_recovery не выполнены.

Сбор AAC первоначально завершился отдельной ошибкой окружения: runner выбрал
Windows Store alias `python`. Это не доказательство повреждения AAC. После
восстановления Android архив повторно скопирован бинарно и прошёл
`tools/verify_source_aac.py --validate-archive` с явным исполняемым Python.
Для следующих прогонов необходимо задать `PYTHON` на рабочий интерпретатор.

Сохранено локально:
- `D:/CodexWork/veycad-v3-salvaged-source-audio.tar`, 15 133 696 байт,
  SHA-256 `ae0f7ac09f38bde481e3cd66864e14a6b5787302b1bec09f46e36497160c15e4`;
- `D:/CodexWork/veycad-v3-salvaged-preview-evidence`, 35 файлов;
- исходные instrumentation/logcat в архиве run ID выше.

Проверка структуры архива не заменяет прослушивание и визуальную приёмку.
На последней проверке adb устройств не обнаружил; изолированный API 36
повтор двух GLES-сценариев ещё не запускался.

[PR #93](https://github.com/Veycad/Veycad/pull/93) исправляет только fixture
SmartFraming: начальные original/current получают один согласованный снимок.
Проверки повреждения кэша и production-валидация не ослаблены. После исправления
целевой тест API 26 прошёл 1/1 (0,552 секунды), сборка UI APK и clean baselineVerify
успешны (6 минут 17 секунд). Проверка в общей сборке и API 36 остаётся открытой.


### V3: изолированные повторы API 36

После подтверждения отсутствия работающего эмулятора запущен тот же AVD
AutoEditUi_API36 с host GPU, без загрузки/сохранения snapshot. Повторно установлены
те же APK с SHA-256, указанными выше; код и assertions не менялись.

- Два GLES-сценария: 1 PASS / 1 FAIL, 2,024 секунды. Сравнение manual-window
  display/encoder прошло; metadata-rotation снова упало на байте 0.
- Ещё три отдельных запуска metadata-rotation: FAIL, PASS, FAIL. Следовательно,
  воспроизведение нестабильно даже без полного набора тестов. Причина пока не
  установлена; это не доказательство ошибки только драйвера или только fixture.
- RenderProgress + StylePicker: 2/2 PASS, 7,653 секунды.
- ManualMvp import/trim/undo/reopen/landscape/export: 1/1 PASS, 42,968 секунды.

Логи и XML сохранены локально с префиксами `veycad-v3-gles-api36-isolated`,
`veycad-v3-rotation-isolated-{1,2,3}`, `veycad-v3-progress-picker-isolated`,
`veycad-v3-manual-landscape-isolated` в `D:/CodexWork`.
Эти результаты не отменяют ошибки полного прогона.

При чтении ManualMvpScreenTest.landscapeLargeFontMissingSourceShowsRecoverableErrorAndSelectedFormat
найдена утечка конфигурации: тест устанавливает `wm size 320x640`, а в finally
вызывает `wm size reset`, теряя предыдущее override 320x480 полного runner.
Это подтверждённая ошибка восстановления fixture; причинная связь с поздним
RenderProgress failure ещё требует проверки последовательности. Следующий шаг:
восстанавливать исходный override, затем проверить размер до/после и следующий
сценарий прогресса. Production-поведение ради устранения этого эффекта не менять.
