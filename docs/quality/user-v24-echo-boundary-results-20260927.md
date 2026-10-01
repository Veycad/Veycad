# v24: фактическая проверка границ Heartbeat echo

27 сентября 2026. Это новая pilot-регрессия только на разрешённых пользовательских
исходниках, не sealed независимая приёмка и не опубликованный release ready.
Sigma остаётся на паузе; полная исходная цель не уменьшается. Все доступные
A/B/C уже pilot: unused parents 0, sealed release cases 0.

Точная установленная универсальная APK 0.1.4/code5:
SHA `e75fea6aee6c31ae973d63ea437347fe164891e22ea7bd13761bb0c7db5eca44`,
196312991 байт. Runtime SHA нового raw ниже совпадает с указанным
установленным SHA; имя локальной APK в этой записи не предполагается.
Корневой исполнитель сообщил: 551 JVM test / 74 suites без fail/error/skip,
179 Python tests, lint 0 errors / 13 warnings, security pass.
Baseline tar SHA `3544aba676832c39e2dc58b75aeb443fa6feaab4d6b60c69ff43cade8b4865f2`.
Эти build-сведения не заменяют проверку реального MP4.

Изменена общая проверка source handles для фактического echo и добавлены
явные execution evidence / объединение с native acceptance. Базовая grammar,
frame planner, compositor, inspector и пороги приёмки не объявляются изменёнными.
Старые v23 MP4/raw/reviews сохранены; их отзывы не перенесены на новый файл.

Фактически завершены обе новые попытки: Heartbeat(A) дал один MP4 с новым
execution evidence/AAC pass, Heartbeat(C) дал material refusal без MP4.
Все worker этой двухпопыточной серии завершены по проверке корневого исполнителя;
последний фактический одиночный источник C. Отказ C не означает исправленный
экспорт C или принятую отрицательную human case.

## Heartbeat(A): новый фактически завершённый экспорт

Корневой исполнитель подтвердил завершение worker28609 на 14:50:01Z и получил
четыре реальные артефакта. Локальная постобработка устройство не опрашивала,
монтаж не запускала; только прочитала новые файлы, создала AAC/report review
и открыла contact sheet диагностически.

Префикс в `artifacts/quality/runs/`:
`user_heartbeat_v24_a_echohandles_20260927`.
Из этого A-экспорта не выводится успешный экспорт C; её реальный отказ
зафиксирован отдельно ниже.

| Артефакт | Байты | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 13191183 | `cd5889f283a9d7cf52a3a7b566d00a18e158de973d41b1c4c91d091d2721b7e0` |
| `.mp4.result` | 5141 | `a19c9b446d67ad7069e705d41cef19ea3d5e134eece581517619dcad6b55b883` |
| `-inspector.json` | 1264144 | `fe70aab1e13e29df08e29c39f3de074682f2efbbedb9b54737de2ba08d2de0b9` |
| `-contact.jpg` | 234739 | `0cac41149b84b2687c96805f69c59394763a90bd9ca603f3fc61ea3039b8f27a` |
| `.audio.json` | 636 | `cb813936876dcd66ef381115621afd251fc001abc722c806db5b1e24f9cfdd3d` |
| `.review.json` первоначально пустая | 615 | `695ed2626af4418ef186d168fd764a324959a5888e287d5fc0fd5d01f5607a79` |

Хеши и размеры вычислены по фактическим локальным файлам, не взяты из названий.
Raw status=ok, recipe=HEARTBEAT, graph_generator=HEARTBEAT_V1:production,
source_pool_generator=heartbeat-source-pool. Runtime APK SHA выше, split0,
API36, модель Android SDK built for x86_64.
Оригинал и render source A SHA
`8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`;
static_source=false, source_count=1.
Отдельная Heartbeat music SHA
`cc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48`.

### Точное сравнение исходных окон с v23(A)

Сравнены все поля всех 27 authored windows из двух фактических inspector JSON.
Изменены ровно два окна одной роли; остальные 25 полностью совпали.

| Clip / role | v23 source interval, мс | v24 source interval, мс | Output interval, мс |
| --- | --- | --- | --- |
| 10 / heartbeat-10-role-11 ACTION | [17,534) | [67,584) | [7783,8300) |
| 21 / heartbeat-21-role-11 ACTION | [17,534) | [67,584) | [15800,16317) |

Оба окна сдвинуты на +50 мс, без растяжения 517-мс фразы или изменения её
output timing. Все 26 visual source/output durations по-прежнему равны,
все shader speeds=1. Первые 15 authored primary intervals попарно не
пересекаются; raw repeated_source_ratio=0.0. Авторская reprise и opaque tail
не выдаются за случайный повтор и остаются исключёнными из существующей метрики.

У v23(A) диагностически найдены три запроса clip21, где negative -67 мс echo
обрезан в0: output15800000/15816667/15833333 мкс, primary17000/33667/50333 мкс,
unclamped secondary -50000/-33333/-16667 мкс. Их opacity ниже0.25,
поэтому прежний inspector accepted=true не означал отсутствия clamp.
В v24(A) таких случаев нет: у всех467 frames с secondary_source_us>=0
точно secondary=primary−67000 мкс; negative unclamped requests0,
clamp/offset mismatches0. Requested primary range67000..26870667 мкс,
secondary range0..26803667 мкс, в пределах исходника A (FFmpeg сообщает
около27.99 с). Secondary request0 теперь законен при primary67000,
а не результат ограничения отрицательного времени.

### Inspector, фактические texture PTS и декодированный output clock

Inspector summary accepted=true, issues=[], planned/shader1270/1270.
Layer frames575, temporal layer frames456, dual_decoder frames467;
эти разные счётчики не смешиваются. Во всех467 echo decoder samples
primary/secondary decoded_source_us известны; unknown0/0.
Requested echo separation всегда67000 мкс. Actual texture separation
44000..83334 мкс; в292 frames с opacity>=0.25 —66656..83334 мкс,
одинаковых primary/secondary texture PTS0. Это результат VFR nearest/ceiling
sampling, не обещание строго67 мс actual separation на каждом decoded frame.
Source sampling error по всему shader trace −4333..16667 мкс.

Отдельно сохраняется прежнее одно межклиповое совпадение actual primary PTS
26895666 мкс в clips6/14, хотя authored intervals не пересекаются.
Это известное VFR boundary sampling наблюдение; не строгая actual-pixel-zero
метрика и не скрытый authored overlap. Полная попиксельная неповторяемость
из trace не выводится.

Shader output_us точно совпадает с rounded60fps clock во всех1270 slots.
Независимый FFmpeg7.1 showinfo decode реального MP4 также завершился exit0:
1270 video frames, time_base1/90000, first0/last21150000 мкс, каждый шаг
1500 ticks = 50000/3 мкс (точные60fps). Этот rational контейнерный clock
не равен округлённому integer shader_us на дробных третях микросекунды;
это отдельные clocks, не обнаруженное смещение или ложный mismatch.

### Native numeric evidence и новый execution contract

21.166 с,27clips,1270frames,720×1280,rotation0; H264/AAC,
video/audio first PTS0, container issues=[], audio samples912,
A/V drift3378 мкс,duration error16000 мкс.
Beat hit0.9230769≥0.90,author accent hit1,offset0.
Все26/26pulses matched; missing/wrong-luma пусты.
Tail decoded-luma-contiguous-60fps-v1:82expected/measured/black,
2boundary samples,first black19800000 мкс,start offset0,matched=true;
missing/bright/unexpected пусты.
Fresh decoded-face-exact-pts-v1:242expected/242measured,unknown0,
7lost/empty,rate0.02892562<0.05. Потери7400000/7416666/7500000/
15400000/15500000/15600000/15700000 мкс не скрываются.
Max artifact0.010416667,colour jump0.11393852.

Все прежние raw поля измерений совпали с v23(A): фактические различия raw
ограничены runtime APK SHA, MP4 bytes/SHA, именем кандидата и четырьмя новыми
execution fields. Это не означает одинаковые encoded pixels: SHA MP4 различен.
Профиль112observations,editorial-semantics-v1/NOT_REQUESTED/0correspondence
assessments не объявляется доказательством физической статики/движения камеры.

Новые реальные поля:
render_execution_method=shader-frame-inspector-v1,
render_execution_evidence=true,render_execution_accepted=true,
render_execution_issues пусто. Они согласованы с actual inspector summary.
Native merged acceptance=false только по
audio-unclamped-headroom-unavailable; никакой inspector issue не вырезан.

### Независимый звук и человеческая граница

FFmpeg7.1 unclamped-f32 AAC:44100 Гц/2ch,1867776samples/933888frames,
21176598 мкс,peak0.4500930607318878,RMS0.07753826874069104;
nonfinite/overfullscale/fullscaleplateau0,issues=[],passed=true.
Новый report связан с новым SHA MP4; current quality_render_report ещё раз
декодировал именно этот файл и подтвердил exact saved-report equality.
Offline разрешён только точный audio-unclamped-headroom-unavailable;
новый execution contract и остальные machine criteria прошли.
Полный gate всё равно exit1/machine_and_human_pass=false: issues только
пустые reviewer/date,playback1×/0.5×,7общих и3Heartbeat style checks.

Создана новая полностью пустая output-bound human форма. Положительный отзыв
и подтверждённый1×старого v23(A) не перенесены; новый v24(A) ещё требует своего
просмотра. Contact sheet действительно открыт диагностически: портретные
выборки, reprise/echo/light accents и чёрные последние выборки.
Он не подтверждает органику, весь ритм со звуком, все лица, full-body coverage
или человеческую приёмку нового файла. Четырёхкадровое issue старого HB(C)
этим A-результатом не объявляется исправленным: нужен отдельный принятый C MP4.

## Heartbeat(C): настоящий отказ без нового MP4

Реальный новый raw `artifacts/quality/runs/user_heartbeat_v24_c_echohandles_20260927.mp4.result`,
622 байта, SHA `ccda3cbc07307a70b75b0abe620f2c0228a70e51944a44fd61af5e04847d0074`.
Status=material_rejected, rejection_code=insufficient_distinct_moments.
Runtime APK SHA e75fea6a…5eca44/API36/split0/x86_64 совпадает с этой сборкой;
original/render source C SHA
`0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23`,
static_source=false, отдельная Heartbeat music SHA cc98cef7…0669e2ef48.
Точный detail: `Heartbeat needs disjoint readable handles for every measured live role and finale`.
Он не подменяется успешным старым v23(C).

Корневой исполнитель подтвердил отсутствие worker на14:51:47Z и отсутствие
нового MP4 на устройстве; локальное назначение также проверено отсутствующим.
Его наблюдение cache hit119 на14:51:09Z не выдаётся за119новых decoded
измерений/face samples или холодный анализ этой попытки.
Для отказа не создавались вымышленные inspector/contact/AAC positive артефакты.
Создана отдельная пустая exact-raw `.negative-review.json`,405байт,
SHA `9f33cd765606d21be78c4da7fcd4ecb1d00246e6566e9dbd78df3dcd8af7a1a0`:
recipe/source/raw
SHA/rejection_code привязаны, secondary source null; reviewer/date пусты,
material_case_confirmed/message_specific false. Человеческая приёмка отказа
не перенесена с каких-либо прежних отзывов.

Отказ ограничивает доказанный результат v24: положительный regression export
есть только для A. Он не доказывает, что во всём C физически отсутствуют
различные моменты или пригодные handles. Причину наличия материала против
границы candidate search нужно исследовать отдельно; краткий raw без trace
не позволяет объявить этот выбор исчерпывающим. Прежнее четырёхкадровое
echo issue v23(C) остаётся историческим наблюдением, а нового принятого C MP4
пока нет. История чередования A→C соблюдена; C вновь последний фактический single.
