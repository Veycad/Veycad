# Совместная проверка релизных PR

Вердикт: **не готово к релизу**. Общий набор изменений собирается, но выбранный
Android-прогон содержит четыре ошибки. Это не проверка подписанного релизного APK.

## Что проверено

Локально объединены деревья PR #70–81 без изменения main и без слияния PR на GitHub.
`git merge-tree --write-tree` не обнаружил текстовых конфликтов. Промежуточные
commit-tree объекты использовались только для локальной проверки совместимости.
PR #81 зависит от #79; при объединении общий предок учтён, изменения не применялись дважды.

- Main: `beca0960fbac96837e39a92ed751909eb9e83715`.
- Локальный снимок: `b9615682de40e256c521a3f9d1035bd9e4cac1a0` (не опубликован).
- Дерево: `bb7f0c0a16d89523da2a7df589da4950f8756e9a`.
- Все исходные SHA PR, состояние CI и результаты каждого Android-теста:
  [машиночитаемый отчёт](integration-2026-10-26.json).
- x86_64 uiTest APK SHA-256:
  `40f49c8212b58f74b5c3556005d7cbb2e38c9dccd85ef1efaab2b5f92d896a72`.

`baselineVerify :app:assembleUiTest :app:assembleUiTestAndroidTest` с явным путём
установленного Python: PASS, 1392 JVM и 338 Python тестов без ошибок и пропусков,
lint, security contract, debug APK и instrumentation compilation. Запуск без clean.

На AutoEditUi_API36 (Android 16):
18 выбранных Android-тестов, **14 PASS / 4 FAIL / 0 SKIP**, процесс не упал.
Проверены все StylePickerScreenTest и InformationalDialogsTest, регрессии #79–81,
прямые кадры preview и ранее падавшие проверки памяти/текста/ручного экспорта.
Это часть полного набора, не полный UI PASS.

## Оставшиеся ошибки

| Проверка | Наблюдение | Следующий шаг |
| --- | --- | --- |
| StylePickerScreenTest.all_available_styles_apply_their_own_source_and_music_requirements | AmbiguousViewMatcherException: «Под свою музыку» совпадает с двумя TextView | Исправить общий помощник выбора карточки из #71; сохранить поддержку маленького экрана и всех стилей. Это ошибка автоматизации, пользовательское падение не доказано. |
| ManualMvpScreenTest.importFourFormatsTrimUndoReopenAndLandscapeExportShareOneRevision | Preview must never create an MP4 | Установить происхождение найденного файла и проверить границу preview/export; не ослаблять запрет промежуточного рендера. |
| DraftPreviewControllerDeviceTest.pausedNormalMinuteDropsProducerAnalysisAndKeepsOnlyBoundedOwnedPages | Actual paused native display must be ready | Проверить готовность native-кадра и владельцев памяти на минутном проекте. |
| GlesPreviewMemoryDeviceTest.rich_caption_and_legacy_pixels_share_export_layout_and_reuse_charged_rgba_uploads | Первый элемент массива: expected 0, actual -1 | Разобрать сравнение экспортной и preview-раскладки текста/пикселей. |

`directSurfaceFirstReverseAndSourceChangePreserveInputsWithoutPreviewFile` здесь
прошёл, но падал с `Expected direct frame 0` в UI CI #78. Один локальный PASS
не объясняет это расхождение и не закрывает стабильность сценария.

Сценарии больших PCM-буферов, экспорта с эффектами/сохранённым текстом,
многократной остановки и восстановления редактора после отмены экспорта прошли
совместно. Это подтверждает только проверенные сценарии исправлений #79–81.

## CI и условия дальнейшей приёмки

Снимок CI на 2026-10-25 18:47:34 UTC: baseline и legacy-save успешны у #70–80;
UI #71/#72 cancelled, #73/#74/#78 failure, остальные UI выполнялись; у #81
все три проверки выполнялись. Это исторический снимок, перед слиянием нужен
новый запрос результатов для последних SHA. Отмена не считается PASS.

В ограниченном логе UI #78 повторились ошибки PCM и остановки контроллера,
а также перечисленные выше native-ошибки. Проверка AAC-архива затем не получила
полный набор данных из-за падения процесса; это не самостоятельное доказательство
дефекта AAC. [Исходный job](https://github.com/Veycad/Veycad/actions/runs/40219611325/job/121248109265).

После исправлений повторить затронутые проверки и совместную проверку нового
дерева. Состав снимка должен быть зафиксирован заново. Физические arm64 устройства,
no-GMS, человеческая приёмка видео/звука, подпись/обновление, контакты/политика,
права на музыку и полный UI CI остаются открытыми. Слияние и отправка в магазин
требуют явного решения владельца; этим отчётом они не разрешаются.
