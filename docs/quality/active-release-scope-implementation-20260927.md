# Раздельная приёмка активных продуктов при паузе Sigma

Основание — read-only аудит `active-products-pause-audit-20260927.md` и
прямое пользовательское указание приостановить и заблокировать Sigma.
Полная цель из четырёх продуктов не сокращается; ни одно требование качества
не ослаблено. Изменены инструменты приёмки, не APK или монтажные директора.

## Проверяемый контракт

`quality_product_scope.py` задаёт точные четыре продуктовых ключа и два
разрешённых режима: все active либо только Sigma paused с фиксированным
user_requested_pause и точным текстом указания. Нельзя исключить отдельный
провалившийся case, поставить FEAR/Heartbeat/DUALITY на паузу через данные,
удалить ключ или оставить произвольное основание. Invalid scope добавляет
ошибки и возвращает все требования, а не убирает не прошедший продукт.

Scope помещается в манифест и автоматически входит в canonical manifest SHA
до раскрытия. make_seal/verify_seal и corpus inspection отдельно валидируют
его. Новый helper входит в baseline glob quality_*.py. Schema2matrix обязана
повторять scope полностью; mismatch/отсутствие/неверная схема блокируют gate
и scope_bound_before_disclosure. Legacy schema1 без scope сохраняет исходную
all4active semantics, не переписывает историческую серию под сегодняшнюю паузу.

Всегда выводятся четыре `products`:
Sigma paused_not_release_ready и requirements_passed=false. Её исходные
положительные/отрицательные requirements остаются в `products.SIGMA.gaps`
и общих gaps. Полный ready=false при paused независимо от успеха остальных.
Отдельный active_products_ready требует проверенного sealed корпуса, полного
общего технического покрытия и всех active-case/MP4/AAC/human gates.
Paused Sigma case вызывает явную ошибку и не даёт case count или exercised
coverage. CLI exit0 по-прежнему означает только full ready, не active-only.

Фиксация scope — не криптографическая подпись пользователя, не гарантия
отсутствия скрытого tuning и не фиксация расписания каждого будущего case.
Исторические seal/matrix не редактировались; новая проверка критериев может
честно выявить их несовпадение с текущим кодом вместо переподписания истории.

## Текущие реальные входы

Новые default inputs: `holdout-user-only-active-candidate-20260927.json` и
`release-matrix-active-candidate-20260927.json`. В них только два лично
присланных оригинала A/B с сохранёнными bytes/SHA и проверенными timing-report
SHA. Оба уже использовались для настройки и имеют role=pilot. Различные
файловые SHA не объявляются независимыми родительскими съёмками; camera model
не выдумана. Публичный исторический корпус не переименован в пользовательский.

Фактический запуск defaults: integrity_errors=[],pilot_media_verified=2,
holdout media_verified=0,corpus_ready=false. Матрица cases=[],errors=[],
verified_case_count=0,Sigma paused,все active not_release_ready,
scope_bound_before_disclosure=false,active_products_ready=false,ready=false.
Отсутствующие условия/камеры/форматы/независимые родители перечислены явно.
Такой кандидат не разрешено seal до новых не использованных исходников.

## Проверка реализации

Полный локальный прогон инструментария:166 Python tests,0failures.
Включает5scope tests, реальную проверку seal после удаления/сменыscope,
подменыAPK/отсутствияseal,23matrixtests и18новых aggregation tests.
Aggregation fixtures/mock decode/mock FEAR/DUALITY assess/mock corpusseal
явно синтетические: проверяют логику совокупности, не художественную приёмку
или настоящий независимый корпус. Синтетический полный active-case набор
может дать active=true,но full=false и Sigma остаётся непрошедшей;
отсутствующий FEARnegative/Dimportorder/human/AAC/coverage всегда блокирует.

Независимый заключительный read-only аудит:71focusedtests,0failures.
На настоящем synthetic seal verifier повторно подтверждено, что matrix/manifest
scope mismatch теперь даёт scope_bound_before_disclosure=false. Реальные
default JSON прочитаны UTF-8:0verifiedcases,все четыре ключа сохранены,
оба readiness false,2pilot. Нового обхода готовности не обнаружено;
аудит не является художественной или независимой корпусной приёмкой.

APK0.1.2 и ранее загруженный0.1.1 не изменялись, монтажи не запускались.
Следующий действительный montage baseline должен включить новый scope-протокол;
v22архив сохраняется как история до этой правки, не выдаётся за currentseal.
Человеческий просмотр новых Heartbeat/DUALITY и новый лично присланный
не использованный корпус всё ещё необходимы для завершения полной цели.

После этого scope-шагa отдельно устранён offline metric parity gap у
Heartbeat/FEAR; последующий полный прогон170tests описан в
`active-offline-metric-parity-20260927.md`. Исходные166tests в этом документе
являются доказательством именно этапа scope, не обещанием неизменности tools.
