# Повторная интеграционная проверка PR #70–84

Это проверка локального совмещённого снимка, не слияние PR и не разрешение на релиз.
Точные исходные коммиты, CI-срез и результаты обеих попыток: [JSON](integration-2026-10-26-v2.json).
Предыдущий [отчёт](integration-2026-10-26.md) остаётся историческим свидетельством четырёх падений.

## Проверенная сборка

- Base main: `beca0960fbac96837e39a92ed751909eb9e83715`.
- Локальный snapshot: `5a717be291a68324b97372988e745ee1e1cbd0a4`.
- Git tree: `0bf9fb6d8c503d99c90a2a17aa603e0a007492aa`.
- PR #70–84 совмещены без текстовых конфликтов; snapshot не опубликован.
- x86_64 uiTest APK SHA-256: `972c8beaaea2df4a99136a09ac57ba6deeef03b4c8747907af6a345d85d242c0`.
- API 36, AutoEditUi_API36, исходные настройки 320×480 / density 160 / font scale 1.0.
  Тесты сами меняют размер, ориентацию и шрифт в соответствующих сценариях.

`baselineVerify :app:assembleUiTest :app:assembleUiTestAndroidTest` — PASS:
1392 JVM и 338 Python тестов без failures/errors/skips, lint, security contract,
debug/uiTest APK и instrumentation. Это инкрементальная сборка, не `clean`.

## Native-сценарии и вмешательство в окружение

Первый запуск завершился с **11/20 PASS, 9 failures**, без errors/skips.
Семь тестов StylePickerScreenTest и два InformationalDialogsTest не получили фокус окна.
`dumpsys window` показал `Application Not Responding: com.android.settings`, а `lastanr`
назвал `com.android.settings.FallbackHome` и таймаут FocusEvent. Блокировка экрана отсутствовала.

Во время прогона выполнен `am force-stop com.android.settings`; затем текущим окном стал
Veycad. Код приложения, тесты и таймауты не менялись. Все последующие сценарии прошли.
После завершения первого запуска оба затронутых UI-класса повторены целиком: **10/10 PASS**.
Итого для **20 уникальных выбранных тестов есть успешные результаты**, но это не один
непрерывный зелёный прогон. Исходные девять failures сохранены в JSON, не заменены повтором.

| Набор | Успешные сценарии |
| --- | ---: |
| StylePickerScreenTest | 7, повтор после системного ANR |
| InformationalDialogsTest | 3, полный класс повторён |
| ManualMvpScreenTest | 3 |
| DraftPreviewControllerDeviceTest | 4 |
| GlesPreviewMemoryDeviceTest: текст и повторные кадры | 1 |
| GlesFrameCompositorDeviceTest: текст на display/encoder | 1 |
| SourceAudioDeviceTest: большие PCM-буферы | 1 |

Проверены импорт/trim/reorder/undo, сохранённая ревизия, экспорт/share, landscape,
background/recreate/cancel, ошибка и повтор, bounded minute preview, 640 stop requests,
reverse/source-change, overlap/text parity, стабильность текста и PCM samples/PTS.
Четыре падения прежнего снимка имеют успешные результаты после исправлений в #71, #82–84.
Однократный успешный direct-frame сценарий не доказывает отсутствие редкого сбоя CI.

## CI и пределы вывода

CI-срез в JSON привязан к проверенным SHA и времени наблюдения: baseline успешен у #70–83,
у #84 тогда выполнялся. Полный UI CI не был зелёным: failure/cancelled/in_progress остаются
явными результатами, а не заменяются локальными выбранными тестами.

После создания снимка в #70 и #75 внесены только подтверждённые публичные имя и email;
эти Markdown-коммиты не входят в проверенное дерево и не меняют APK.

Открыты: полный интегрированный UI-прогон, стабильность direct-frame сценария, физические
arm64/no-GMS устройства, визуальная и слуховая приёмка готовых роликов, сценарии камеры,
TalkBack, окончательная политика и публичный URL, права на музыку, аккаунт, постоянная
подпись и проверка обновления. Нужна повторная приёмка точного подписанного release APK.
Ни один PR не слит, приложение в магазин не отправлено.
