# v25: план проверки measured-pair source selection Heartbeat

27 сентября 2026. Статус: реализация и cache-derived planning проверены,
native A→C серия регистрируется отдельно ниже. До фактического запуска должны
быть проверены frozen baseline SHA и runtime identity. Имя v25 не доказывает
release ready или готовый MP4.

## Полный объём цели сохраняется

Четыре самостоятельных продукта Sigma/Heartbeat/FEAR/DUALITY, собственные
grammars, требования к материалу/музыке, измерения и human acceptance. Только
Sigma остаётся paused/locked. Независимый разнообразный unused набор пока не
получен: A/B/C разрешены владельцем как pilot и уже использованы для настройки.
Этот этап не переименовывает их в holdout и не сужает исходную цель.

## Предварительная гипотеза

Прежний Heartbeat pool пропускает внутренние короткие окна с двумя соседними
реальными наблюдениями: центр на одном отсчёте при шаге250ms даёт лишь один
отсчёт в450ms. Требуется Heartbeat-only fallback по центрам measured pairs,
а не искусственная равномерная нарезка, изменение скорости или ослабление
readability/echo thresholds. Одной замены недостаточно как общего решения:
соседние резервы могут потребовать совместного перемещения.

Preferred selection и полный директор запускаются первыми. Успешный результат
сохраняется точно; поиск альтернатив разрешён только после типизированного
insufficient_distinct_moments. Каждый полный альтернативный пул проходит весь
реальный директор: composition permutation, live roles, hero finale, optional
interlude, aggregate repeated-source audit и actual scheduler echo bounds.
Возвращаются именно принятый пул и уже проверенный граф.

Necessary viability prune рассматривает superset всех15 возможных target uses
с прежними liveHandleStarts/readableExtension/echoFootprint. Это только доказательство
невозможности отдельного кандидата, не самостоятельная приёмка. Общий отступ67ms
для всех окон запрещён: есть роли без echo, а назначения меняются по композиции.

Поиск ограничен и отменяем. Предложенные limits: 2000000 charged candidate visits
(включая domain scans) и64 complete director validations. Достижение cap означает
незавершённый вычислительный поиск, не material rejection и не доказательство
непригодности видео. Даже исчерпание объявленного finite candidate domain не
доказывает отсутствие всех физических моментов вне этого домена.

## Проверки до native

- Preferred успех возвращает точные pool/graph без fallback и второго build.
- Actual233/267ms и250ms cadence: short pair windows имеют минимум два собственных
  наблюдения; membership повторно проверяется после clamp и rounding.
- Общий joint поиск допускает несколько замен, сохраняет semantic scores,
  строгую source distinctness и output1×.
- Полный director проверяется при обоих знаках echo, композиционных перестановках,
  finale/interlude, readable/unknown extension и допустимых no-echo границах.
- Cancellation и посторонние ошибки распространяются; честно различаются finite
  domain rejection и computational cap; stress4000 observations ограничен.
- Standalone JVM probe читает exact frozen actual C cache через production decoder
  и selector. Его доказательство относится только к planning, не к decoded output,
  звуку, художественному ритму или просмотру человеком.
- Полная JVM/Python suite, lint/security/build должны пройти без ослабления gates.

## Последующая native последовательность (ещё не запущена)

Последний фактический single source — Heartbeat(C) v24: typed refusal/no MP4.
Следующая зарегистрированная новая серия должна чередовать A → C с отдельными
неиспользованными именами output/result и после терминального выхода предыдущего
worker. Только разрешённые owner sources; музыка прежняя отдельная Heartbeat.
Sigma не запускать; FEAR, который понравился владельцу, не переделывать.

До запуска: exact universal APK/local+runtime SHA, новая version/code, frozen
pilot baseline текущей грязной копии, source/music SHA, source guard, отсутствие
локальных/device целей и отсутствие live worker. Не менять старые v23/v24 artifacts,
reviews и cache. Для каждого фактического успеха нужны MP4/raw/inspector/contact,
новый execution contract, свежий independent AAC decode и exact report binding.
Для отказа — честный raw и пустая output-bound negative review; без вымышленных
положительных artifacts.

Отзывы владельца относятся только к HBv23(A) SHA8b2f…d8450 и FEARv23(C)
SHA09b2…d580c, для обоих подтверждён только1×. Их нельзя переносить на новый
v25 MP4,0.5×, полный checklist или весь release. Нынешний DUALITY и независимая
матрица остаются отдельными незавершёнными требованиями.

## Первая фактическая focused проверка и изменение следующего шага

Новая production API скомпилировалась. Первый coordinated focused запуск
HeartbeatSourceFallbackTest + HeartbeatReservationTest + ProductSourcePoolsTest
завершился exit1: 38tests, 1failed —
`full_search_can_replace_multiple_selected_windows_and_is_deterministic`.
Остальные37, включая4000 observations stress, прошли; это не полная suite.
Неудачный тест сохранён, лимит64 и его требование нескольких замен не ослабляются.

Причина: обычный DFS может расходовать complete-director budget на множество
вариантов самого глубокого окна, не переходя к совместным заменам. Одного
max-rank-frontier недостаточно как общего решения:15бинарных доменов уже дают
32768пулов и при64callbacks сохраняют перекос к глубоким ролям.
Следующая правка — точные слои по суммарному ИСХОДНОМУ рангу кандидатов:
cost0, cost1 (первые одиночные альтернативы разных ролей), cost2 (пары и вторые
альтернативы), далее до полного finite family либо честного computational cap.
Ранги после compatibility filter не перенумеровываются; уже проверенные complete
пулы не строятся заново. Intermediate layer failure не является исчерпанием
полного домена. Этот порядок не обещает проверить все пары за64callbacks.
JVM/cache/native успех исправления пока не подтверждён.

Первый отказ именно вычислительный: visits125928/2000000,
validations64/64; он не стал material rejection. Stress4000 завершился за1.367s
на этом JVM запуске с установленным тестовым visit cap1000; это не мобильный
benchmark, не cold analysis и не гарантия времени для любого реального материала.

Read-only byte audit относительно frozen v24 tar подтвердил точное совпадение
семи текущих production files: FearDirector, FearStrobeProfile,
HeartbeatMontageProfile, HighQualityFramePlan, LayerCompositorModel,
RenderedMp4Acceptance и VeykadRenderInspector. Проверка хешировала бинарный
вывод конкретных tar entries и текущие файлы, без переименования/перезаписи.
Она подтверждает сохранение этих правил и порогов, но не заменяет новые tests
и реальные exports. Старые понравившиеся owner MP4 также не перезаписываются.

## Проверенный кандидат перед native запуском

После исправления порядка поиска focused40tests прошли. Полная JVM серия,
фактически выполненная при первом A-probe запуске:565tests/75suites,
failures/errors/skipped0. Повторный full build использовал эти up-to-date tests
и завершился SUCCESS1m14s: assembleDebug, lintDebug (0errors/13warnings),
verifySecurityContract. Последующая Python suite179tests прошла за3.190s.

Универсальная debug APK0.1.5/code6, min26/target36:
`artifacts/quality/apk/Veykad-0.1.5-measured-pairs-20260927.apk`,196329375bytes,
SHA-256 `c784516f5787ffacff039b83c0a2cdb2a174395255f8937f8b6430fb826932e1`.
Artifact/build-output SHA совпадают, signaturev2 verified. Установка `-r` без
очистки данных успешна; установленный version/name и actual base.apk SHA совпали.
После установки app PID отсутствует, новый монтаж ещё не запускался.

Реальные frozen cache probes A и C−67 прошли: A exact preferred pool+graph,
fallbackgenerated0/directorvalidations1. C legacy отказ воспроизведён,
fallback изменил роли0/2/7: generated2794/domain3260/visits83695,
directorvalidations2 включая preferred. Полный graph27clips/1270frames60fps,
26live1×, first15 overlap0/repeated0,467actual scheduled echo requests
в source bounds. Это planning, не decoded MP4/AAC/human acceptance.
Подробные byte bindings и история запуска:
[`user-v25-cache-planning-results-20260927.md`](user-v25-cache-planning-results-20260927.md).
C+67 отдельный cache-derived probe также реально прошёл, его JSON не подменяет
запланированный default−67 native экспорт.

Новые native targets в device golden directory и local artifacts/quality/runs:
`user_heartbeat_v25_a_measuredpairs_20260927` затем
`user_heartbeat_v25_c_measuredpairs_20260927`.
До snapshot/start проверено отсутствие всех восьми output/result/inspector/contact
целей в обеих средах. Source guard A после последнего actual C прошёл.
Device source A SHA8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca,
C SHA0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23;
Heartbeat music SHAcc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48
повторно подтверждены. Worker absence/runtime identity/targets проверяются ещё
раз непосредственно перед каждым запуском. Static mode/control exports запрещены
в этой двухпопыточной серии; только explicit heartbeat=true.

Cloud файл0.1.1 не заменён. Sigma locked, FEAR неизменён, unused0/pilot3,
sealed release cases0; исходная полная цель остаётся незавершённой.

Перед первым native start создан frozen pilot snapshot текущей грязной копии:
`artifacts/quality/baseline-v25-v015-measured-pairs-20260927.tar`,254151680bytes,
SHA-256 `70cc658348cc5c207a6c52707bde72a56da52553d31def4c03e915c15c271918`.
Включены app/src (включая tests/assets), build/config/gradle wrappers, tools,
quality docs и README. Этот snapshot не является независимым corpus seal;
последующие результаты добавляются отдельно, архив не перезаписывается.

Первая попытка preflight в16:33:47Z обнаружила отсутствие emulator-5554 и
остановилась на runtime check ДО `am start`; native attempt не начался,
source history не менялась. Read-only проверка devices/processes подтвердила
отсутствие emulator/qemu, не только наблюдательный timeout. Существующий
Fear_API_36 затем запущен hidden/no-window/no-wipe/no-snapshot-load на5554,
PID9364; boot завершён за36963ms, sys.boot_completed=1/device online.
После восстановления сохранены exact0.1.5 APK/code6/baseSHA, A/C/musicSHA
и private v18cache files. Cold boot не выдаётся за cold source analysis.
Новые native targets проверяются заново; первый actual start ещё впереди.

Heartbeat(A) фактически dispatched16:37:39Z после повторного device/worker/target
preflight. Мгновенный PID lookup был пуст во время startup; этот transient lookup
не привёл к повторному start. Далее подтверждены PID1987/TID2011
`veycad-golden-r`; source cache-hit112/editorial-semantics-v1/0correspondence
в device diagnostics16:37:42, decoder pool27clips/2decoders/1source/session reuse
в16:37:47. На16:38:48 worker ещё live, terminal result ещё не подтверждён.
Это не cold source analysis и пока не completed export. Следующий single после
терминального выхода этого worker — C, не второй A.

На16:49:47Z повторная authoritative проверка обнаружила A worker absent,
при сохранённом appPID1987/deviceonline и всех четырёх завершённых artifacts.
Device inspector event16:42:01accepted=true/issues[],1270/1270shaderframes,
dualdecoder467; это не точное время root-наблюдения выхода worker. Все четыре
новые A files затем скопированы и их device/local SHA совпали. MP4 SHA
`9b243d0c9463990c8c423418da4beb9fc4710652614beda5e95e1bccb1e2cf47`,13259304bytes;
raw SHA`8c25b6df22247c5a354196ab9a17df6e0ef16792051ed8c25c8dad0f7de8d867`,5496bytes.
Actual runtime/source/music совпали; tracepreferred-pool,generated0/visits0/director1.
Native acceptancefalse толькоaudio-unclamped-headroom-unavailable, execution
evidence/acceptedtrue с пустымиissues. AAC/human полный gate ещё отдельно.

Heartbeat(C) фактически dispatched16:51:38Z после successful C source guard
against actualA, свежих runtime/source/music/worker/target checks. PID1987,
новый TID6321 `veycad-golden-r` liveна16:52:06Z; device diagnostics cache-hit119
в16:51:39/editorial-semantics-v1/0correspondence, decoder pool27/2/1 в16:51:44.
Это reuse существующих source measurements, не cold source analysis.
C terminal result/MP4/AAC/human ещё не подтверждены; последняя фактически
запущенная singlesource теперь C, вторая попытка этой A→C серии.

## Завершение зарегистрированной A→C серии

На16:58:44Z authoritative ps-T при прежнем appPID1987/deviceonline подтвердил
отсутствие C worker6321 и наличие четырёх completed artifacts. Device inspector
event16:56:31accepted=true/issues[]; это не точное время root-наблюдения выхода.
На16:59:05Z файлы скопированы в новые local paths; каждый device/local SHA совпал.
C MP413332148bytes SHA`feca2e856df6cf9cbbdd2075eaa8e6f39e501021e9b92253fabc3082d0c03365`,
raw5486bytes SHA`53a39e330d1313e27fb1bc0abeea36cfa192e2c50d250df9203b52cc5d560591`.
Actual trace measured-pair-joint-v1/generated2794/domain3260/visits83695/
directorvalidations2 точно совпал с default−67 planning. Все27 native окон/ролей
и длительностей совпали с frozen C graph, включая отдельный interlude и finale.
Execution acceptedtrue/issues[],467echo requests в source bounds,26/26pulses,
82/82blacktail,230face samples/3lost/0unknown. Fresh AAC прошёл и повторный decode
точно совпал с сохранённым output-bound report. A также прошёл отдельный AAC.

Root просмотрел contact sheets A и C только как диагностику, не весь MP4 со
звуком и не человеческую приёмку1×/0.5×. У C581 adjacent decoder-PTS repeats
при30fps source→60fps output; это не физические60fps движения и не product
repeated-source ratio0. Оба новых human forms остаются пустыми; full gatefalse
только по12human requirements, независимо от прежних likedv23 MP4.
Подробные byte bindings и разграничение scheduled/decoded metrics:
[`user-v25-native-results-20260927.md`](user-v25-native-results-20260927.md).
Новых монтажей сверх зарегистрированных A→C не запускали. Sigma остаётся
locked/paused; independentunused0/sealedcases0/fullreleaseincomplete.
