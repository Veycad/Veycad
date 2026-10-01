# v28: фактические FEAR/DUALITY проверки

Итог: все4зарегистрированные попытки завершены. FEAR(A) дал типизированный отказ
без MP4; FEAR(C) и DUALITYAB/BA прошли current execution/retained-inspector/
independent-AAC gates. Все human forms остаются пустыми. Это проверка активных
продуктов на знакомых пользовательских материалах, не независимая приёмка выпуска.

План до запуска: `user-v28-active-products-preregistration-20260928.md`.
Новые результаты относятся только к exact installed0.1.7/code8,
SHA `c7d5ee0f55355da8c53f5a3b428dd6f2ca3b80ebac8378cd4c37185dd52ea8f5`.
Последующие UI правки рабочей копии в этой APK отсутствуют.

## Восстановление и первый запуск

В03:33:20UTC adb devices пуст, emulator/qemu отсутствовали. Существующий
Fear_API_36 запущен28сентября03:34:39UTC, host PID17672, hidden/no-window,
no-snapshot/no-wipe, swiftshader_indirect, порт5554. Подтверждены boot_completed1,
version0.1.7/code8, exact base.apk SHA, всеA/B/C и FEAR/DUALITY music SHA.
Native worker отсутствовал; новыхv28prefix файлов на device/local не было.
AVD5556 принадлежит UI-тестам соседнего чата и не использовался.

03:35:39.097033UTC: FEAR(A) реально начат с explicit fear=true.
Source guard passed against previousactualC(v27HB), PID1998/TID2016.
ИсточникA `19348088228456.mp4`; run prefix `user_fear_v28_a_20260928`.
На момент этой записи worker ещё выполняется, результата нет.

Все6используемых offline tools побайтно сверены с frozenv27baseline:
quality_render_report,quality_inspector_report,quality_audio_report,
quality_review_form,quality_review_identity,quality_user_source_guard — совпали.
Это подтверждает версию проверок, но не подменяет результат native или human.

Полная цель остаётся открытой: unused0/pilot3/sealedcases0, Sigma paused/locked.
Общие положительные отзывыv23 не заполняют новые forms или half-speed просмотр.

## FEAR(A): завершённый отказ

03:39:47.037807UTC: root подтвердил отсутствие worker2016 и наличие только
нового raw. `status=material_rejected`, code`insufficient_motion_evidence`,
runtimeAPK/source/musicSHA совпали с планом. Detail указывает на недостаток
надёжно измеренного устойчивого движения; unknown не считается static.
Это тот же тип отказа, что наv23A, не доказательство отсутствия движения в видео.

Raw `user_fear_v28_a_20260928.mp4.result`,630bytes,
SHA `29448dbe07937c708480992e9a5c89ad61350b29aad5489312bd304b97d0110a`,
device/local совпали. MP4 отсутствует и на device, и локально, проверено явно.
Новая `user_fear_v28_a_20260928.negative-review.json`,406bytes,
SHA `6345c4e4a04e14f8fcc1918a1a704f319a6f681ad15fe6f972611c83489ffff9`.
Reviewer/date пустые, material_case_confirmed/message_specificfalse.
Это pilot refusal, не принятый независимый отрицательный release case.

## FEAR(C): начало

03:40:28.537721UTC: после terminalA и passed guard against actualA начат
`user_fear_v28_c_20260928`, explicitfeartrue, PID1998/TID2143.
RuntimeAPK, sourceC/musicSHA повторно сверены, prefix targets отсутствовали.
Результат на момент записи ожидается. Новых положительных оценок нет.

## FEAR(C): завершённый экспорт

03:52:35.705360UTC worker2143 подтверждён отсутствующим; MP4/raw/inspector/contact
завершены, затем скопированы. Device/local SHA каждого совпали.
Prefix всех файлов: `artifacts/quality/runs/user_fear_v28_c_20260928`.

| Suffix | Bytes | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 11125924 | `92f24c760562b90e053f4d5db6c8e68fd54174d5d710b09ef93093bde63c05e1` |
| `.mp4.result` | 11432 | `d8324a80c4e2adafe52580a27e94bc2575d5d2a3a8614f28a769e6f27e639019` |
| `-inspector.json` | 554503 | `7e87c1d238759d65c3bf660dfc78f502544fdcc9adc0a889176cc154d58cb691` |
| `-contact.jpg` | 247481 | `46c57301674a47cb30b9baa079a1a4f0fa8d17d2e6590727fa6d3907973199de` |
| `.audio.json` | 637 | `e4f340af1d12a77291182b8bb0b3d51c6d6feb59a8ef82dae89478df8671c1ee` |
| `.review.json` | 623 | `1c290f1e0d19589527c33968032bec70fa821630834eac488ed0af7e340cc030` |

Statusok, graph`FEAR_STROBE_V1:exact`, new method`decoded-texture-pts-v1`,
policy`no-temporal-layer-v1`; render_execution_acceptedtrue/issues[]. Retained
inspector issues[]. Все549secondarydecodedPTSnull корректны при0visible
temporal layers: FEAR spatial/shutter эффекты не требуют ложного второго input.

720×720,30fps,549frames/30clips/18.300s; shutter30/30, finale matched,
unexpected black absent, repeated-source0, beat-hit0.9655172, AVdrift30658us.
Source analysis119observations/119correspondence assessments; body5/camera13.
Opener: moving4, consecutive3/span500000us; measured10/body5/camera10,
unknown5/conclusive5/inconclusive10. Cascades sampled13/14 и три distinct
witnesses своей фразы в каждом. Эти числа не доказывают художественный смысл.

Все30authored source windows совпали сv23C. Frame records не полностью равны:
изменены subject_occlusion355,mask_temporal_iou529,subject_quality539,
face_region182,mask_confidence446 кадров; остальные сравниваемые значения,
включая source/output/decoded clocks, совпали. Реальная повторная обработка
исходника наблюдалась до появления video output. Причина различий semantic
estimates не устанавливается сравнением двух файлов; улучшение изображения
из этих чисел не выводится. Root просмотрел новый contact sheet, но это не
полный human playback. Лицо измерено437раз,unknown0,lost12,rate0.027459955
(вv23 было13lost); это также не самостоятельное доказательство улучшения.

Независимый fresh AAC и повторное сравнение passed:44.1kHz/stereo,
807936PCM frames,peak0.9242376089096069,RMS0.3369579729362241,
nonfinite/overfullscale0. Native audio-headroom unknown разрешён только
независимым float decode. Full gatefalse ровно по12human requirements;
новая форма полностью пустая. Likedv23FEAR SHA09b2…d580c повторно совпал,
его файл не заменялся.

Дополнительное независимое сравнение v23C/v28C через FFmpeg framemd5:
оба декодированы в549кадров, у503decoded pixel hashes различаются.
Таким образом, новые bytes нельзя считать только новым контейнером того же
изображения. Hash inequality не измеряет заметность отличий или их причину
и не является положительной/отрицательной художественной оценкой.
Отдельный aligned decode/SSIM дал Y0.969174,U0.993437,V0.992394,All0.977088.
Это сравнительное наблюдение, не новый порог качества и не основание переносить
отзыв пользователя. В частности, различие может включать кодирование; его
причина этим сравнением не установлена.

## DUALITY(A,B): начало

03:53:54.099229UTC: после terminal FEAR(C), source guard orderedA,B,
runtime/source/musicSHA и отсутствия targets/worker начат
`user_duality_v28_ab_20260928`, explicitdualitytrue, PID1998/TID4223.
Одиночная история остаётся C; пары не являются новым single-source чередованием.
На момент записи worker выполняется; BA ещё не запускался.

## DUALITY(A,B): завершённый экспорт

03:57:45.714259UTC worker4223 подтверждён отсутствующим; completed artifacts
скопированы с совпадением всех device/localSHA. Prefix `user_duality_v28_ab_20260928`.

| Suffix | Bytes | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 11921415 | `f99bdd2914c091bfc42e889d6df249799c5fb97aab899af63dde5567c0f0c332` |
| `.mp4.result` | 10705 | `137a12df84a85b1ee40c80a9030c64e4f31f24f3e5ae984d18c6cfc7b5d6845d` |
| `-inspector.json` | 468877 | `873b8a1c69674f4b361ed18d96281be50abc794e43543ba2de851e93723004e6` |
| `-contact.jpg` | 280726 | `ae8202af6e877993138656ad2ccdaa248e53e486445e1c6a68525baa0c24fa98` |
| `.audio.json` | 639 | `02d292900d1e1fb944fc55460cf13264107eebe06692b4130043f7d582e55746` |
| `.review.json` | 617 | `2a77cdfb6013c00139266ae7b4090427e9d8dbaf2752b5efe744910e17cff8bb` |

Exact runtime APK, statusok; policy`no-temporal-layer-v1`, new decodedmethod
и render_executionacceptedtrue/issues[]. Retained inspector issues[].
Все549secondarydecodedPTSnull допустимы для этого продукта. 25clips/549frames/
18.300s, source0A14clips/source1B11clips. Faces180measured/0lost,
beat-hit0.625,repeated-source0,AVdrift30658us. Контактный лист просмотрен root:
оба исходника видны, финал продолжает исходное видео; это не human story review.

Собственный independent AAC и повторное сравнение passed:44.1kHz/stereo,
807936PCMframes,peak0.8843885660171509,RMS0.19274843229837393,
nonfinite/overfullscale0. Full verdictfalse ровно по12human requirements.

С v23AB не совпадают19из25source windows:
indices1,2,4,5,6,9,10,11,12,13,14,15,17,18,19,21,22,23,24.
Также отличаются source/decodedPTS, speed и часть коротких effect values.
Нельзя заявить сохранение старого монтажа. Source summary measurements тоже
отличаются: например A yaw span85.11547→86.236664 и mean motion
0.4508812901164804→0.4508740135809473; B mean motion
0.46952109938037806→0.4694665717282172. Новые scores не доказательство улучшения.

Read-only сравнение frozenv23/v27 main sources подтвердило неизменность
DualityLoopDirector/Profile и MediaFrameVisualAnalyzer. Изменены только
HeartbeatDirector,MainActivity,MediaCodecSpeedRampRenderer,MulticlassMatteClient,
ProductSourcePools,VeycadAutomaticEditor,VeykadRenderInspector; добавлен
MontageFailurePresentation. Diff VeycadAutomaticEditor касается HB selection
и execution acceptance, ветвь DUALITY selection не менялась. Это объясняет
границы известного: измерения отличаются при неизменном директоре, но точная
причина изменения каждого ranking не доказана. Сравнение текущих AB/BA
проверяется отдельно и не заменяется сравнением разных версий/анализов.

## DUALITY(B,A): начало

03:59:21.700085UTC: после terminalAB, source guard orderedB,A, runtime/source/
musicSHA и отсутствия targets/worker начат `user_duality_v28_ba_20260928`,
explicitdualitytrue,PID1998/TID6217. На момент этой записи выполняется.

## DUALITY(B,A): завершение и проверка порядка импорта

04:02:27.739648UTC root подтвердил отсутствие worker6217 и завершённые artifacts;
копирование выполнено после terminal, device/localSHA совпали. Prefix
`user_duality_v28_ba_20260928`.

| Suffix | Bytes | SHA-256 |
| --- | ---: | --- |
| `.mp4` | 11921415 | `fd381727debc2d5098e5e02bcf93894fc9e127a4017c55e6fb3bb3e56f25e985` |
| `.mp4.result` | 10699 | `7e4ad60605dd544d3e73f71ff3c608199ec23f69b3e236272d556900a9754455` |
| `-inspector.json` | 468877 | `3d0862e824874fc17b188a5542b617f805e2ece39de670c670273bf33fabb3bb` |
| `-contact.jpg` | 280726 | `ae8202af6e877993138656ad2ccdaa248e53e486445e1c6a68525baa0c24fa98` |
| `.audio.json` | 639 | `2b944fbd76b22cad05a6915e522ccfe2075c49d99595ee39420e134d0e0b6e25` |
| `.review.json` | 617 | `ddf51b92c9183451044c4468f63c11303d58b572b51fd9c774a930048139f813` |

Statusok, exact runtimeAPK, current method/policy, render_execution acceptedtrue.
Retained inspector issues[]. Source1A14clips/source0B11clips: физический состав
совпадает сAB после смены индексов. Faces180measured/0lost,beat-hit0.625,
repeated-source0,AVdrift30658us. Fresh independent AAC и повторное сравнение
passed; полная оценкаfalse только по12human requirements.

Для AB/BA отдельно выполнено сравнение после нормализации каждого source_index
в реальный SHA исходника: все25physical source windows/output ranges/roles
совпали. Все549serialized frame records тоже равны, включая decoded clocks
и speed. Независимый FFmpeg decode/framemd5 подтвердил равенство всех549video
rows, включая timestamps и pixel hashes. Отдельный decode audio вfloat32PCM:
6463488bytes в каждом, SHA
`37f6340ab11cae2d91df0a20a75b31391b1811190739d134822f4a73a76df18b`.
Звук совпал побайтно после decode. Сам MP4SHA разный: raw и review сохраняют
собственные output bindings. Один реальный AB contact sheet просмотрен root;
BAcontact имеет тот же SHA. Ни одна форма human playback не заполнялась.

Это доказательство устойчивости текущего монтажа к перестановке именно этой
пары при текущих измерениях. Оно не доказывает независимость пары, осмысленность
истории или устойчивость к новым съёмкам/повторному анализу. Отличие отv23
остаётся явно описанным выше.

## Итоговое состояние

Ровно4попытки:1refusal+3MP4, без повторных запусков, Sigma и controls не запускались.
Последний single sourceC; пары его не меняют. Новых изменений APK/движка/порогов
не делалось. LikedFEARv23 сохранён. Три положительные и одна отрицательная новые
human формы пустые; технический pass не переносится в release matrix.
Просмотр: `user-v28-active-products-human-review-20260928.md`.
