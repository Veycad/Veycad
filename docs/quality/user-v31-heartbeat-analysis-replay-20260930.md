# Heartbeat: воспроизводимая проверка изменения данных анализа

## Подтверждённый результат

На одном и том же текущем production selector/director старый frozen анализ A
воспроизводит все27source windows реального Heartbeat v27, а новый frozen
анализ A — все27source windows реального Heartbeat v30. Изменены ровно
indices1,3,5,8,12,13,16,19,23,24, как в предыдущем сравнении MP4.
Это подтверждает локальную причину изменения выбора: разные измерения анализа
перед композицией, а не один лишь контейнер MP4 или кодирование.

Предпочтительный пул15моментов совпал полностью; изменилось их распределение
director по целевым ролям. Обе попытки идут preferred path, без fallback,
ослабления порогов, выбора альтернативного директора или повторного рендера.
Оба planning_contract_pass=true. Эта проверка касается планирования; она не
является новой декодированной/звуковой или человеческой приёмкой.

## Точные входы и результаты

Source A: лично присланный `19348088228456.mp4`, source SHA
`8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`.
В конце current source bytes на `emulator-5554` отдельно перепроверены.
Оба анализа editorial-semantics-v1,112observations/112attachments,
correspondence NOT_REQUESTED. Это не новое видео и не независимая съёмка.

| Артефакт в `artifacts/quality/runs` | SHA-256 |
| --- | --- |
| Старый snapshot `v25a-diagnostic-v18-editorial-semantics-v1-250000-8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca.bin.gz` | `31abb3fcd6f3cf067fd87bee76d0b9449cf83d5c62524219551cf7c4da6f870a` |
| Новый snapshot `v31a-diagnostic-v18-editorial-semantics-v1-250000-8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca.bin.gz` | `cc3b9e3d2fa225c06207810b24dec88ec9aa0d0ad9e0cb4d4a2a88d88ea5f19d` |
| `user_heartbeat_v31_a_cache_snapshot_20260930.json` | `84e3e342ce97c50b629c5499bb6f6794ff3a17e84678a065865f240678e18e10` |
| `user_heartbeat_v31_a_oldcache_planning_20260930.json` | `6b4bb1a05038d992a155913da74255cb021244dcaaad431cedfee5b81acd73f1` |
| `user_heartbeat_v31_a_newcache_planning_20260930.json` | `ebc05382bd59748caef6fcdc022a6d1dcad9daf93dd22f9b462b1f8ff2fcc7bf` |

Старый snapshot13,425,524байт уже зарегистрирован в прежней диагностикеv25.
Новый13,429,595байт скопирован из app-private cache без его изменения.
SHA текущего device cache до/после сохранения и local snapshot совпали.
Новый cache SHA добавлен только в opt-in JVM helper whitelist; это не изменение
APK или источник, автоматически разрешённый для монтажа.

## Что подтверждает журнал устройства

`LocalDiagnostics` содержит следующие события (literal device time):

- 27сентября18:30:30: Heartbeat(A)v27, editorial cache hit,112frames.
- 28сентября03:54:22: editorial анализ A сохранён заново,112frames,
 13,429,595байт,27,473мс анализа; совпадает с зарегистрированным запускомDUALITYABv28.
- 30сентября17:11:22: Heartbeat(A)v30, editorial cache hit,112frames.
- 30сентября17:20:40 и17:26:18: DUALITYAB/BA, тот жеA editorial cache hit.

Следовательно, v30 не пересчитывал A при запуске; он использовал данные,
созданные раньше. `load` обновляет mtime при успешном чтении, поэтому один
только mtime17:26 не является доказательством пересчёта или порчи cache.
Лимит3cache entries может вытеснять старые профили, но журнал не записывал
удалённый ключ: конкретная причина удаления старого A не объявляется доказанной.

Почему сами semantic результаты старого/нового анализа различаются, эта
проверка не устанавливает. Исторические source/runtime/output SHA известны,
но полный fingerprint среды первоначального анализа не записывался.
Без него нельзя приписать различие FEAR-tail исправлению, новой APK,
случайности ML или изменению исходных кадров. Прежние документы не переписываются.

## Улучшение измеримости, без изменения художественного монтажа

`tools/quality_cached_motion_report.py` теперь поддерживает:

- `--serial`: явный выбор устройства при нескольких подключённых эмуляторах;
- `--snapshot`: новую побайтную копию именно того cache, который прочитан и
  валидирован для JSON отчёта. Report и snapshot имеют разные цели,
  используются exclusive-create, исторические bytes не перезаписываются.

Чтение выполняется прежним read-only `adb exec-out run-as`; APK, исходный cache,
права Android и native pipeline не меняются. Проверка snapshots выполнена на
реальном разрешённом A, а unit fixtures проверяют только инструменты, не монтаж.
CLI отчёт честно оставляет source association filename-based; отдельная свежая
проверка реального source SHA записана выше, не выдумана helper.

Две попытки скопировать cache через внешнюю/временную директорию Android
отказаны системой доступа, cache не изменился. Пустая созданная временная
директория удалена; снимок сохранён read-only helper без root/SELinux изменения.

7новых tests: точные bytes/device, сохранность обоих старых targets,
разные назначения, отказ невалидному cache, legacy CLI/schema и отказ пустому/
дополненному пробелами serial. Первые6проверок не прошли до реализации.
После исправления сравнения tuple/list при JSON roundtrip полный файл30tests
прошёл; вся Python серия255tests/fail0/error0/skip0 прошла за12.125с.
Report: `build/reports/python-tests-cache-snapshot-20260930.json`.

Существующий `heartbeat-cache-probe.init.gradle` дважды вызвал настоящий
production planner: новый input28с, старый6с, оба BUILD SUCCESSFUL.
Только диагностическая test-компиляция; JUnit, сборка/установка APK и native
montage здесь не выполнялись. Сравнение normalized windows с actual inspector
проверило source_index/start/end/outputduration каждого из27clips.

## Граница результата

Понравившиеся v23 Heartbeat/FEAR и APK0.1.8 остаются неизменными.
Sigma не запускается. ВсеA/B/C остаются использованными пилотами; эта
диагностика не закрывает независимый корпус или человеческую приёмку.
Новый Heartbeat v30 не заменяет понравившийся и не объявляется улучшением.
Для следующих текущих QA серий сохраняется exact analysis snapshot до смены
набора cache другим стилем, чтобы качество сравнивалось с известными входами.

[Новые ролики для просмотра](user-v30-v018-active-human-review-20260930.md).
