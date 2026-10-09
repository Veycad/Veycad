# APK 0.1.7: фактические кадры временного эха

Локальный universal debug кандидат 0.1.7/code8, min26/target36.
`artifacts/quality/apk/Veykad-0.1.7-decoded-temporal-20260927.apk`
содержит 196329375 байт, SHA-256
`c7d5ee0f55355da8c53f5a3b428dd6f2ca3b80ebac8378cd4c37185dd52ea8f5`.
Сохранённая копия совпала с actual build output; APK signature v2 verified.

## Изменения и проверка

Visible authored temporal layers теперь требуют двух live inputs и валидных,
разных decoded primary/secondary PTS. Recorder фиксирует timestamp того входа,
который действительно привязан ко второму texture sampler. Explicit evidence
method `decoded-texture-pts-v1` и product policy записываются в raw/inspector.
Offline gate проверяет их согласованность и retained per-frame evidence.
Это проверка различия записанных времён кадров, не pixel-readability или
подтверждение художественной приёмки. Прежний scheduled separation и остальные
монтажные пороги сохранены; новый произвольный decoded gap threshold не введён.

Включены результаты согласованного review тестов соседним чатом, в том числе
строгий отказ matte-cache reader на усечённом конечном timestamp. Offline
semantic support корректно обрабатывает margin=0. Подробности этого review:
`docs/testing/test-review.md`. История первого неуспешного concurrent build
и предварительный native план: `user-v27-decoded-temporal-preregistration-20260927.md`.

После передачи очереди root выполнил fresh `baselineVerify --rerun-tasks`
с явно выбранным Python: BUILD SUCCESSFUL, 2m19s, все57tasks executed.
Actual XML: 595 JVM tests /77 suites, failures/errors/skipped0.
Actual Python report: 242 discovered/run, failures/errors/skipped0, passedtrue;
время6.300s. Lint0errors/13warnings, release security contract passed.
TemporalDecodedEvidenceTest:14 методов. Это автоматические проверки логики;
нативное исполнение будет проверено отдельно на зарегистрированных A→C.

## Границы результата

Sigma locked/paused. A/B/C остаются тремя использованными пилотными исходниками,
unused holdout0, sealed releasecases0. Новый APK не доказывает независимого
разнообразного покрытия или полной human acceptance. Отзывы likedv23 относятся
только к тем файлам и скорости1×. Новая облачная доставка не выполнялась.

На момент создания этой записи установка без очистки данных запущена;
runtime identity, frozen snapshot и native результаты будут добавлены после
фактической проверки. APK не перезаписывается последующими UI-тестовыми сборками.

## Подтверждённая установка и фиксация перед native

Установка `adb install -r` завершилась Success. На5554 прочитаны actual
version0.1.7/code8 и SHA установленного base.apk, совпадающий с сохранённой APK.
До запуска отсутствовал golden worker. В18:29:41UTC snapshot уже сохранён:
`artifacts/quality/baseline-v27-v017-decoded-temporal-20260927-r1.tar`,
254712832bytes, SHA
`32f03ad552e9b1fd259c57dd7189994446745c831da5333f99bfb0ffb79c40e5`.
Все463сохранённых файла read-only сравнены по SHA с рабочей копией: mismatches[].
Архив содержит main/debug/test/testDebug/testFixtures, tools, docs, build inputs,
actual JVM XML/Python report/lint report. Изолированные будущие UI source sets
не включены. После фиксации соседнему чату передана очередь исходников/Gradle;
дальнейшие UI изменения не входят в этот APK.

Первая попытка archive без суффикса `-r1` завершилась exit1 из-за отсутствующего
`app/proguard-rules.pro` в ошибочно указанном списке. Этот файл не существует
в проекте. Первая попытка сохранена, но не используется как baseline. Новый
`-r1` создан с проверенными существующими входами, exit0, затем выполнено
полное сравнение463файлов. Старые baseline/APK не удалялись и не перезаписывались.
Эта запись добавлена после фиксации архива; архив не менялся.

Зарегистрированные native A→C впоследствии завершены на этом exact APK.
Оба execution/retained-inspector/independent-AAC gates прошли; полные verdictfalse
только по12human requirements. Все27windows/1270frame records каждого ролика
совпали сv25. Результаты: `user-v27-native-results-20260927.md`;
human приёмка: `user-v27-heartbeat-human-review-20260927.md`.
