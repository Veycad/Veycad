# v28: FEAR и DUALITY на фиксированной APK 0.1.7

План записан до новых запусков 28 сентября2026. Предыдущий прерванный ход
прочитал грамматики и согласовал очередь с соседним чатом, но v28 native не
начался: новых v28 artifacts нет. Последний actual single источник — C,
Heartbeatv27 (terminal подтверждён27сентября18:44:49UTC).

## Проверяемый пробел

Новый `decoded-texture-pts-v1` контракт проверен native только на Heartbeat.
Последние FEAR/DUALITYv23 сохраняют исторические measurements, но их raw не
имеют новой execution/policy metadata. Нужно подтвердить текущие правила
каждого продукта на установленной фиксированной0.1.7, в частности отсутствие
ложного требования temporal secondary input у FEAR/DUALITY spatial effects.
Старые raw не дополняются новыми полями; likedFEARv23 остаётся неизменным.

APK: `artifacts/quality/apk/Veykad-0.1.7-decoded-temporal-20260927.apk`,
196329375bytes, SHA
`c7d5ee0f55355da8c53f5a3b428dd6f2ca3b80ebac8378cd4c37185dd52ea8f5`.
Frozen source baseline до nativev27:
`artifacts/quality/baseline-v27-v017-decoded-temporal-20260927-r1.tar`,
SHA `32f03ad552e9b1fd259c57dd7189994446745c831da5333f99bfb0ffb79c40e5`.
595JVM/242Python pass относятся к этой сборке. Последующие UI изменения
рабочей копии и `.uitest` APK соседнего чата в неё не входят. Новый build,
изменение директоров, source pools или порогов в этом этапе не планируются.

## Четыре зарегистрированные попытки

1. `user_fear_v28_a_20260928`: sourceA, explicit fear=true. Guard against actualC.
   Исторически A отказан insufficient_motion_evidence; новый исход записывается
   фактически, а не считается заранее отказом или успехом.
2. `user_fear_v28_c_20260928`: sourceC, explicit fear=true. Guard against actualA.
3. `user_duality_v28_ab_20260928`: orderedA,B, explicit duality=true.
4. `user_duality_v28_ba_20260928`: orderedB,A, explicit duality=true.

A —19348088228456.mp4,
SHA `8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`.
B —19348085803624.mp4,
SHA `81629968d2a0b41b355ec729d900f9b1cd2aec244a543f785796e5aa55d616b2`.
C —IMG_2249.MOV,
SHA `0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23`.
Музыка FEAR —blind-v1-fear-score.m4a,
SHA `2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Музыка DUALITY —duality-author_20260926.m4a,
SHA `ff759c051b806564e6012423d237df57f0798ce52825f7b24179dc20072cb3de`.
Имя старого music файла не означает использование публичного исходного видео.

## Исполнение и приёмка

Перед каждой попыткой проверяются exact installed APK/version, источник/музыка,
user source guard, отсутствие локальных/device prefix targets и предыдущего
golden worker. Только explicit recipe flag; Sigma не запускается. Перезапуск
из-за observation timeout не допускается. Только после terminal сохраняются
actual artifacts и сверяются device/local SHA.

Positive: statusok, expected product graph, `no-temporal-layer-v1`, сохранённый
inspector без противоречий, собственные прежние численные критерии FEAR/DUALITY,
fresh independent AAC с повторным сравнением. На FEAR проверяются непрерывное
вступление, вариативные cascade, shutter и финал; на DUALITY — обаsource roles,
порядок импорта, source-bound face evidence и живой финал. Сравнение сv23
фиксирует изменения или сохранение выбранных окон и actual frame records.
Human формы создаются пустыми, для exact новых bytes, без переноса отзывов.

Negative: реальный material-rejected raw, код и absence MP4; новая negative
форма связана с rawSHA/recipe/ordered sources. Ошибка процесса/codec/compute
не становится доказательством непригодного материала. При неожиданном исходе
сначала диагностика, без ослабления порогов или скрытого повторного запуска.

## Состояние перед восстановлением устройства

28сентября03:33:20UTC: adb devices пуст, emulator/qemu процессов нет.
Это подтверждённое отсутствие устройства, не истечение времени наблюдения.
Существующий Fear_API_36 AVD найден. Разрешено восстановить его без wipe и
без загрузки snapshot; после boot перепроверить сохранённые данные и APK.
До восстановления native ещё не начинался; история источников не изменилась.

## Полная цель

Только три уже использованных owner pilots; unused holdout0/sealedcases0.
Эта серия расширяет текущую техническую проверку на все активные продукты,
но не создаёт независимые съёмки. Требования разнообразия и human1×/0.5×
сохраняются; Sigma paused/locked, не принята. Cloud публикация не выполняется.

## Фактическое завершение (добавлено после серии)

Все4зарегистрированные попытки выполнены без повторов. FEAR(A) — typed refusal
insufficient_motion_evidence безMP4; FEAR(C),DUALITYAB,DUALITYBA — completed
MP4 с current execution/retained-inspector/AAC passes. Human формы пусты.
AB/BA дали одинаковые physical windows, decoded frames и PCMaudio; различия
отv23 отдельно сохранены, не названы улучшением без человеческой приёмки.
Последний worker6217 подтверждён terminal28сентября04:02:27UTC.
Доказательства: `user-v28-active-products-results-20260928.md`.
