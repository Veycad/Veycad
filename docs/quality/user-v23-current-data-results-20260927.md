# v23: фактические пилотные прогоны на текущих пользовательских исходниках

27 сентября 2026. Точная установленная сборка — APK0.1.3/code4,
SHA256 `2e54f621b539a03d339e5156a61bbe04b63f60e92bd2e02233db4897b2819e0b`.
Это пилотные/калибровочные проверки лично присланных материалов, не закрытие
sealed независимой серии. Sigma остаётся заблокированной; публичные видео,
искусственное движение и изменение порогов не используются.

В этой записи приведены только фактически полученные результаты. Планируемые
следующие попытки не считаются завершёнными. Локальная постобработка читает
новые экспорты; она не выполняет монтаж, не меняет исходники и не заполняет
человеческие оценки. Новые формы первоначально созданы пустыми; последующие
явные ответы владельца привязаны только к показанным конкретным MP4 ниже.

Важное ограничение общего итога: у Heartbeat(C) сохранён настоящий inspector
warning `temporal_layer_sources_too_close_to_read`. Native acceptance/offline
report его не включают в свои criteria; поэтому этот результат нельзя
назвать полностью чистым по всем машинным диагностикам. У остальных
описанных положительных экспортов также нет завершённой человеческой формы.

## Итог завершённой шестипрогонной серии v23

На точной APK0.1.3/code4 действительно завершены следующие6попыток.
Ни один следующий план/новая правка не включены в эти результаты.

| Попытка | Фактический исход | Inspector диагностика | Человеческая приёмка |
| --- | --- | --- | --- |
| Heartbeat(A) | MP4, numeric/container/face/pulse/tail/AAC criteria на момент проверки пройдены | accepted=true, issues=[]; один общий VFR texture PTS отдельно описан | Общая положительная фраза и явно подтверждён только1×; .5×/10checks/reviewer/date не подтверждены |
| FEAR(C) | MP4, opener/cascade/shutter/face/container/AAC criteria на момент проверки пройдены | accepted=true, issues=[] | Общая положительная фраза и отдельно подтверждён только1×; .5×/checks/reviewer/date не подтверждены |
| FEAR(A) | material_rejected/insufficient_motion_evidence, MP4 нет | Экспорт отсутствует | Новая пустая exact-raw negative форма |
| Heartbeat(C) | MP4, native/offline numeric criteria и AAC пройдены | accepted=false, temporal_layer_sources_too_close_to_read | Новая пустая форма; оценка A не перенесена |
| DUALITY(A,B) | MP4, текущие product/container/face/AAC criteria пройдены | accepted=true, issues=[] | Новая пустая форма |
| DUALITY(B,A) | MP4, текущие product/container/face/AAC criteria пройдены | accepted=true, issues=[] | Новая пустая форма |

Итого5реальных MP4 и1типизированный отказ. Все5имеют новый независимый
unclamped AAC report и fresh saved-report comparison. У каждого полного
positive gate на момент описанной проверки machine_and_human_pass=false
из-за незаполненной формы;
у HB(C) сверх того остаётся отдельный настоящий inspector warning.
Пилотные источники/одна давно знакомая DUALITY пара не закрывают независимую
разнообразную серию; Sigma на паузе, не принята. Полный release ready не заявлен.

### Последующая граница нового execution-evidence контракта

После этих фактических проверок вводится fail-closed offline контракт
с полями доказательства исполнения renderer. Неизменяемые v23 raw этих
новых полей не содержат. Записанные выше fresh AAC совпадения и прежние
numeric gate observations остаются исторически действительными, но при
применении нового контракта v23 raw не получают полный machine pass без
execution evidence. Inspector JSON остаются отдельным авторитетным
диагностическим источником:4/5accepted, HB(C)с4кадрами echo issue.
Исторические raw/MP4 не дополняются вымышленными полями задним числом.
Будущая общая source-boundary/echo правка ещё не собрана, не проверена
новыми экспортами и не выпущена; новые source windows требуют новых
output-bound измерений и человеческого review, не перенесённых оценок A/C.

## Heartbeat(A): новый экспорт на APK0.1.3

Фактический raw содержит `status=ok`, `recipe=HEARTBEAT`,
`graph_generator=HEARTBEAT_V1:production`, runtime APK SHA выше,
split0, API36, device `Android SDK built for x86_64`.
Авторизованный A — SHA
`8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`;
`render_source_sha256` совпадает, `static_source=false`, вход ровно один.
Отдельная авторская музыка SHA
`cc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48`.

Новые реальные файлы находятся в `artifacts/quality/runs/`:

- `user_heartbeat_v23_a_20260927.mp4`, 13173528байт, SHA
  `8b2fa8f07dde6953fcd9c0fadf277ef6f9a2f8cf7dd04829ede0672b562d8450`.
- `user_heartbeat_v23_a_20260927.mp4.result`, 4992байт, SHA
  `e8122b2f71e4ac3f320b0caf19dc5904a43f58c94ec8b788fe80cb9cef3cf1f3`.
- Новый `user_heartbeat_v23_a_20260927-inspector.json`, 1264051байт, SHA
  `fdc817927489f7307c6d03f8346d89de88d8516861d20145ef5157053b42f295`.
- Новый `user_heartbeat_v23_a_20260927-contact.jpg`, 234611байт, SHA
  `66612238b09f3506502a72ed21d3c6bce6cef9e6a1fe831a50d8b6e107440abc`.
- Новая `.audio.json`, SHA
  `8e26c3ef6f47f0f5a58682193651b8356f73bcb77a94ac7cc5bcb5cffa930099`.
- Новая пустая `.review.json`, первоначальный SHA
  `72686208f7cf52ff0381725013642cf0b7020b840a98c4a5f8a80e98c1fd343c`.
  Её output SHA/recipe/generator относятся только к этому новому экспорту.

### Машинные наблюдения

21.166с, 1270кадров, 27клипов, 720×1280, encoded rotation0.
Контейнерные issues пусты, начала video/audio PTS0; A/V drift3378мкс,
duration error16000мкс. Source profile editorial-semantics-v1,
112observations, correspondence NOT_REQUESTED/0assessments: это не измерение
физической неподвижности, движения тела или независимого движения камеры.

`beat_hit_rate=0.9230769` соответствует существующему минимуму0.90.
`repeated_source_ratio=0.0` соответствует прежнему native максимуму0.02
и его независимой offline проверке. Все26пульсаций совпали; нет missing/wrong
luma. Contiguous decoded black-tail:82ожидаемых,82измеренных,82чёрных,
2boundary witnesses, начало19800000мкс, смещение0, matched=true.

Fresh decoded-face-exact-pts-v1:242ожидаемых/242измеренных, unknown0,
7потерь/7empty, face_loss_rate0.02892562<0.05. Времена потерь:
7400000,7416666,7500000,15400000,15500000,15600000,15700000мкс.
Они не скрываются и не превращаются в гарантию читаемости лица человеком.
Максимальный artifact0.010416667, colour jump0.11393852,
black block0.010416667. Temporal layer456frames.

Новый реальный inspector экспортирует27окон authored-clip-intervals-v1.
Все26визуальных source/output durations равны; все visual speeds1.
Все1270renderer output timestamps совпадают с rounded60fps clock.
Первые15 authored интервалов дают0мс пересечения. Но actual decoder texture
PTS26895666мкс общий у clip6/clip14, sourceIndex0: это один общий VFR кадр
при authored зазоре1мс. Других общих primary decodedPTS нет. Примерно
1/705primaryframes=0.00141844 — отдельная диагностика, а не замена
graph-based repeated_source_ratio0.0. Авторская реприза исключена из
случайного first-phrase overlap; строгого нулевого actual-pixel-repeat gate
не заявляется. SurfaceTexture timestamps около draw — не независимое
распознавание идентичности исходных кадров из encoded MP4pixels.

### Независимый звук и итог gate

Новый FFmpeg7.1 unclamped-f32 AAC report связан с точным новым MP4 SHA:
44100Гц,2канала,1867776PCM samples/933888frames,21176598мкс,
peak0.4500930607318878, RMS0.07753826874069104;
nonfinite/overfullscale/fullscaleplateau0, issues=[], passed=true.
Текущий quality_render_report заново декодировал этот же MP4 и получил
точно тот же audio report. Android acceptance=false только из-за
audio-unclamped-headroom-unavailable; offline разрешил только этот unknown.

Остались исключительно незаполненные человеческие пункты:
reviewer/date, playback1×/.5×, 7общих и3Heartbeat style checks.
`machine_and_human_pass=false`, CLI exit1 — ожидаемый незавершённый gate,
не полный успех. Текущие машинные критерии прошли; художественная приёмка
не проведена. Старые WhiteKing/v21/v22 оценки не переносились.

Контактный лист действительно открыт как диагностика. Видны портретные
кадры A, затем эхо, световые акценты/размытие и чёрные последние выборки.
Это редкие неподвижные выборки, без полноценного просмотра со звуком:
они не подтверждают органику, ритм, читаемость каждого кадра или10пунктов
человеческой приёмки. Совпадение размеров/метрик с v22 не означает одинаковый
экспорт: новый MP4 имеет другой SHA и новый runtime binding.

A уже использовался для настройки. Этот повтор — подтверждённая регрессия
на точной текущей сборке, не новый независимый родитель и не новая release
case. Полная готовность продукта или обновление файла на Яндекс Диске этим
отчётом не объявляются.

### Новая общая обратная связь пользователя о показанном Heartbeat

После показа именно `user_heartbeat_v23_a_20260927.mp4` пользователь написал:
«Текущий heartbeat монтаж получился очень хорошо».
Это положительная общая оценка показанного A-экспорта с SHA8b2fa8f0…2d8450,
не отзыв о C или другом продукте. Она не перенесена на все10пунктов формы:
факт просмотра1×/.5×, reviewer/date и отдельные проверки не выводятся из
этой фразы. В текущем gate человеческая приёмка остаётся незавершённой.
Корневой исполнитель отдельно сохранил feedback event в A-форме на
2026-09-27T14:20:42Z, сохранив все boolean checks/playback false.
Это время регистрации обратной связи, не выдуманное время полноценного
человеческого просмотра или reviewed_at.

Затем владелец явно уточнил: «Только на обычной скорости».
Отдельный event зарегистрирован2026-09-27T14:38:31Z; это не reviewed_at.
В этой конкретной A-форме playback_1x=true, playback_half=false,
reviewer/date пусты,7общих и3style checks по-прежнему false.
Подтверждение1× относится только к показанному A, не к HB(C), FEAR(C)
или DUALITY; полный человеческий gate не завершён.

## FEAR(C): фактический экспорт на новом пользовательском исходнике

Последующее явное уточнение владельца о FEAR: «Только на обычной скорости».
Отдельный event зарегистрирован2026-09-27T14:54:25Z для SHA09b2…d580c;
в его форме теперь playback_1x=true, playback_half=false. Reviewer/date и
отдельные checks не заполнены; это не вывод из Heartbeat-ответа и не новая
приёмка изменённой версии. Прежние нижеописанные empty-form наблюдения
сохраняют исторический смысл до ответов владельца.

Получены новые MP4/raw/inspector/contact на той же точной APK0.1.3/code4,
runtime split0/API36/x86_64. Raw `status=ok`, `recipe=FEAR_STROBE`,
`graph_generator=FEAR_STROBE_V1:exact`, source pool `fear-source-pool`.
Оригинальный C (`IMG_2249.MOV`) и render source совпадают по SHA
`0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23`,
`static_source=false`. Отдельная FEAR музыка SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Смысл исторического префикса blind-v1 в имени относится только к этой музыке.

Новые файлы `artifacts/quality/runs/`:

- `user_fear_v23_c_20260927.mp4`, 11088993байта, SHA
  `09b2fcb9040bf3a5b305ad2e98a5b921dd037f936c66c74de761e609639d580c`.
- `user_fear_v23_c_20260927.mp4.result`, 11208байт, SHA
  `debfa82e0c6eb424cf2bc45714dbec43dd5d9c9af27ba7faafdbb125919db93a`.
- `user_fear_v23_c_20260927-inspector.json`, 554370байт, SHA
  `cf9d433005b6ed785b004c6e316eeeb0a1093f35afc25f5c3e63886fa051ca9b`.
- `user_fear_v23_c_20260927-contact.jpg`, 247728байт, SHA
  `1abbafc46198af1dbb5a228976b6b50f0388616660986fc7cc118a34f1cbcce4`.
- Новая `.audio.json`, SHA
  `3766939d6d64ce1224191f95b2815a88275424f73a483d3048288e8b961e4a0f`.
- Новая пустая `.review.json`, первоначальный SHA
  `c7c1f3056440aa8e354dee87f10092f85b1a98ec314314aed0a9c1b04b450871`.

### Машинные наблюдения и движение

18.300с, 549кадров, 30клипов, 720×720, video/audio PTS0,
duration error33334мкс, A/V drift30658мкс, container issues пусты.
Все549renderer output timestamps совпадают с rounded30fps clock;
реальный inspector содержит30authored окон и пустые summary issues.
Это не Heartbeat: равенство source/output durations всех клипов не заявляется.

Полный профиль editorial-correspondence-v1:119observations/119assessments,
ASSESSED, 5валидных body samples и13camera samples во всём источнике.
Для фактической выбранной завязки измерены10samples, camera10/body5,
unknown5; conclusive5/inconclusive10. Motion samples4, longest run3,
span500000мкс, метод person-adaptive-affine-background-query-fov-v9.
Подтверждён требуемый минимальный run, а не непрерывное доказанное движение
каждого кадра всей3.6-секундной завязки. Unknown samples не названы статичными.

Первая каскадная фраза:13sampled clips, witnesses2/3/4;
вторая:14sampled clips, witnesses15/16/18. Точные witness IDs сохранены
в raw. Измерены/совпали30из30shutters, finale matched=true,
missing/wrong-luma/unexpected-black пусты. Beat hit0.9655172,
authored repeated_source_ratio0.0.

Fresh decoded-face-exact-pts-v1:437ожидаемых/437измеренных, unknown0,
13lost/13empty, loss rate0.029748283<неизменённого0.18.
Потери зарегистрированы около7.533–8.000с и не скрываются.
Max artifact0.010416667, colour jump0.015873775. Это не доказательство
человеческой читаемости лица при всех ракурсах/вспышках.
Часть диагностических gesture averages в raw равна NaN при
gesture_samples0; это отсутствующие жестовые наблюдения, не придуманный
нулевой жест и не валидные числовые witnesses.

### Независимый AAC, gate и ограничения

Новый unclamped-f32 AAC decode:44100Гц/2канала,
1615872PCM samples/807936frames,18320544мкс,
peak0.9242376089096069, RMS0.3369579729362241;
nonfinite/overfullscale/fullscaleplateau0, issues=[], passed=true.
Это прохождение текущего audio gate, не утверждение о произвольном запасе
громкости в децибелах. Повторное измерение quality_render_report точно
совпало с новым сохранённым audio report.

Native acceptance=false только из-за audio-unclamped-headroom-unavailable.
Вычисление offline resolved_headroom допускает только этот точный unknown
при независимом звуке. Итоговый gate exit1, machine_and_human_pass=false;
его issues содержат исключительно пустые человеческие проверки:
reviewer/date, playback1×/.5×, 7общих и3FEAR style checks.
Текущие машинные критерии пройдены, человеческая приёмка не выполнена.

При первой локальной проверке обнаружен отдельный дефект диагностического
поля: resolved_unknowns вывел [5.0] вместо строки audio unknown, потому что
локальное число unknown motion samples в FEAR ветке затенило имя audio
token. Решение resolved_headroom было вычислено раньше с правильной строкой;
дефект затронул выходное пояснение, не обход machine/human gate. О нём
сообщено владельцу tools; в этой локальной постобработке код не менялся.
До повторной проверки исправленного пояснения токен из ошибочного поля
не используется как доказательство разрешённой неизвестности.

Последующая проверка после исправления локального имени motion unknown
в quality_render_report действительно выполнена: тот же неизменённый C MP4,
существующие AAC/review без перезаписи, fresh AAC сравнение совпало.
Теперь resolved_unknowns точно равен
["audio-unclamped-headroom-unavailable"]. Итог по-прежнему exit1,
machine_and_human_pass=false, issues только человеческие. Исправлена
диагностическая строка; native APK, MP4, пороги и решение gate не менялись.

Контактный лист действительно открыт диагностически: завязка показывает
различные положения рук, затем FEAR title, разные портретные масштабы/ракурсы,
тёплые акценты и чёрные последние выборки. Ноги не подтверждены полностью
видимыми; full-body coverage не присваивается. Редкие выборки не заменяют
просмотр со звуком1×/.5× и не подтверждают ритм, уместность титра или финала.

Это реальный первый пилот FEAR на owner-confirmed отдельной съёмке C,
но не заранее sealed независимый release case. После native анализа/экспорта
C больше нельзя считать нераскрытым unused candidate для будущей серии.
Семантическая связь C с A/B для DUALITY не выводится автоматически.
Sigma не запускалась; прежние human reviews не переносились.

### Последующая общая обратная связь о показанном FEAR(C)

Владелец написал: «Новый fear тоже получился хорошо, где я в "полный рост"».
Корневой исполнитель привязал этот event только к FEAR(C)MP4
SHA09b2fcb9…9d580c, recorded_at2026-09-27T14:39:32Z.
Это положительная общая оценка конкретного экспорта. «Полный рост» —
формулировка владельца, не машинно измеренное full-body coverage и не
отмена ограничения диагностических30выборок выше. У этой формы оба
playback поляfalse, reviewer/date пусты и все checksfalse; просмотр1×
Heartbeat(A)на FEAR не перенесён. Время event не является reviewed_at.

## FEAR(A): настоящий отказ по неизвестному измерению движения

Новый `artifacts/quality/runs/user_fear_v23_a_20260927.mp4.result`,630байт,
SHA `a6d80a4284fd60b61a37d68a3f0f64ce6fe59ce1643c2388087108d6059402d5`.
Raw runtime binding APK2e54f621…2819e0b/API36/split0/x86_64 совпадает с
текущей сборкой; recipe FEAR_STROBE, оригинальный A/render source SHA
8e65b237…43aafca, static_source=false и FEAR music SHA2fc09a0c…85bce37.

Фактически `status=material_rejected`,
`rejection_code=insufficient_motion_evidence`, detail:
«FEAR needs reliable sustained measured movement; unknown samples are not static evidence».
Нового MP4 нет: корневой исполнитель проверил это на устройстве, локальное
назначение также проверено отсутствующим. Старые экспорты не заменялись.

Создана новая пустая `.negative-review.json`, связанная с exact raw SHA,
recipe, source SHA, null secondary source и точным rejection code.
reviewer/date пусты, material_case_confirmed/message_specific false;
никакая человеческая оценка не скопирована.

Этот отказ не является положительным FEAR и не подтверждает неподвижность
человека: отсутствует достаточное надёжное движение, а не само движение.
Он не закрывает требуемый отрицательный пример insufficient_motion на
достоверно статичном материале и не считается принятой человеком negative
case. Текущий краткий raw не содержит численных motion counts; количества
из v21/cache-only диагностики не выдаются за новые измерения этого прогона.
A уже экспонированная калибровка; этот исход не добавляет независимого родителя.

## Heartbeat(C): ещё один фактический пилот на текущей сборке

Новый `artifacts/quality/runs/user_heartbeat_v23_c_20260927.mp4`,13413877байт,
SHA `6f6e8bc3e6362b83700963d88a612366510f7cb07c1f8449938731b01cd7890a`;
raw `.mp4.result`,4956байт, SHA
`50ba2e1e4fe435e96088208be1d4161b5369d30f51674f17b6fe579cedbf5dea`.
MP4/raw хеши вычислены заново и совпадают с фактическим экспортом.
Runtime APK0.1.3/code4 SHA2e54f621…2819e0b/API36/split0/x86_64,
original/render source C SHA0e2d3903…f48e9c23, static_source=false;
отдельная Heartbeat music SHAcc98cef7…0669e2ef48.
status=ok, HEARTBEAT_V1:production/heartbeat-source-pool.

21.166с, 1270кадров, 27клипов, 720×1280, контейнер без issues,
PTS начала0, A/V drift3378мкс, duration error16000мкс.
Beat hit0.9230769≥0.90, authored repeated ratio0≤0.02.
Все26/26пульсаций совпали;82/82tailframes измерены и чёрные,
2boundary witnesses, matched=true, смещение начала0.
Fresh exact-PTS лица:232ожидаемых/232измеренных, unknown0,
2lost/empty, loss rate0.00862069<0.05; потери15600000/15700000мкс
не скрываются. Max artifact0.010416667, colour jump0.11841136.
Новый inspector подтверждает27окон, все26visual source/output durations
равны и speeds1;1270timestamps совпадают с rounded60fps clock.
Но inspector summary содержит отдельный diagnostic issue
`temporal_layer_sources_too_close_to_read`; он не скрывается и не является
пустым. Этот issue не попал в native acceptance issues или текущий offline
gate, поэтому прохождение их критериев не объявляется отсутствием всех
машинных диагностик. Читаемость временного эха остаётся отдельным риском.
Локальный разбор показывает4таких frames в clip18, output
13733333–13783333мкс: source requests333/17000/33667/50333мкс,
secondary request0 после ограничения отрицательного67мсoffset у начала
оригинала. Opacity0.62–0.59874. Actual primary textures33333/33333/
66666/66666мкс, secondary texture0. Это конкретная граница source handle,
а не отсутствующее движение, проблема AAC или перенесённая оценка A.
Отсутствие всех одинаковых encoded пикселей из этих полей не выводится.

Здесь editorial-semantics-v1,119observations, correspondence NOT_REQUESTED,
0assessments. Это не противоречит отдельно измеренному FEAR(C) full-profile:
возможности анализа различны, skipped correspondence не означает static.

Новые `.audio.json`/пустая `.review.json` созданы только для этого MP4 SHA.
Независимый FFmpeg7.1 AAC:44100Гц/2ch,1867776samples/933888frames,
21176598мкс, peak0.4500930607318878, RMS0.07753826874069104;
nonfinite/overfullscale/plateau0, issues=[], passed=true.
Offline gate заново измерил звук и подтвердил exact saved-report match;
разрешён только audio-unclamped-headroom-unavailable. Native acceptancefalse
с единственным этим unknown, полный offline gate exit1/false только из-за
пустых человеческих полей. У C нет перенесённой положительной оценки A.

Контактный лист открыт диагностически: видны разные позы рук/верхней части
тела C, крупный тёплый портрет в повторной фразе, эхо и чёрные последние
выборки. Full-body coverage, органика эха, весь видеоряд со звуком и1×/.5×
просмотр не подтверждаются контактным листом. Новый файл требуется оценивать
отдельно. C уже использован в FEAR перед этой попыткой; он пилот, не новый
неэкспонированный родитель для sealed серии.

## DUALITY(A,B): фактический экспорт первого порядка на APK0.1.3

Новые файлы в `artifacts/quality/runs/`:
`user_duality_v23_ab_20260927.mp4`,11896322байта, SHA
`f7e2752714b006d97243c438fc700f3a8528c4e0aaa474f323186b5402a8554a`;
raw `.mp4.result`,10111байт, SHA
`80a837473b27850f4f1ff72d779ceb37a9c08cbca141af9dde4878f95c2261a5`.
Оба хеша вычислены по локальным фактическим файлам. Новые inspector/contact,
AAC и пустая output-bound review относятся именно к этому MP4.

Raw status=ok, DUALITY_LOOP, runtime APK2e54f621…2819e0b/API36/split0/x86_64.
Ordered primary A SHA8e65b237…43aafca и secondary B SHA81629968…616b2
действительно сохранены; render primary hash совпадает, static_source=false.
Отдельная DUALITY музыка SHA
`ff759c051b806564e6012423d237df57f0798ce52825f7b24179dc20072cb3de`.

18.300с,549frames,25clips,720×1280,PTS начала0,контейнер без issues,
duration error33334мкс,A/V drift30658мкс. Authored repeated ratio0.0,
beat hit0.625≥собственного0.60. Fresh exact-PTS лица181/181измеренных,
unknown/empty/lost0,loss rate0. Max artifact0.0625,colour jump0.023286164.
Оба editorial-semantics-v1 профиля явно NOT_REQUESTED:112/58observations,
0correspondence assessments. Это не вывод об отсутствии физического движения.

Авторитетный для shader diagnostics inspector summary accepted=true,
issues=[],549planned/549shaderframes. Отмечены137speed-ramp и106virtual-camera
frames: цифровые преобразования не выдаются за независимое движение камеры
в оригинальном материале. Temporal layers0; Sigma effects не навязывались.

Независимый AAC:44100Гц/2ch,1615872samples/807936frames,18320544мкс,
peak0.8843885660171509,RMS0.19274843229837393,nonfinite/overfullscale/plateau0,
issues=[],passed=true. Current offline gate заново измерил звук и подтвердил
exact saved-report match, разрешив только audio-unclamped-headroom-unavailable.
Native acceptancefalse только по этому unknown. Offline exit1/false:
оставшиеся issues только7общих/3DUALITY human checks и identity/date/playback.

Контактный лист открыт диагностически: сначала A, затем оба героя в знакомой
обстановке, в поздних выборках B и A чередуются; последние выборки содержат B.
Это не подтверждение осмысленной связи, органики склеек, полного1×/.5×
просмотра или живого финала человеком. Пара давно знакома: новый runtime
экспорт не превращает её в новую независимую смысловую пару. Старые A,B
и B,A результаты/WhiteKing оценки не перенесены. Успех обратного порядка
на этой APK не выводится из этого первого экспорта.

## DUALITY(B,A): фактический обратный импорт на той же APK

Новый `artifacts/quality/runs/user_duality_v23_ba_20260927.mp4`,11896322байта,
SHA `65bf7a95ea490f56eb1a7617216f2cb7bc63e74219879cf261f160696f1b99c8`;
raw `.mp4.result`,10105байт, SHA
`9d1fda3c8f2524abac2f7db4898d3762823b61dccf7adaf97f0b2e7c45365ddf`.
Новые inspector/contact, AAC и пустая output-bound review отдельно получены
для этого MP4; оба хеша вычислены заново.

Runtime APK2e54f621…2819e0b/API36/split0/x86_64, status=ok,
recipe DUALITY_LOOP. Ordered source действительно B SHA81629968…616b2,
secondary A SHA8e65b237…43aafca, render source совпадает с первым B,
static_source=false. Авторская DUALITY music SHAff759c05…072cb3de.
Профили обоих входов editorial-semantics-v1/NOT_REQUESTED,
58/112observations,0correspondence assessments — не доказательство static.

Фактические18.300с,549frames,25clips,720×1280,PTS0,container issues=[],
duration error33334мкс,A/V30658мкс. Repeated ratio0.0,beat hit0.625≥0.60,
fresh faces181/181known,unknown/empty/lost0. Max artifact0.0625,
colour jump0.023286164. Inspector summary accepted=true,issues=[],
549shaderframes;137speed-ramp/106virtual-camera frames явно renderer intent,
не измерение физического движения камеры.

Новый независимый AAC report:44100Гц/2ch,1615872samples/807936frames,
18320544мкс,peak0.8843885660171509,RMS0.19274843229837393,
nonfinite/overfullscale/plateau0,issues=[],passed=true. Current report заново
измерил AAC и подтвердил exact match. Разрешён только тот же audio-headroom
unknown; native acceptancefalse по нему, полный offline exit1/false с
исключительно человеческими незаполненными пунктами. Человеческий успех
первого порядка не придуман и не перенесён на обратный.

Контактный лист открыт как диагностика, без1×/.5× аудиовизуальной приёмки.
Визуально последовательность знакомых героев сохраняется, хотя порядок
входных файлов обратный. Это ожидаемо для выбора смысловых ролей, не
доказательство органики всех склеек/осмысленной связи/читаемого живого финала.
Равный размер MP4/похожие выборки не означают одинаковые байты: SHA различны.
Два contact JPG фактически совпали по SHA
`06cd18b6ffbc3f7141fc52fc508d940164abe54a2b4ac27a930553c62ac5f25f`.
При нормализации sourceIndex обратно к идентичностям A/B все25authored
окон также совпали: первый clip имеет sourceIndex0 в A,B и1 в B,A.
Это наблюдение сохранённого выбора исходных ролей/окон при обратном импорте,
не независимое сравнение всех encoded пикселей или человеческая приёмка.
Оба порядка реально проверены на текущей APK, но это всё ещё одна знакомая
пара, не три независимые осмысленные пары и не полный release case.
