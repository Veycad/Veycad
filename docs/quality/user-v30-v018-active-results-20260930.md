# v30: фактические проверки Heartbeat и DUALITY на 0.1.8

Серия выполняется по заранее записанному
`user-v30-v018-active-preregistration-20260930.md`, только на присланных A/B/C.
Это техническое продолжение на имеющемся наборе, не ожидание новых видео.
Исторические liked exports не изменяются; Sigma не запускается.

## Восстановление и точная установленная версия

30 сентября 2026 восстановлен существующий Fear_API_36 без wipe/snapshot.
`emulator-5554` boot_completed=1. Установленные 0.1.8/code9 и SHA base.apk
`35683fe6c277f5670ed3f39bca5c88c0759bd1aebd82e3a12e598d0e9b50eab4`
совпали с сохранённой APK. Переустановка и новая сборка не выполнялись.
Device SHA A/B и музыки обоих продуктов совпали с preregistration.
Три локальных/device префикса отсутствовали, golden worker отсутствовал.
Соседний чат уведомлён; его UI-установка и чужой эмулятор камеры не затронуты.

## Heartbeat(A)

2026-09-30T17:11:17.3925818Z: native запуск с explicit `heartbeat=true`,
source A, прежняя музыка Heartbeat, новый output
`user_heartbeat_v30_a_v018_20260930.mp4`. Guard проверил фактический C → A.
Start Status=ok, appPID1999/workerTID2019, `veycad-golden-r`.
17:18:46.2401300Z worker2019 отсутствовал; MP4/raw/inspector/contact/layers
сохранены только после terminal, device/local SHA совпали для всех пяти файлов.

| Артефакт после префикса `user_heartbeat_v30_a_v018_20260930` | Байты | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 13281266 | `15c4f6603774a62f14eae886d185c09728c15dd56cd294a2cadf552b187a5583` |
| `.mp4.result` | 5591 | `fbd9fb5baccbf07689ba39b0250b5202bf67ac683998c1c70e8c982a19200be7` |
| `-inspector.json` | 1264261 | `459b34e642fcd83d1e399572138b18bb218090f4007686818a037a48745d711d` |
| `-contact.jpg` | 235907 | `a19540d2874ed091347fbb748122580aa328ca785ed85955d5f86e8429cfe064` |
| `-layers.jpg` | 506106 | `11090888dde69748473c295e4c200094195e7e9781c8617fb893556be38f9370` |

`status=ok`, exact runtime/source/music SHA соответствуют плану,
graph `HEARTBEAT_V1:production`, policy `temporal-distinct-pts-v1`.
Actual execution accepted=true/issues[], retained inspector issues[].
1270 кадров/21.166с, 27 clips, 26/26 pulses matched, tail matched=true.
292 visible temporal frames: все имеют dual decoder и разные actual decoded PTS.
Repeated source ratio0, beat hit0.9230769, AVdrift3378мкс.
242 measured faces/0unknown/7loss: 7.400–7.417,7.500,15.400–15.700с.

Independent unclamped AAC и повторное измерение согласованы: 44.1кГц stereo,
933888 PCMframes/1867776samples, duration21176598мкс,
peak0.4500930607318878/RMS0.07753826874069104, nonfinite/overfullscale0.
Полный gate false только по12незаполненным human требованиям.

### Отличие от предыдущего Heartbeat(A)v27

Это не побайтно или покадрово идентичный ролик. 10из27 source windows изменились
(indices1,3,5,8,12,13,16,19,23,24), длительность/структура27clips сохранены.
Всего1270frames в обоих MP4,92framemd5 records совпали; SSIM All0.899668.
PCM звук одинаков:7471104байт, SHA
`93f4b42e02b92a037e241a002b2f4713113aa83e3488112138eb50920315bb8a`.
Изменения source_us/decoded_source_us отмечены615frames, secondary233frames.
Таким образом различие не сводится к контейнеру или одному кодированию.
Причина изменения ранжирования пока не установлена; эта серия не меняла
директоры или художественные пороги. Отзыв на liked v23 и предыдущую версию
не перенесён. Новый результат требует собственного просмотра.
Две первые попытки вспомогательного read-only сравнения остановились на неверном
предположении о схеме отчёта; исправленный вызов прошёл. Реальные артефакты
не переписывались, повторного native рендера не было.

## DUALITY

В обоих случаях источники — те же A/B, с заранее заданным порядком.
Пара остаётся одной уже использованной парой.

2026-09-30T17:20:37.8476348Z: AB запущен после terminal Heartbeat,
exact APK/source/music SHA перепроверены; source guard и exclusive prefix
прошли, explicit duality=true, PID1999/TID6332.
17:24:46.7783073Z worker6332 отсутствовал, четыре файла сохранены после terminal.

| Артефакт после префикса `user_duality_v30_ab_v018_20260930` | Байты | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 11921415 | `06f54c927dc72b89cde1bfc0bb4c3832a51013e507077b17ae533b8c880b6fce` |
| `.mp4.result` | 10710 | `837eb95cce3b6b95a8e51f755be64aee8c36b0cd480da3a5a9570439b026da4f` |
| `-inspector.json` | 468887 | `c41190fa9713fd07c168760714f030e996ab4acf1940cb589120d6dc0e80a978` |
| `-contact.jpg` | 280726 | `ae8202af6e877993138656ad2ccdaa248e53e486445e1c6a68525baa0c24fa98` |

AB statusok, executionaccepted/issues[], graphDUALITY_LOOP_V1,
policy no-temporal-layer-v1, repeated0, beat hit0.625, AVdrift30658мкс,
180faces measured/0unknown/0loss. Independent AAC и повторный decode passed:
807936PCMframes, duration18320544мкс, peak0.8843885660171509,
RMS0.19274843229837393, nonfinite/overfullscale0. Полный gate false только
по12неподтверждённым human требованиям.

2026-09-30T17:26:16.0264903Z: BA начат после terminal AB и сохранения файлов.
Guard проверил orderedB,A; runtime/source/music SHA снова проверены,
exclusive targets отсутствовали. Explicit duality=true, PID1999/TID8330.
17:29:33.9795617Z worker8330 отсутствовал. Четыре файла скопированы после
terminal, все device/local SHA совпали.

| Артефакт после префикса `user_duality_v30_ba_v018_20260930` | Байты | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 11921415 | `dcae54f234b93a0f36202afed512a9c98023a051d6afa27d907cfb14f9c00240` |
| `.mp4.result` | 10704 | `e7f824534f14edf50f59a064e43e97fbd3e4a7adac2fd4d286154c52cd5ddaf2` |
| `-inspector.json` | 468887 | `e5069302b0c8f183b16d14e3b2bf417319f4f83db4a94d0defa83e384656d5f3` |
| `-contact.jpg` | 280726 | `ae8202af6e877993138656ad2ccdaa248e53e486445e1c6a68525baa0c24fa98` |

BA statusok, exact runtime/source/secondary/music identities соответствуют плану,
executionaccepted/issues[], retained inspector issues[]. Его frame/face/beat/AV
и independent AAC measurements совпали с AB; saved audio report повторно
согласован с новым decode. Полный gate false только по12human requirements.

### Сравнение обоих порядков и предыдущей версии

v28AB, v30AB и v30BA содержат549frames каждый; все framemd5 records совпали.
PCM звук тоже одинаков:6463488байт, SHA
`37f6340ab11cae2d91df0a20a75b31391b1811190739d134822f4a73a76df18b`.
При замене import index точным source SHA и исключении только импортного
`clip_id` все25физических source windows совпали дляAB/BA: сохраняются
clip_index/source_identity/source_start_ms/source_end_ms/output_start_ms/
output_end_ms/role. Не нормализованный словарь отличается ожидаемыми
source_index и clip_id у25clips; это изменение имён ролей импорта, не изображения.
Контактные листы AB/BA побайтно одинаковы, MP4 контейнеры имеют разные SHA.
Human формы остаются отдельными, пустыми и output-bound.

## Итог серии

Выполнены ровно три запланированные попытки, без повторов, все три дали MP4.
На сохранённой0.1.8 проверены Heartbeat(A) и оба порядка DUALITY, дополняющие
предыдущий FEAR(C)v29. Actual execution, retained inspector и independent AAC
прошли во всех трёх новых запусках. Рендеры закончены; последнее одновходовое
видео теперь A. Два D-порядка одиночную историю не меняют.

Read-only сверка с frozen baselinev27 показала совпадение исходников
HeartbeatDirector, LocalSemanticFrameAnalyzer, MediaFrameAnalysisCache,
MediaFrameVisualAnalyzer и VeycadAutomaticEditor с текущей рабочей копией.
Это не устанавливает причину изменения10окон Heartbeat и не заменяет runtime
identity checks. Heartbeat не объявляется визуально лучше или одобренным.

На этом этапе ни новый APK, ни художественная настройка не требовались.
Sigma по-прежнему заблокирована; cloud публикация не выполнялась.
ВсеA/B/C остаются разрешёнными пилотами, независимый корпус и полная
человеческая приёмка не закрыты. Но новых файлов этот текущий этап не ожидал.
Понравившиеся Heartbeat/FEARv23 не перезаписывались.
В конце повторно проверены exact installed/local APK SHA и обе liked v23 копии:
FEAR `09b2fcb9040bf3a5b305ad2e98a5b921dd037f936c66c74de761e609639d580c`,
Heartbeat `8b2fa8f07dde6953fcd9c0fadf277ef6f9a2f8cf7dd04829ede0672b562d8450`.
Хеши не изменились, golden worker отсутствует. Контактные листы Heartbeat/DUALITY
просмотрены агентом для диагностического внимания, но это не просмотр со
звуком на двух скоростях и не подпись человеческой формы.
[Результаты для просмотра](user-v30-v018-active-human-review-20260930.md).

Следующая read-only/JVM диагностикаv31 воспроизвела все27оконv27 на старом
сохранённом анализеA и все27оконv30 на новом анализеA; preferred source pool
одинаков, отличается композиционное распределение. Это локализует причину
изменения выбора в данных анализа, но не объясняет происхождение различия
самих semantic измерений. Новых монтажей и изменения APK не было.
[Доказательстваv31](user-v31-heartbeat-analysis-replay-20260930.md).
