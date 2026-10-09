# v27 Heartbeat: проверка decoded temporal evidence

Итог: обе зарегистрированные попытки A→C завершены на exact0.1.7.
Native execution, retained inspector и independent AAC прошли. Оба полных
machine-and-human verdictfalse только по12human requirements. Новых физических
попыток сверх этих двух не выполнялось. Контроль качества стал строже;
монтажные фрагменты и recorded frame execution совпали сv25 для обоих файлов.

Это журнал зарегистрированной пилотной серии A→C на лично присланных исходниках.
Полная цель четырёх самостоятельных продуктов остаётся открытой. Sigma одна
paused/locked. A/B/C уже использованы при настройке: unused0, sealedcases0.

## Сборка и последовательность

Exact installed APK0.1.7/code8:
`c7d5ee0f55355da8c53f5a3b428dd6f2ca3b80ebac8378cd4c37185dd52ea8f5`.
Frozen baseline `baseline-v27-v017-decoded-temporal-20260927-r1.tar`:
`32f03ad552e9b1fd259c57dd7189994446745c831da5333f99bfb0ffb79c40e5`.
595JVM/242Python pass, signature/lint/security passed. Подробности и история
неуспешной первой archive попытки: `apk-v017-decoded-temporal-local-20260927.md`.
План до раскрытия результата: `user-v27-decoded-temporal-preregistration-20260927.md`.

27сентября2026,18:30:25.181633UTC: реально запущен A с explicit heartbeat=true,
обычной отдельной музыкой и default echo−67ms. Source guard прошёл against
previous actual C(v25); локальные и device prefix targets отсутствовали,
старый golden worker отсутствовал. После старта подтверждены PID10701/TID10720,
`veycad-golden-r`, running. Это запуск, не подтверждение готового экспорта.

A: `user_heartbeat_v27_a_decodedpts_20260927`, sourceSHA
`8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`.
C зарегистрирован следующим: `user_heartbeat_v27_c_decodedpts_20260927`, sourceSHA
`0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23`.
Оба device originals и музыка перед серией сверены поSHA. Музыка:
`cc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48`.

После фиксации сборки соседний авторизованный чат получил очередь исходников
для новых UI-тестов с отдельными `.uitest`/5556. Изменения после snapshot
не входят в этот runtime APK. Эта серия продолжает работать на5554.

## Критерии результата

Нужны terminal worker, actual MP4/raw/inspector/contact, device/local hash match,
explicit decoded evidence method/policy, retained record validation и отдельный
независимый AAC decode с повторным сравнением. План/граф сравниваются сv25,
чтобы установить, сохранилась ли монтажная последовательность после поправки
измерений. Копии старых отчётов и отзывы likedv23 не заменяют эти проверки.

Human формы новых файлов создаются пустыми. Отсутствие decoded temporal ошибки
не доказывает художественную читаемость или просмотр человеком на1×/0.5×.
Этот этап не является независимой release-серией и не публикует APK в облако.

## A завершён, C начат

В18:37:21.820709UTC root подтвердил отсутствие A worker10720 и наличие завершённых
artifacts; это время наблюдения terminal, не предположение о точном времени выхода.
Четыре native файла скопированы после этого, каждый device/localSHA совпал.

| A artifact suffix | Bytes | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 13259304 | `9a1b7dcdd4ef9bf5af1ddeef770813576df2cd1d4407e2c460d3fff54f109065` |
| `.mp4.result` | 5596 | `e0f3130e3f332217a7d8d687dfdd74ee7dccf60ea17c3a32574b68de7c525513` |
| `-inspector.json` | 1264262 | `54efd66d6f6c04d496ab2c32cce2881aee6de0dc884f57e864b996aba014ca4e` |
| `-contact.jpg` | 234581 | `5d07db6f2d4cdfa96f034354a2ec7287550b250d9da3009ebb3a5af4169ea652` |
| `.audio.json` | 636 | `d4bc1487ac3bedd71fc09aba112a9b484aa4fb8c2781843907e8af616861b2da` |
| `.review.json` | 615 | `6eeeff29b26c0e1b2b00e18509735ed07b6bbfacf507ee8516fea1a0a068a1b7` |

A raw: statusok, exact runtimeAPK выше, explicit method/policy совпали,
render_execution_acceptedtrue/issues[]. Retained inspector validation issues[].
Все27authored source windows и все1270frame records совпали сv25A.
Контактный лист также имеет прежний SHA; root просмотрел его. Это не просмотр
всего ролика со звуком человеком. MP4 имеет новый SHA, поэтому отзывы не переносятся.

292visible temporal frames имеют два live inputs и distinct actualdecodedPTS,
separation66656..83334us. В live clips сохранены63valid adjacent same-clip decoded
repeats: clip9=10,11=7,15=9,16=19,23=18. Во всех кадрах их77, включая14в
авторском чёрном tail clip26; именно исключение tail объясняет разницу.
Пар с двумя неизвестными decoded timestamps0. Product repeated-source ratio0
является другой метрикой и не означает отсутствия decoder repeats.
Preferred-pool выбран без fallback, director validations1, generated/visits0.
Face-loss rate0.02892562, AVdrift3378us.

Свежий independent AAC decode прошёл и точно совпал с повторным измерением:
44.1kHz/stereo,933888PCM frames, peak0.4500930607318878,
RMS0.07753826874069104, nonfinite/overfullscale0. Native unknown
audio-unclamped-headroom-unavailable разрешён только этим независимым измерением.
Full machine-and-human verdictfalse ровно по12незаполненным human requirements.
Форма нового файла полностью пустая, включая1×/0.5×/reviewer/date/checks.

18:38:16.159692UTC: C реально начат после terminal A. Повторно подтверждены
source guard against actual A, runtime0.1.7/code8/SHA, source/musicSHA,
отсутствие новых prefix targets и старого golden worker. Новый C worker:
PID10701/TID15030. Общий PID приложения сохранился, worker другой.
На момент этой записи C выполняется, положительный результат ещё не заявлен.

## C завершён

В18:44:49.711124UTC root подтвердил отсутствие worker15030 и завершённые файлы.
Четыре native artifacts скопированы после terminal, device/local SHA совпали.
Это последняя actual single-source попытка: C. Следующая одиночная попытка,
если потребуется, должна пройти source guard against C.

| C artifact suffix | Bytes | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 13332148 | `0350200f7cc1a3df9fc17f56ed725fa9212a942dc13c7f62f141e33f3f6f7b6c` |
| `.mp4.result` | 5586 | `8678c52c87fbb36f28c920ebf37176e5eae75b6bca3866b5994c12769b6bfa1a` |
| `-inspector.json` | 1263624 | `8b3174bdcbc9e0446533cd34ded02b17cd974ba8d7348590f2787ac5002a4bf1` |
| `-contact.jpg` | 230219 | `d788c7b600137cbd5a5673ddd2a861029a60f5308fdae6a3241bebeba5fe8bc8` |
| `.audio.json` | 636 | `65890eb1075dd6b137ccda1cce83cf22d95e6118d1db4294f708e988174dc5ca` |
| `.review.json` | 615 | `f2bc64e842916ab1cbc0af618244b60cf86b3fe9ede7f5b924cfe399afa7fc51` |

C raw: statusok, exact runtimeAPK0.1.7 SHA, method/policy совпали;
render_execution_acceptedtrue/issues[]. Retained inspector validation issues[].
Все27source windows и все1270frame records совпали сv25C. Contact SHA также
совпал; root просмотрел лист. В интерлюдии12.23с заметна обрезка верхней части
головы — это подсказка для человеческого просмотра, не заполненная оценка.

292visible temporal frames: все dualdecodertrue, distinctdecodedPTS,
separation66666..100000us. Live decoder repeats581, отдельно black tail49;
30fps исходник выводится по60fps расписанию. Product repeated-source ratio0
не используется как доказательство отсутствия этих повторов.
Measured-pair-joint-v1: generated2794/domain3260/visits83695/director validations2,
finite_domain_exhaustedfalse. Выбранные окна и counters совпали сv25.
Faces230measured/0unknown/3lost, rate0.013043478. Оба ролика:
720×1280,1270frames/27clips/21.166s,26/26pulses,82/82black-tail frames,
AVdrift3378us. Полная человеческая оценка движения и композиции ещё нужна.

Собственный fresh independent AAC C прошёл, повторный decode точно совпал
с сохранённым отчётом. Peak/RMS/sample counts совпали сA из-за одинаковой музыки
и длительности; каждый отчёт отдельно получен из своего MP4 и связан с егоSHA.
Full verdictfalse только по тем же12human requirements. Новая C review form
целиком пустая; likedFEAR(C)v23 и отзыв оHeartbeat(A)v23 сюда не перенесены.

## Полная цель остаётся открытой

Повторная текущая corpus-проверка: integrity_errors[], unused verified0,
pilot verified3, corpus_readyfalse. В текущей release matrix cases[], errors[],
verified_case_count0, active_products_readyfalse, readyfalse; Sigma paused.
Все9условий, разнообразие fps/containers/cameras и фиксация независимого набора
остаются незакрытыми именно для unused holdout. Уже использованные A/B/C и
новые экспорты из них не восполняют независимость. Независимых release cases
не создавали, source policy не расширяли.

Likedv23 FEAR(C) иHeartbeat(A) повторно сверены: SHA09b2…d580c и8b2f…d8450
сохранены. Новый [лист приёмки](user-v27-heartbeat-human-review-20260927.md)
ссылается только на новыеv27 bytes. Native v27 больше не выполняется.
