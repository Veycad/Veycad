# v29: фактические FEAR прогоны на APK 0.1.8

План до запуска:
[`user-v29-fear-tailgate-preregistration-20260928.md`](user-v29-fear-tailgate-preregistration-20260928.md).

Перед первым стартом: emulator-5554 online/boot_completed1; установленный
`base.apk` SHA `35683fe6c277f5670ed3f39bca5c88c0759bd1aebd82e3a12e598d0e9b50eab4`.
Device SHA источников A/C и FEAR музыки совпали с планом. Source guard для
FEAR(A) против последнего single C прошёл. Оба назначенных v29 префикса
отсутствовали локально и на устройстве; golden worker отсутствовал.

28сентября2026 04:37:32.6376446UTC: FEAR(A) запущен с explicit `fear=true`,
sourceA; Activity `Status: ok`, appPID8219, workerTID8301.
Итог ещё не записан; процесс был жив на момент этой записи.

04:40:59.3631973UTC: worker8301 отсутствовал; на устройстве был завершённый
raw630байт, MP4 отсутствовал. Получен `status=material_rejected`,
`rejection_code=insufficient_motion_evidence`, source/render_source A, music и
runtimeSHA соответствуют плану; detail указывает на недостаток надёжных
измерений устойчивого движения. Это не доказательство физической статики.
Raw скопирован после terminal, device/local SHA совпали:
`0f6a88f20f5a552befc1dfe846b4f04f027b211e09e8deeb6e7f2167efca014f`.
Пустая отрицательная human форма создана отдельно, SHA
`825c0f597cdc2275904cd1c04ac3b8ae85f6a6628b12fb2f828d2ac9e816678f`;
material/message confirmation и reviewer/date пока отсутствуют.

После terminal A проверены source guard дляC с previousA, exact APK,
device source/music SHA, отсутствие worker и device/local C prefix.
04:43:20.0417512UTC: FEAR(C) начат с explicit `fear=true`, appPID8219,
workerTID8416. Исход на момент этой записи ещё не известен.

## Завершённый FEAR(C)

28сентября04:55:00.4621567UTC worker8416 отсутствовал; готовые MP4/raw/inspector/
contact скопированы после завершения. Все device/local SHA совпали. После
прерывания работы30сентября локальные файлы повторно проверены; эмулятор
отсутствовал, новый запуск не выполнялся.

| Файл после префикса `user_fear_v29_c_tailgate_20260928` | Байты | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 11125924 | `7fd2e8206b80860d5f0830ca12a7a9ebcf42e3f6529e276ccadc3cbfc7105d91` |
| `.mp4.result` | 11441 | `b8c6376dd6cf266c3dc7fe507dfd4380db4570774358efbe8232a4c566e2af03` |
| `-inspector.json` | 554530 | `cff93a0f082601b5dee0c6a3166e87a1d9aa5ec65953700a39f20815d2b863ea` |
| `-contact.jpg` | 247481 | `46c57301674a47cb30b9baa079a1a4f0fa8d17d2e6590727fa6d3907973199de` |

`status=ok`, exact APK0.1.8, source/render_sourceC, own FEAR music, graph
`FEAR_STROBE_V1:exact`. Actual execution accepted=true/issues[], сохранённый
inspector issues[]. 549кадров/18.3с, все30shutter matched, finale=true,
missing/wrong pulse lists и unexpected black пусты. Новый native gate не
отклонил корректный чёрный хвост. Beat hit0.9655172, repeated source0,
AVdrift30658мкс;437измерений лица/0unknown/12loss.

30сентября выполнен свежий independent float32 AAC decode, затем точное
повторное сравнение при полном offline gate. 44.1кГц/стерео,807936PCMframes,
duration18320544мкс, peak0.9242376089096069/RMS0.3369579729362241,
nonfinite/overfullscale0. Audio passed, SHA нового `.audio.json`
`1b2b8b880331bc797780eb7eb9570f1601715234f1ccfe763a175c57f426ecf7`.
Native `audio-unclamped-headroom-unavailable` разрешён независимым измерением.
Полный verdict=false ровно по12незаполненным human требованиям. Новая
`.review.json` SHA `761c2b02b26601529c53cbf4680709522a9821eef0163d45800180a3e7eefc80`
оставлена пустой.

Независимый FFmpeg framemd5 сравнил v28C и v29C: все549строк с временами и
декодированными пикселями совпали. Декодированный float32PCM звук тоже
побайтно одинаков:6463488байт, SHA
`beac218af5a8aaffc061aafc40587c0930cb77e5a7262139084c171912caa122`.
Сами MP4 имеют разные хеши; новая форма привязана к своему файлу. Отзыва
человека на v28 ещё не было, поэтому человеческая оценка остаётся открытой.

Серия завершена: два запуска, один отказ и один технически проверенный MP4.
Последний single sourceC. Независимых unused0, пилотов3, releasecases0;
Sigma приостановлена. [Лист просмотра](user-v29-fear-human-review-20260930.md).
