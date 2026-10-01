# Heartbeat echo boundary: проверка общего исправления до native запуска

Проверка продолжает явно разрешённую работу только на пользовательских A/B/C.
Удачные неизменяемые v23 Heartbeat(A) и FEAR(C), включая точные пользовательские
отзывы, сохраняются. HB(A) просмотрен на обычной скорости; его half-speed и
подробная форма не подтверждены. Для FEAR(C) скорость просмотра не объявлялась.
Новая версия не наследует человеческую приёмку этих старых MP4.

## Обнаруженная причина и ограниченная правка

На v23 HB(C) четыре requested echo PTS были прижаты к началу исходника.
Рабочая правка учитывает фактические echo-frame позиции после назначения роли
и центрального обрезания каждого использования; учитываются whole-slot BUILD,
поздний finale echo и optional interlude. Непригодные handles получают существующий
типизированный отказ. Не меняются музыкальные треки, монтажная грамматика,
кривые эффектов, длительности, 1× скорость и пороги качества. Все реальные echo
requests должны укладываться в [0, sourceDuration), без планируемого clamping.

Отдельно закрывается потеря inspector issues: actual shader summary теперь
объединяется с decoded acceptance, а отсутствие/старый артефакт запрещает pass.
Raw получает четыре явных `render_execution_*` поля. Независимый AAC может
разрешить только прежний строго определённый headroom unknown, не execution
defect. Старые v23 raw не переписываются и не соответствуют новому raw-контракту.

## Предварительный порядок и неизменяемые доказательства

Сборка рабочей копии запланирована как 0.1.4/code5. До запуска нужны полные
JVM/Python tests, lint/security, SHA APK, новый pilot baseline snapshot и
побайтное совпадение установленного APK. Пока сборка/установка не подтверждены.

Последний фактический single = C (v23 HB(C)); DUALITY(A,B)/(B,A) это не меняют.
Предварительный порядок regression: Heartbeat(A), затем Heartbeat(C), включая
любой терминальный отказ. Новые имена:

- `user_heartbeat_v24_a_echohandles_20260927`
- `user_heartbeat_v24_c_echohandles_20260927`

До каждого native запуска проверяются отсутствие локального/remote output/result,
любой монтажный worker, точные device source/music/APK SHA и source guard с
фактическим предыдущим single. Никакого default Golden запуска (Sigma) нет:
ровно один явный флаг Heartbeat, без синтетических/control входов. Маркер сам
не доказывает завершение worker; все фактические артефакты запрашиваются после
его выхода. Не перезаписываются прежние выводы.

A SHA `8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`;
C SHA `0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23`;
Heartbeat music SHA `cc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48`.

Новые MP4/raw/inspector должны сохранять 1270 frames/27 clips/21166ms,
26 matched pulses, полную black tail, 1× live playback, authored accidental
overlap=0, readable decoded faces и контейнер/A-V. Проверяются requested echo
bounds и реальный shader inspector accepted=true/issues=[]; decoded PTS
фиксируются отдельно и не подменяются requested. Независимый unclamped float-AAC
повторно измеряется на конкретном новом MP4. Новые human формы пусты, без
копирования отзывов v23. Changed source windows/bytes сравниваются честно.

## Состояние полной цели

Это обычная pilot regression на трёх уже использованных родителях, не новая
независимость и не sealed corpus. Release matrix остаётся без verified cases;
вся исходная разнообразная цель сохранена. Только Sigma paused/locked, её
монтаж/настройка не выполняются. Яндекс Диск этим regression планом не обновляется.

## Фактическая фиксация после сборки, до первого запуска

Полная сборка `testDebugUnitTest assembleDebug lintDebug verifySecurityContract`
завершилась успешно за 2m20s. JVM: 551 tests / 74 suites, failures/errors/skips=0;
Python quality: 179 tests, pass. Lint: 0 errors / 13 advisory warnings; security
contract pass. Universal APK 0.1.4/code5 сохранён отдельно:
`artifacts/quality/apk/Veykad-0.1.4-echo-handles-20260927.apk`, 196312991 bytes,
SHA `e75fea6aee6c31ae973d63ea437347fe164891e22ea7bd13761bb0c7db5eca44`.
Подпись v2 проверена; min26/target36, universal включает x86_64 для эмулятора.

До native запусков создан pilot snapshot исходников/инструментов/документов и
настроек, включая грязную копию:
`artifacts/quality/baseline-v24-v014-echo-boundary-20260927.tar`, 254475264 bytes,
SHA `3544aba676832c39e2dc58b75aeb443fa6feaab4d6b60c69ff43cade8b4865f2`.
Это baseline текущей regression, не независимый seal.
Read-only adversarial сравнение с архивом v23 подтвердило побайтовую неизменность
Heartbeat profile, frame planner, layer compositor, decoded acceptance thresholds
и inspector. Fresh artifact binding: новый report path плюс прежняя проверка имени
и размера MP4; это не заявленный криптографический runtime-binding всей пары.

APK установлен без очистки данных на emulator-5554/API36/x86_64; version0.1.4/code5
и actual base.apk SHA e75f…ca44 проверены. После установки app не запущен,
монтажных worker нет. Device A/C/Heartbeat music SHA совпали. Первый новый
local/remote output/result отсутствует. Source guard A после фактической C пройден.

Первый Heartbeat(A) действительно запущен 2026-09-27T14:47:50Z,
app PID28589 / worker TID28609 `veycad-golden-r`; cache-hit112semantic frames
подтверждён, renderer начал27clips/1source/2decoders с session reuse.
Это не cold benchmark и пока не завершённый экспорт. Следующая single попытка
после его терминального результата/выхода worker — Heartbeat(C).

## Фактический итог двух зарегистрированных попыток

Heartbeat(A) завершён, worker28609 отсутствовал на14:50:01Z. Все четыре
артефакта получены; MP4 SHA cd5889f283a9d7cf52a3a7b566d00a18e158de973d41b1c4c91d091d2721b7e0.
Реальный execution contract/inspector accepted=true/issues=[], 1270frames;
clamp/offset mismatch=0 на467secondaryframes. Свежий AAC passed и saved report
точно совпал. Clips10/21 перемещены на+50ms относительно v23, прочие25windows
совпали; человеческая форма нового MP4 пустая, отзыв v23 не перенесён.

Heartbeat(C) затем действительно запущен14:51:06Z после новых проверок
source/music/APK/worker/отсутствия целей. Native cache-hit119semantics frames
в14:51:09Z, без нового inference. Worker отсутствовал на14:51:47Z;
фактический raw622bytes SHA
ccda3cbc07307a70b75b0abe620f2c0228a70e51944a44fd61af5e04847d0074:
material_rejected/insufficient_distinct_moments, без MP4. Новая negative форма
создана пустой. Последний фактический single теперь снова C.

Read-only разбор реальных v23 C windows/inspector: выбранный источник роли7
[0,450)ms требует выхода за границы для echo-67ms при любом разрешённом
назначении. Его исходное свидетельство на133333us имеет face=null,
subjectQuality=.19064/occlusion=.6396, поэтому расширение не проходит прежний
readability gate. Текущий joint assignment обязан использовать все15выбранных
моментов; альтернативный невыбранный момент он не запрашивает. Это ограничение
выбранного пула, не доказательство нехватки хорошего материала во всей C.
Запас альтернативных пригодных measured windows пока не установлен.

Run-as попытка копии имеющегося semantics cache на external storage отказана
Permission denied; исходный cache не изменён, локального diagnostic payload нет.
Следующий безопасный этап — корректный бинарный экспорт неизменённого cache,
точное воспроизведение выбранных domains и общий подбор альтернативных measured
окон с прежними thresholds/grammar/1×/disjointness/echo bounds. Новый native
прогон пока не запущен. Успех исправления для C, full release или новая cloud
доставка не заявлены. Подробные измерения:
[`user-v24-echo-boundary-results-20260927.md`](user-v24-echo-boundary-results-20260927.md).

## Последующая диагностика существующего кэша (не новый монтаж)

После описанного отказа external-copy выполнено другое безопасное чтение:
бинарный `adb exec-out run-as ... cat` существующего private semantics cache.
Кэш на устройстве не изменён; новый анализ видео, синтетические наблюдения и
монтаж не запускались. Локальный диагностический файл в `artifacts/quality/runs/`:
`v24c-diagnostic-v18-editorial-semantics-v1-250000-0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23.bin.gz`,
18564764 bytes, SHA-256
`21a2e6d655d46f9588aeff64074cca98bff547cb8a4324cc557c6b86880aa70f`.
Device/local SHA совпадают; schema18, editorial-semantics-v1, 119 observations
и 119 attachments, duration29700000us, correspondence assessments0.
NOT_REQUESTED не доказывает физическую статику или движение.

Разбор объясняет следующий шаг: при фактических соседних интервалах около
233/267ms внутренние окна450ms, центрированные на одном наблюдении, содержат
только один отсчёт и отсеиваются. Прежний генератор для этой длины оставляет
лишь [0,450) и [29250,29700)ms; второе конфликтует с уже выбранным концом.
Центры соседних реальных измерений дают дополнительные окна с собственными
двумя отсчётами: например [291,741)ms содержит actual PTS400000/633333us.
Это не расширение старого плохого окна, не новые кадры и ещё не проверка
готового графа/decoded MP4.

Согласована Heartbeat-only реализация: сначала неизменённый preferred pool и
полный директор; fallback только после типизированного отказа. Совместный
поиск нескольких замен использует measured-pair anchors, прежние оценки,
длительности, строгую непересекаемость, неизменённый readability/echo contract
и полный директор для каждого завершённого пула. Вычислительный лимит должен
давать отдельную ошибку незавершённого поиска, не приговор исходнику.
FEAR, Sigma и их source selection этим этапом не меняются. Реализация и её
проверки пока не завершены; новая APK/native серия ещё не зарегистрирована.
