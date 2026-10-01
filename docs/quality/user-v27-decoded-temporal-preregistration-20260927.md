# v27: реальное decoded temporal evidence, план до native

27 сентября2026. Это новый пилотный этап; имя v27 не доказывает успех рендера.
Предыдущий этап v26 дал installed0.1.6/code7 и205Python/578JVMtests pass.
На0.1.6 native монтаж не запускали. Последний actual single источник —
Heartbeat(C)v25, SHA0e2d…9c23; следующая разрешённая серия должна быть A→C.

## Проверяемая проблема

Inspector проверяет authored/scheduled temporal PTS, но не требует валидные
разные decoded primary/secondary PTS. При visible temporal opacity≥0.25
изменение accepted frame in-memory на null/equal decoded PTS/dual=false не
даёт ошибки прежнего offline retention validator. Native evaluate также
сравнивает только scheduled sourceTimeUs/secondarySourceTimeUs. Это показано
на синтетическом faithful fixture, не изменением реальных артефактов.

Реальные v25A/C имеют по292visible echo frames с валидными distinctdecodedPTS,
min separation66656/66666us; проблема не заявляется дефектом этих роликов.
Запрошенный offset−67000us и декодированный gap66.656ms могут различаться
из-за округления к фактическим source frames. Нельзя подменять decoded критерий
плановым67ms или придумывать50ms threshold, подогнанный под эти материалы.

## Минимальное исправление, без изменения монтажа

Прежние scheduled/configured gates, source selection, grammar, audio, speed,
artistic thresholds и ReferenceMontageProfile exception сохраняются.
Visible authored non-reference DOUBLE_EXPOSURE/MIRROR_SLICE требует двух
live decoder inputs, nonnull nonnegative actual decoded PTS и разных timestamps.
Это distinct recorded texture-time evidence, не гарантированная видимая разница
пикселей или полная художественная читаемость. Более сильный decoded readability
threshold не вводится без независимого обоснования.

Recorder берёт timestamp входа, действительно bound на texture unit1. Выбор
debug probe incoming-on-both допускается только для unit proof выбора объекта,
не новым физическим контрольным монтажом. Production default остаётся прежним.
Texture matrices, rotation и rendering rules не меняются.

Explicit evidence method `decoded-texture-pts-v1` и policy сохраняются в raw и
inspector JSON. Policies: `temporal-distinct-pts-v1`, `reference-spatial-v1`,
`no-temporal-layer-v1`. Последняя не означает, что декодирование не нужно
для основного изображения. Reference exception использует прежний exact
native appliesTo/ID; Sigma по-прежнему locked и не запускается.
FEAR opener spatial effects не требуют второго temporal texture.

Offline gate требует explicit policy, согласованную с product/grammar/inspector,
и actual decoded records там, где policy требует temporal contrast. Старые
v25/v23 artifacts не переписываются и не снабжаются вымышленной новой policy.
Под новым контрактом отсутствующее method/policy — unknown, не pass.
Прежняя actual ad-hoc decoded диагностика остаётся историческим доказательством.

## До native

- Unit tests: equal/null/negative decoded PTS при valid scheduled67ms не pass;
  dual=false не pass; обе стороны decoded нужны; opacity boundary0.25.
- Distinct actual66656/66666us при requested67ms разрешены без ослабления
  прежнего scheduled gate; layer opacity<0.25 и spatial/reference условия
  не получают новое ложное требование второго temporal input.
- Binding selector использует exact debugAND condition и один выбранный
  объект для texture binding и recordedsecondary timestamp.
- Offline method/policy unknown/mismatch/malformed records failclosed;
  intentional non-temporal effects и source30fps→output60fps decoder repeats
  не превращаются в дефект. Требование inspector SHA/retention остаётся.
- Полные JVM/Python tests, build/lint/security/signature; новый APK/code и
  frozen dirty-worktree pilot baseline до physical export.

## Зарегистрированная физическая последовательность

Только explicit heartbeat=true, один source и прежняя отдельная Heartbeat music
SHAcc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48.
Новые exclusive prefixes:

1. `user_heartbeat_v27_a_decodedpts_20260927`, ownerA19348088228456.mp4,
   sourceSHA8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca.
2. `user_heartbeat_v27_c_decodedpts_20260927`, ownerCIMG_2249.MOV,
   sourceSHA0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23.

Перед каждой попыткой: source guard against previous actual single,
exact local/runtimeAPK SHA/version, source/musicSHA, local/device targets absent,
previous worker terminal by ps-T. Не перезапускать из-за observationtimeout.
Не выполнять nativeSigma, static/synchronous/texture probe controls или
публичные исходники. После terminal success — MP4/raw/inspector/contact,
device/localSHA, decoded policy checks, fresh AAC с повторным decode, blank
new output-bound human form. Для отказа — honest raw, отсутствие MP4, правильное
разделение material/compute outcome; без вымышленных положительных artifacts.

## Полный объём цели

Все разрешённые A/B/C уже exposed pilots; unused0/sealedcases0. Это не новая
слепая серия и не independent corpus seal. Четыре самостоятельных продукта
по-прежнему требуются; одна Sigma paused/locked, остальные не release-ready.
Owner likedv23HB(A)/FEAR(C) только1×, это не v25/v27 approval/0.5×/checklist.
Открытый вопрос о скорости нового v25C не переадресовывается на новые bytes.
Общий выпуск остаётся недоказанным; этот этап не заменяет independent разнообразные
съёмки или обязательную human acceptance и не публикует новую cloud APK.

## Согласование общей рабочей копии до запуска

Первый общий прогон 0.1.7 завершился ошибкой: 592 JVM tests, два сбоя новых
fixtures в DualityLoopDirectorTest/MaterialSuitabilityTest и пропавший временный
binary-results файл при одновременной работе Gradle из двух чатов. Это не pass.
Пользователь явно разрешил согласовать работу между чатами. Чат «Найти автотесты
приложения» завершил свой пересмотр тестов и передал очередь после устранения
обоих fixtures; production thresholds не ослаблялись.

В объединённую рабочую копию также вошли две воспроизведённые им поправки:
отказ чтения matte cache на усечённом конечном timestamp и обработка margin=0
в offline semantic support без native MaxFilter(1). Его отчёт:
`docs/testing/test-review.md`. TemporalDecodedEvidenceTest содержит 14 методов:
13 исходных и дополнительную проверку смешанной valid/invalid последовательности.

Перед собственной итоговой сборкой root подтвердил отсутствие активных
GradleWrapperMain/GradleWorkerMain/TestMutationCheck и native golden worker.
На emulator-5554 установлена ещё 0.1.6/code7; v27 targets отсутствуют.
Device A/C/music SHA совпали с указанными выше исходниками. Запущен новый
`baselineVerify --rerun-tasks` (session17357), а не принято предположение об
успешном завершении прерванного прогона. На момент этой записи результат сборки
ещё ожидается, физические v27 экспорты не начаты.

Соседний чат получил новую отдельную задачу UI-тестов. Согласовано: до сохранения
exact APK и frozen baseline он не меняет входящие в эту сборку файлы и не
запускает Gradle. После передачи очереди UI-тесты используют отдельные
applicationId `.uitest` и AVD5556; наши пилоты — exact installed APK на5554.
Новые UI-тесты не заявляются частью проверенного v27 APK задним числом.

Итог серии добавлен после выполнения: fresh baseline595JVM/242Pythonpass,
exact0.1.7/code8 установлен и frozen snapshot создан до запуска. A начат
18:30:25UTC, worker terminal подтверждён18:37:21UTC; C начат18:38:16UTC,
terminal подтверждён18:44:49UTC. Оба native/retention/AAC gates прошли,
human forms пусты. Только две зарегистрированные попытки; последний sourceC.
Полные byte bindings и ограничения: `user-v27-native-results-20260927.md`.
