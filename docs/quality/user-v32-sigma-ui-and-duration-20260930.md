# APK 0.1.8: блокировка Sigma и проверка короткого материала

## Точная установленная версия

30 сентября2026 проверен `emulator-5554`, пакет `com.example.autoedit`,
versionName0.1.8/code9. SHA установленного base.apk и сохранённой копии:
`35683fe6c277f5670ed3f39bca5c88c0759bd1aebd82e3a12e598d0e9b50eab4`.
Новой сборки/установки не было. Golden worker перед проверкой отсутствовал.
Чужой `emulator-5558` не затронут.

## Реальная проверка интерфейса Sigma

MainActivity запускался поверх прежнего экрана результата. Обычная кнопка
назад вернула экран монтажа без удаления результата. Выбран Heartbeat,
preferences `montage_style.selected=heartbeat`.

В реальном списке стилей Sigma имеет видимый🔒и текст «Временно недоступно».
Её строка: enabled=false/clickable=false/selected=false. Heartbeat/FEAR/DUALITY
enabled=true/clickable=true, selected толькоHeartbeat. Upcoming — отдельный
неактивный placeholder, а не дополнительный принятый продукт.

По координатам из фактической XML hierarchy нажата отключённая строка Sigma.
После нажатия Sigma остаётся false/false/false, Heartbeat selected=true.
Hierarchy до/после tap побайтно одинакова. Диалог отменён, не применён;
selector label и preference heartbeat остались прежними. Native Sigma,
рендер и выбор видео не запускались.

Скриншот просмотрен агентом: замочек/неактивная строка видны, кнопки диалога
не обрезаны. Это проверка UI текущей установленной APK, не человеческая
визуальная приёмка монтажа и не полный повтор46UItests.

| Артефакт в `artifacts/quality/runs` | Байты | SHA-256 |
| --- | ---: | --- |
| `ui_v32_sigma_lock_v018_before_20260930.xml` | 6934 | `07953b7b4d5b4a4f99f73bad684e853293b0961f3957577fb7091acc997abc3a` |
| `ui_v32_sigma_lock_v018_director_20260930.xml` | 10668 | `9aa1a2af6028a652a2c1d897e62567b6756ce30ac5aa953bd7b847c8d970b0a9` |
| `ui_v32_sigma_lock_v018_dialog_20260930.xml` | 14873 | `19ba9fe594f64c7715658c0e491324d917d2269ea8bf7cf64ed3c75e21351da9` |
| `ui_v32_sigma_lock_v018_aftertap_20260930.xml` | 14873 | `19ba9fe594f64c7715658c0e491324d917d2269ea8bf7cf64ed3c75e21351da9` |
| `ui_v32_sigma_lock_v018_aftercancel_20260930.xml` | 10668 | `9aa1a2af6028a652a2c1d897e62567b6756ce30ac5aa953bd7b847c8d970b0a9` |
| `ui_v32_sigma_lock_v018_20260930.png` | 173267 | `f51386ee31cd55ef9f4bce204ce788ecde603d0c59a4bed8fe61808ee8bc9be0` |

Все local/device SHA совпали.18:12:02.3679395UTC после проверки worker отсутствует.

## Зарегистрированная до запуска одна negative попытка Heartbeat(B)

До начала попытки фиксируется prefix
`user_heartbeat_v32_b_duration_20260930`, exact установленная0.1.8,
explicit heartbeat=true, ownerB `19348085803624.mp4`, sourceSHA
`81629968d2a0b41b355ec729d900f9b1cd2aec244a543f785796e5aa55d616b2`.
Историческая фактическая длительность14.42с; Heartbeat требует≥15с.
Ожидаемый исход — typed insufficient_duration, но будет записан фактический.
Музыка прежняя `heartbeat-author_20260926.m4a`, SHA
`cc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48`.

Последний одновходовый actual sourceA (Heartbeatv30); guard проверяетA→B.
UI-onlyv32 и JVM planningv31 историю одновходовых материалов не изменяли.
Перед запуском: source guard, exact runtime/source/music hashes, свободный
device/local prefix, отсутствие worker. Один запуск, без повторов/ослабления
материального порога. После terminal: raw bytes и отсутствиеMP4, отдельная
пустая refusal review. Вычислительная ошибка не будет названа material refusal.

### Фактический исход Heartbeat(B)

2026-09-30T18:20:44.3074371Z начат единственный зарегистрированный запуск,
Start Statusok, PID1999/TID10520. Начальная проверка отсутствующего маркера
вернула ls-not-found при живом worker; это не было названо terminal и запуск
не повторялся.18:21:08.5424901Z worker отсутствовал, raw готов.

`status=material_rejected`, `recipe=HEARTBEAT`,
`rejection_code=insufficient_duration`, detail содержит минимум15000ms.
Runtime/source/render_source/music SHA соответствуют регистрации.
Во всём device prefix есть только один `.mp4.result`; MP4 и промежуточное
video отсутствуют. Raw582байта скопирован после terminal, device/local SHA
`d526bfc2a0bc253246e40ee1a121151027e8209a3f6cc250748abe7c939a1019` совпали.

Отдельная `.negative-review.json` создана пустой, связана с точным rawSHA,
recipe иB; её SHA
`3415486af7e2997cff85f2e04253a6a6f459693eb64832921ce8ec5b67b179c5`.
Человеческие material/message confirmations и имя/дата не заполнены.
Русское сообщение в MainActivity этой попыткой не проверялось: это actual
production material gate через debug entry, а не полный UI ошибки.
Последний одновходовый источник теперь B. Повторных попыток, новых APK или
правок режиссёров не было. Это negative pilot, не sealed release case.

## Граница результата

Sigma lock подтверждён на точной0.1.8, но качество отложенной Sigma не принято.
Три активных продукта имеют отдельные director ветки и семь описанных полей
грамматики в `README.md` этого каталога; это не независимая серия приёмки.
Свежий corpus verifier: unused/media_verified0, pilot_media_verified3,
integrity_errors[], corpus_readyfalse. Матрицаcases0, errors[], readyfalse,
active_products_readyfalse; SIGMA единственная paused recipe.
Четыре текущие формыv30HB/DAB/DBA/v29FEAR всё ещё без reviewer/date/1×/0.5×.
Завершение полной исходной цели не доказано. Текущие проверки продолжаются
только на имеющихся разрешённыхA/B/C, не требуют новых файлов для этой попытки.
Портрет/полный рост/несколько людей/без людей, тёмные/контровые/слабые сцены,
статика/интенсивное движение и разнообразие камер/fps/форматов ещё не доказаны
на независимых unused данных.22corpus gaps сохраняются; они не описывают
физическое отсутствие всех этих признаков в использованных пилотах.
Три active director пути напрямую проверены в текущем исходнике;
самостоятельный продуктSigma и его качественная приёмка остаются отложенными,
не объявляются готовыми вследствие подтверждения замочка.
