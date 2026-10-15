# Архив статуса из README: пилотные проверки сентября 2026

Этот документ сохраняет прежний раздел «Состояние проекта» из README на коммите
`8b73fde87ae5b1056cb6cc2dd8998795e756f018`. Он перенесён 15 октября 2026 года
для сохранения истории и ссылок на доказательства.

Версии, формулировки «последняя», результаты и открытые проверки ниже относятся
к моменту составления отдельных записей. Они не описывают текущую сборку `main`
и не подтверждают качество новых экспортов, исходников или устройств.
Актуальное краткое описание — в [README](../../README.md), список протоколов —
в [каталоге качества](README.md).

---

Завершённая пилотная серия выполнена на `0.1.3/code4`, не опубликованном
полностью принятом релизе. 27 сентября 2026 завершены все шесть попыток v23
на разрешённых пользовательских A/B/C: пять MP4 и отказ FEAR(A)
`insufficient_motion_evidence` без MP4. Оба порядка DUALITY завершены;
последний фактический одновходовый источник C, монтажных worker серии нет.

Все пять экспортов прошли отдельное свежее измерение unclamped AAC и точное
повторное сравнение. Inspector принял четыре из пяти: у Heartbeat(C) остаётся
`temporal_layer_sources_too_close_to_read` в четырёх кадрах echo у начала
исходника. Поэтому общего утверждения «все машинные проверки чистые» нет.
Полный просмотр со звуком1×/0.5× и человеческая приёмка остаются незавершёнными.
Фраза владельца «Текущий heartbeat монтаж получился очень хорошо» относится
только к показанному Heartbeat(A), не к C или всем пунктам проверки.
Для этого A явным ответом «Только на обычной скорости» подтверждён только1×;
0.5×, reviewer/date и отдельные checks остаются незаполненными. Отдельная
фраза «Новый fear тоже получился хорошо, где я в "полный рост"» относится
только к показанному FEAR(C); позднее для него отдельно подтверждён обычный
просмотр1×, но не0.5×/подробные checks. «Полный рост»
здесь описание владельца, не измеренное full-body coverage.
Подробные bindings и ограничения — в
[результатах v23](../../docs/quality/user-v23-current-data-results-20260927.md).

Прежние numeric gate observations относятся к моменту проверки. Вводимый
fail-closed execution-evidence контракт требует новых полей, которых в
исторических v23 raw нет: под новым контрактом полный machine pass не заявлен.
Отдельные inspector diagnostics и fresh AAC evidence сохранены.

Предыдущая общая правка source-boundary/echo и fail-closed execution gate собраны
в локальную `0.1.4/code5`:551 JVM tests/179 Python tests pass, lint0errors,
security pass. На эмуляторе установлен точный APK e75f…ca44, начат новый
Heartbeat(A) v24 завершён с execution accepted=true и нулём clamped echo requests;
fresh AAC passed. Heartbeat(C) затем получил typed refusal
insufficient_distinct_moments без MP4: выбранное граничное окно не расширяется
с прежним evidence gate, а альтернативные невыбранные моменты пока не проверены.
Это не доказательство непригодности всего C. Оба worker завершены, последний
single снова C. Протокол: [v24 regression](../../docs/quality/user-v24-echo-boundary-preregistration-20260927.md).

Проверенный монтажный кандидат `0.1.5/code6` добавляет Heartbeat-only совместный
подбор альтернативных измеренных окон после selected-pool отказа. Успешный
старый путь сохраняется; оценки, grammar и gates не ослаблены. Полная JVM серия
565tests/75suites без fail/error/skip, Python179pass, lint0errors13warnings/securitypass.
Exact universal APK196329375bytes SHA
`c784516f5787ffacff039b83c0a2cdb2a174395255f8937f8b6430fb826932e1` собрана и
установлена без очистки данных. Frozen real A/C cache planning прошёл;
C потребовал три совместные замены, но это само по себе не decoded acceptance.
Оба native v25(A→C) завершены с actual execution accepted=true/issues[];
восемь artifact SHA device/local совпали. Каждый MP4 прошёл свежий independent
AAC decode и точное повторное сравнение. Все27 фактических окон C совпали
с cache planning, echo requests не выходят за границы источника. Это не
утверждение об отсутствии decoder-PTS повторов: у30fps C при60fps output
581 такой повтор, у A63. Новые human формы пусты, полный gate остаётся false
только по человеческой приёмке и не наследует отзывы v23/v24.
Протокол и точная сборка:
[v25](../../docs/quality/user-v25-measured-pair-candidate-plan-20260927.md),
[локальная APK0.1.5](../../docs/quality/apk-v015-measured-pairs-local-20260927.md),
[фактические результаты A/C](../../docs/quality/user-v25-native-results-20260927.md).

Предыдущая локальная APK `0.1.6/code7` уточняет пользовательские сообщения:
ограниченный подбор Heartbeat не объявляет всё видео непригодным. Монтажный
движок и пороги не менялись;578JVMtests/76suites pass, lint0errors13warnings,
security/signature verified. APK196329375bytes SHA
`5ba771a9dbc778738f15eadff3d2ca68533ab53bdd7d95678b7fa1bcd1a27ce7`
установлена без wipe с подтверждённым runtime base.apk SHA. Новых native
экспортов на0.1.6 не было: A/C остаются evidence0.1.5. Offline gate отдельно
проверяет корректность имени/даты human review, но не заполняет приёмку.
Positive release case требует также сохранённый inspector JSON, его exactSHA
и согласованные recorded evidence; полная offline suite205tests pass.
[Сборка0.1.6](../../docs/quality/apk-v016-honest-quality-local-20260927.md),
[лист просмотра A/C](../../docs/quality/user-v25-heartbeat-human-review-20260927.md).

Последняя проверенная локальная APK `0.1.7/code8` добавляет контроль фактически
декодированных кадров временного эха и сохраняет прежние монтажные пороги.
Fresh baseline:595JVM/242Python pass, lint0errors13warnings/security/signaturepass.
Exact APK SHA `c7d5ee0f55355da8c53f5a3b428dd6f2ca3b80ebac8378cd4c37185dd52ea8f5`
установлена и зафиксирована до двух native Heartbeat A→C. Оба прошли execution,
retained inspector и independent AAC; все27windows/1270frame records каждого
совпали сv25. Новые human формы пусты. Последующая работа над UI-тестами в
эту сохранённую APK не входит. [Сборка0.1.7](../../docs/quality/apk-v017-decoded-temporal-local-20260927.md),
[результатыv27](../../docs/quality/user-v27-native-results-20260927.md),
[просмотр новых A/C](../../docs/quality/user-v27-heartbeat-human-review-20260927.md).

На той же зафиксированной APK завершены отдельные проверки FEAR и DUALITY:
три технически проверенных экспорта и один отказ FEAR из-за недостатка
надёжных измерений движения. Файлы для визуальной оценки и точные результаты:
[лист просмотра](../../docs/quality/user-v28-active-products-human-review-20260928.md),
[протокол v28](../../docs/quality/user-v28-active-products-results-20260928.md).
Новые человеческие оценки пока не получены; эта серия использовала уже
известные тестовые материалы.

Локально собрана и установлена APK0.1.8/code9 с полным baselineVerify pass.
Она добавляет проверку последнего чёрного участка FEAR и включает исправления
интерфейса. [Точная APK и границы проверки](../../docs/quality/apk-v018-fear-tail-ui-local-20260928.md).
На0.1.8 завершены FEAR(A) с отказом по измерениям движения и FEAR(C) с
успешными техническими проверками. [Результатыv29](../../docs/quality/user-v29-fear-tailgate-results-20260928.md),
[лист просмотра](../../docs/quality/user-v29-fear-human-review-20260930.md).

Старые Heartbeat(A)/FEAR(C) и их отзывы сохраняются; новое окно/MP4 требует
нового просмотра. Новая APK не загружалась на Яндекс Диск и не объявлена релизом.
Все доступные A/B/C уже pilot: unused parents0, sealed release cases0.
Sigma только приостановлена, полная исходная цель не уменьшена и release ready
не достигнут. Открытые ограничения и задачи перечислены в [todo.md](../../todo.md).
Исторический архитектурный reboot описан в [docs/EngineReboot.md](../../docs/EngineReboot.md),
текущий конвейер — в [docs/EventDrivenEngine.md](../../docs/EventDrivenEngine.md).

Golden MP4 хранятся только локально в `app/src/testFixtures/golden-mp4/`, не
включаются в Git и APK. Отчёты о визуальной приёмке не заменяют проверку нового
исходника или нового устройства.
