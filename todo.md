# Ревью Veykad / AutoEdit

> Это исходное ревью от 2026-09-16. Описания в карточках фиксируют состояние
> на момент обнаружения, а не обязательно текущий код. Статус P1 сверен с
> реализацией и [приёмкой P1](docs/p1-acceptance-2026-09-16.md) при подготовке
> baseline. Старые абсолютные ссылки внутри карточек относятся к прежнему
> расположению проекта.

**Текущий статус:** REV-001, REV-002, REV-003, REV-004 и REV-009 закрыты.
Рендер принадлежит `EditSession`, сохранение и публикация результата выполняются
через устойчивое хранилище, занятое задание блокирует разрушительную навигацию,
а источники центрируются с учётом ориентации и пропорций. Приёмка охватывает
эмулятор API 36 и синтетические входы; ограничения по физическим устройствам,
API 26/28 и полному визуальному регрессу остаются открытыми. Остальные карточки
не закрываются этим статусом автоматически.

Дата исходного ревью: 2026-09-16. Объект: рабочая копия того дня, включая незакоммиченные изменения и новые FEAR/DUALITY-файлы. Это backlog ревью с добавленными позже отметками закрытия.

Проверены продуктовый сценарий, Activity и XML-интерфейс, импорт/черновики/библиотека/экспорт, каталог стилей и музыки, оркестрация анализа и рендера, граф/таймлайн, MediaCodec/GLES/AAC, локальные ML-модели и кэши, QA/диагностика, сборка, тесты, вспомогательные инструменты и документация. Проверка кода не означает полного воспроизведения всех сочетаний устройств и медиа.

## Приоритеты и доказательства

- **P0 — критическая авария:** массовая недоступность приложения, потеря оригиналов пользователя или подтверждённая утечка. По текущим доказательствам P0 не выявлены.
- **P1 — высокая критичность:** потеря созданного результата/активной работы или заметное повреждение основного результата; закрыть до расширения пользовательского тестирования.
- **P2** — существенная функциональная, UI- или техническая проблема.
- **P3** — улучшение продукта, сопровождаемости или подготовки к распространению.
- **UI** — воспроизведено на эмуляторе; **код** — подтверждается конкретным путём исполнения, но отдельный device-сценарий не запускался; **долг** — отсутствующий контракт/механизм, а не утверждение о наблюдавшемся падении.

### Итоговая приоритизация

**Исходная оценка: 5 P1, 17 P2, 12 P3.** Все пять P1 закрыты; остальные приоритеты ниже относятся к дате ревью. Приоритет учитывает последствия, доступность сценария обычному пользователю и возможность восстановления. Статистики частоты нет; редкие codec-сбои не приравниваются к потере результата от обычного Back. Уверенность в доказательстве указана отдельно в карточках. Упоминание удаления исходников относится к приватным импортированным копиям приложения: удаление оригиналов в галерее этим ревью не установлено.

| Очередь | Приоритет | Задачи | Почему в таком порядке |
| --- | --- | --- | --- |
| 1 | P1 | **REV-003** — гонка сохранения и навигации | Ломается финальный шаг: пользователь уже создал результат и ожидает его сохранения; требуется защита файла и состояния Save. |
| 2 | P1 | **REV-009** — удаление результата по Back/перезапуску | Обычная навигация теряет готовую работу без возможности вернуться; восполнение требует нового рендера. |
| 3 | P1 | **REV-001** — жизненный цикл задания | Потеря длительной работы и callbacks уничтоженного экрана; общий источник нескольких гонок. |
| 4 | P1 | **REV-002** — сброс активной работы через библиотеку | Конкретный разрушительный путь; нужны существующий эдит и навигация во время работы, поэтому ниже обычного Back. |
| 5 | P1 | **REV-004** — искажение пропорций | Прямо повреждает основной продукт — видео; затрагивает входы с пропорциями, отличными от выходного формата. Перед правкой подтвердить синтетической сеткой. |
| 6 | P2 | **REV-005, REV-010, REV-019** — целостность импорта, копий и DUALITY-draft | Ложные/частичные файлы и неправильное восстановление. Обычно доступны повторный импорт или экспорт, оригиналы не утрачены. Делать рядом с исправлениями P1. |
| 7 | P2 | **REV-006, REV-007** — выбор видео | Блокируют вход в основной сценарий при отдельных разрешениях/обработчиках. Исправить единым picker-путём. |
| 8 | P2 | **REV-012** — бюджет анализа | Длинный вход способен исчерпать диск/память и время. Начать с дешёвого preflight и ограничения входа. |
| 9 | P2 | **REV-011, REV-013, REV-014** — зависания и ресурсы | UI-блокировка на API 26–28, неограниченное ожидание AAC, утечки при неудачной инициализации. Последствия серьёзные, но проявление зависит от версии/сбоя. |
| 10 | P2 | **REV-015, REV-016** — недостоверный QA | «Проверено» может не учитывать отказ части проверок. Исправить до использования gate как основания для выпуска. |
| 11 | P2 | **REV-008** — недоступные действия диалога после поворота | Воспроизведено; блокирует выбор стиля в текущем окне, но есть обход через возврат в portrait/переоткрытие. |
| 12 | P2 | **REV-020, REV-021, REV-022** — хранение, диагностические кадры, capabilities | Накопление файлов, неполная политика удаления/backup, зависимость от устройства. REV-021 обязательно проверить перед внешним распространением. |
| 13 | P2 | **REV-017, REV-018** — неверный прогресс и название результата | Вводят пользователя в заблуждение, но сами по себе не меняют и не удаляют MP4. Небольшие исправления можно включить в ближайший связанный патч. |
| 14 | P3 | **REV-025, REV-028, REV-026, REV-029, REV-027** — понятность и удобство | Требования к материалу, ошибки, preview, доступность и каталог. При подтверждении блокирующего TalkBack-сценария поднять соответствующий дефект до P2. |
| 15 | P3 | **REV-033, REV-032, REV-031** — регресс, окружение, release | Общая инфраструктура не должна задерживать точечные исправления. Регресс-тесты на исправляемые P1 нужны сразу; release-процесс становится обязательным перед распространением. |
| 16 | P3 | **REV-024, REV-023, REV-030, REV-034** — API и сопровождаемость | Window audio и gain не задействованы обычным полным экспортом текущих рецептов; далее — обоснованный рефакторинг и документация. REV-024 исправить раньше, если window-export используется для оценки музыкальной синхронизации. |

**Изменения относительно первой оценки:** REV-009 поднят P2 → P1 из-за потери готового результата при обычном действии; REV-005 понижен P1 → P2, поскольку сломанный draft можно импортировать заново; REV-023/024 понижены P2 → P3, поскольку текущий основной UI-сценарий их не использует. Это уточнение влияния, не закрытие дефектов.

### Выполненные проверки

- `./gradlew --offline --gradle-user-home .gradle-local testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL. 51 задача UP-TO-DATE, одна выполнена: это проверка актуальности результатов Gradle, не чистая пересборка и не принудительный повтор тестов. В XML-отчётах: **245 тестов, 37 suites, 0 failures/errors/skipped**.
- Android lint: **0 ошибок, 3 предупреждения** — IntentReset, SelectedPhotoAccess, InlinedApi. Первые два разобраны ниже; InlinedApi в `saveToGallery()` защищён проверкой API в вызывающем `saveResult()` и сам по себе не доказывает падение на API 26.
- Python: `python3 -m unittest discover -s tools -p 'test_*.py'` — 10 тестов прошли, один модуль не импортируется из-за отсутствия NumPy. В Python Стефании NumPy есть, но тот же модуль блокируется отсутствием Pillow. Полный Python-набор **не прошёл**, см. REV-032.
- UI: эмулятор Android API 36, 1080×2400, font scale 1.0; главный экран, каталог стилей, поворот открытого диалога. Установленный universal APK побайтово совпадает с локальной сборкой: SHA-256 `2e88fc8b82ebb993e0f844bfc62e8e585c04522a9ba064181bf6435cdf243f79`.
- Скриншоты: [главный экран](artifacts/project-review-2026-09-16/main.png), [стили](artifacts/project-review-2026-09-16/styles.png), [диалог после поворота](artifacts/project-review-2026-09-16/styles-rotated.png). Артефакты локальные, каталог `artifacts/` исключён из Git. Исходные настройки поворота эмулятора восстановлены.
- Новый полный рендер всех стилей, физические Samsung, Android 26–35, TalkBack, большие шрифты, нехватка диска и убийство процесса в этой проверке не прогонялись. Исторические acceptance-отчёты не считаются свежим прохождением этих проверок.

## Карточки REV-001–005 — жизненный цикл, геометрия и импорт

### REV-001 — У рендера нет владельца, переживающего Activity, и корректной отмены

- [x] **P1 · закрыто; исходный дефект.** `MainActivity` владеет executor и состоянием задания. Back на главном экране вызывает `finish()` даже во время работы; `onDestroy()` вызывает `shutdownNow()` и удаляет временные результаты. В циклах видео/AAC нет общего cancellation token; блокирующий вызов matte-provider также не отменяется этим механизмом. Прерывание executor не гарантирует остановку native-работы, а callbacks продолжают ссылаться на уничтоженную Activity. Пересоздание Activity/процесса не восстанавливает job.
- **Последствие:** потеря длительного рендера, продолжающаяся фоновая работа без экрана, гонка удаления/создания файлов; новый экран может удалить файлы ещё работающего задания.
- **Где:** [MainActivity.kt:119](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:119), [MainActivity.kt:134](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:134), [MulticlassMatteClient.kt:71](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MulticlassMatteClient.kt:71).
- **Сделать:** определить политику фонового рендера; вынести job и его состояние из Activity, связать cleanup с фактическим завершением/отменой, отбрасывать callbacks чужого job. Дать явную отмену.
- **Приёмка:** Back, Home, пересоздание Activity и завершение процесса на анализе/рендере/QA дают предсказуемое восстановление либо завершённую отмену без осиротевшего задания.

### REV-002 — Через библиотеку можно удалить исходники активного рендера

- [x] **P1 · закрыто; исходный дефект.** `updateReadyState()` блокирует выбор видео/стиля и запуск, но не «Мои эдиты». Во время рендера можно открыть сохранённый ролик и нажать «Создать новый монтаж». `showNewEditScreen()` без проверки `rendering/importing` удаляет `source-draft` и `pending-renders`. При импорте возможен тот же путь.
- **Где:** [MainActivity.kt:352](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:352), [MainActivity.kt:566](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:566), [MainActivity.kt:594](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:594).
- **Сделать:** разделить job-файлы и экранный выбор, запрещать разрушительный сброс занятого job либо сначала дожидаться отмены. Завершение рендера не должно самопроизвольно закрывать открытую библиотеку.
- **Приёмка:** начать работу → библиотека → старый эдит → новый монтаж; исходное задание либо остаётся целым, либо явно отменяется, без удаления используемых файлов.

### REV-003 — Сохранение гоняется с переходом к новому монтажу

- [x] **P1 · закрыто; исходный дефект.** В `saveResult()` выключается только Save. Пока worker копирует MP4, Back/«Создать новый монтаж» удаляет исходный pending-файл и сбрасывает состояние. `completeSave()` затем без идентификатора результата выставляет `renderedSaved=true` уже другому экрану. Переход к новой работе может повредить сохранение или его статус.
- **Где:** [MainActivity.kt:637](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:637), [MainActivity.kt:693](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:693).
- **Сделать:** отдельное состояние saving, привязка операции к immutable result ID и защита файла от cleanup до завершения. Нельзя применять callback к произвольному текущему результату.
- **Приёмка:** Save → сразу Back/New edit и Save → пересоздание Activity; готовый MP4 не теряется, новое состояние не становится ошибочно «сохранено».

### REV-004 — Рендер не сохраняет пропорции произвольного исходника

- [x] **P1 · закрыто; исходный дефект.** UI принимает `video/*`, однако renderer рисует полный texture quad во viewport 720×1280, а для FEAR — 720×720. Размеры отображаемого исходника не участвуют в матрице геометрии: применяется только одинаковый scale по X/Y и авторский transform. SurfaceTexture-матрица учитывает текстуру/поворот, но здесь нет политики fit/crop по соотношению сторон результата. Landscape/4:3/квадратный исходник и вертикальный исходник для FEAR растягиваются при несовпадении пропорций.
- **Где:** [MediaCodecSpeedRampRenderer.kt:637](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaCodecSpeedRampRenderer.kt:637), [MediaCodecSpeedRampRenderer.kt:1195](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaCodecSpeedRampRenderer.kt:1195), [VeycadAutomaticEditor.kt:270](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt:270).
- **Сделать:** явный контракт crop/fit с учётом display rotation и pixel aspect ratio; те же координаты применить к маскам, лицам, обоим источникам и QA.
- **Приёмка:** сетка с кругом в 16:9, 4:3, 1:1 и 9:16, rotation 0/90/180/270; круг остаётся круглым во всех стилях, включая смешанные пропорции DUALITY.

### REV-005 — Частично импортированный файл становится валидным черновиком

- [ ] **P2 · код + UI.** Импорт пишет прямо в окончательный файл внутри `source-draft`. При убийстве процесса `.onFailure` не выполняется. Восстановление берёт любые `isFile`, выбирает по имени/mtime и не сверяет завершение импорта, размер или наличие видеодорожки. На эмуляторе реально восстановился `.ready`, и «Создать монтаж» была активна.
- **Где:** [MainActivity.kt:245](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:245), [MainActivity.kt:300](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:300), [MainActivity.kt:735](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:735).
- **Сделать:** импорт во временное место, проверка метаданных, атомарная публикация manifest черновика с конкретными файлами/порядком/именами. Не выбирать источники обходом всех файлов каталога.
- **Приёмка:** завершить процесс на 10/50/99% одиночного и двойного импорта. Восстанавливается предыдущий завершённый draft, а не обрезанный файл/смесь двух импортов/служебный marker.

## Карточки REV-006–024 — функциональность, UI и надёжность

### REV-006 — Выбор одного видео требует полного доступа к медиатеке

- [ ] **P2 · код + lint SelectedPhotoAccess.** Для одного источника перед picker требуется `READ_MEDIA_VIDEO`/`READ_EXTERNAL_STORAGE`; при ограниченном доступе выводится «Разрешите полный доступ». DUALITY уже использует `OpenMultipleDocuments` без этого ограничения. Пользователь с разрешением только на выбранное видео не получает равноценного сценария.
- **Где:** [MainActivity.kt:82](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:82), [MainActivity.kt:159](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:159).
- **Сделать/приёмка:** использовать URI-based picker и для одного видео; проверить полный отказ, selected-only и отзыв разрешения. Импорт выбранного URI не должен требовать всей медиатеки.

### REV-007 — MIME-тип стирает URI в Intent галереи

- [ ] **P2 · код + lint IntentReset.** После `Intent(ACTION_PICK, EXTERNAL_CONTENT_URI)` присваивается `type = "video/*"`, поэтому data URI сбрасывается. Фактический Intent отличается от заданного контрактом; выбор/наличие подходящего обработчика зависит от окружения. Ошибка `launch()` не обрабатывается.
- **Где:** [MainActivity.kt:222](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:222).
- **Сделать/приёмка:** убрать дефект при унификации picker из REV-006; если ACTION_PICK останется, использовать `setDataAndType` и обработать отсутствие activity. Lint IntentReset должен исчезнуть.

### REV-008 — Открытый диалог стилей ломается после поворота

- [ ] **P2 · UI.** Открыть диалог в portrait → повернуть в landscape: действия «Отмена»/«Применить» оказываются за нижней границей окна. Высота списка вычисляется только в `setOnShowListener`, а Activity сама обрабатывает orientation через manifest и не пересоздаёт/перемеряет диалог.
- **Где:** [StylePickerDialog.kt:115](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/StylePickerDialog.kt:115), [скриншот](artifacts/project-review-2026-09-16/styles-rotated.png).
- **Сделать/приёмка:** пересчитывать доступное место при изменении window bounds/insets; portrait ↔ landscape при открытом диалоге, split-screen и font scale 2.0 сохраняют видимые действия и прокручиваемый список.

### REV-009 — Back с результата безвозвратно удаляет результат и выбранные исходники

- [x] **P1 · закрыто; исходный дефект.** Back с нового результата вызывает тот же destructive reset, что «Создать новый монтаж». При следующем `onCreate()` pending-результаты также безусловно очищаются. Нет возможности вернуться к подбору стиля на тех же исходниках или восстановить завершённый несохранённый монтаж. Share не делает результат устойчивым: отправляемый URI указывает на pending-файл.
- **Где:** [MainActivity.kt:121](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:121), [MainActivity.kt:566](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:566), [MainActivity.kt:705](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:705).
- **Сделать/приёмка:** отделить навигацию назад от удаления draft; сохранять последний результат с явной политикой удаления. Проверить случайный Back, перезапуск, смену стиля и чтение shared URI после возврата в приложение.

### REV-010 — Частичная локальная копия попадает в «Мои эдиты»

- [ ] **P2 · код.** `persistMyEdit()` сразу пишет в окончательный `.mp4`. Если `copyTo()` падает, например при нехватке места, присваивание `local` ещё не завершено и cleanup внутри `saveResult()` не охватывает частичный target. Библиотека показывает все `.mp4` без проверки завершения.
- **Где:** [MainActivity.kt:648](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:648), [MainActivity.kt:687](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:687).
- **Сделать/приёмка:** временная копия → проверка → атомарное переименование; cleanup при любой ошибке. Прерванный/непоместившийся файл никогда не появляется в библиотеке как готовый.

### REV-011 — Экспорт на Android 8–9 выполняет файловое копирование на UI-потоке

- [ ] **P2 · код.** Callback `CreateDocument` синхронно копирует MP4 в выбранный provider и затем ещё раз в private storage. Облачный/медленный provider или большой файл блокирует главный поток. В этой ветке также нет состояния saving и защиты от повторного запуска.
- **Где:** [MainActivity.kt:90](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:90).
- **Сделать/приёмка:** общий асинхронный механизм Save для всех API. На API 26/28 и медленном DocumentsProvider интерфейс отвечает, повторный Save не создаёт дубликаты.

### REV-012 — Анализ не ограничен длительностью/размером входного видео

- [ ] **P2 · код.** До проверки минимальной длины уже запускается полный анализ. Верхнего лимита/trim нет: provider сохраняет ARGB-кадр каждые 250 мс для всего видео, затем клиент удерживает observation/ML-данные. Для 270×480 это примерно 2 МБ временных ARGB-данных на секунду исходника, около 1.2 ГБ на 10 минут, без исходного файла и прочих данных. Две записи DUALITY увеличивают общую работу. Кэш ограничен тремя entries, а не байтами; reader допускает максимум 4000 observations, тогда как writer/анализ таких ограничений не имеют.
- **Где:** [MulticlassMatteProvider.kt:75](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MulticlassMatteProvider.kt:75), [MediaFrameVisualAnalyzer.kt:48](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaFrameVisualAnalyzer.kt:48), [MediaFrameAnalysisCache.kt:234](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaFrameAnalysisCache.kt:234).
- **Сделать/приёмка:** быстрый preflight до ML, бюджет диска/памяти/времени, ограничение или выбор диапазона, потоковая обработка/ограниченный cache. Длинное видео обрабатывается в заявленном бюджете либо отклоняется до дорогостоящего анализа с понятной причиной.

### REV-013 — AAC encoder может бесконечно ждать EOS

- [ ] **P2 · код.** В `AacEncoderMuxer.encode()` цикл `while (true)` не имеет deadline или отмены: бесконечный `INFO_TRY_AGAIN_LATER` не завершает job. В legacy `decodePcm()` аналогичная проблема. У video renderer и нового audio decoder watchdog уже есть.
- **Где:** [AacEncoderMuxer.kt:130](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/AacEncoderMuxer.kt:130).
- **Сделать/приёмка:** единая политика stalled-codec и cancellation; сценарий отсутствия выходных buffers/EOS завершается за ограниченное время, освобождая codec и позволяя повторить рендер.

### REV-014 — Ошибки инициализации оставляют native-ресурсы неосвобождёнными

- [ ] **P2 · код.** Encoder, input surface, GlSession и muxer создаются до `try/finally`. Исключение на следующем этапе не освобождает ранее созданные ресурсы. Аналогично audio decoder создаёт extractor/decoder и запускает configure/start до защищённого блока; присваивание через `apply { configure; start }` в декодерах также теряет handle при исключении внутри apply.
- **Где:** [MediaCodecSpeedRampRenderer.kt:99](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaCodecSpeedRampRenderer.kt:99), [MediaCodecAudioDecoder.kt:36](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaCodecAudioDecoder.kt:36), [SequentialBitmapDecoder.kt:84](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/SequentialBitmapDecoder.kt:84).
- **Сделать/приёмка:** resource scope начинается до первой аллокации; handle сохраняется до configure/start; освобождение одного ресурса не мешает освобождению остальных. Повторные ошибки codec/GLES не истощают лимиты процесса.

### REV-015 — «Проверено» не учитывает часть собственных проверок рендера

- [ ] **P2 · код.** `renderCandidate()` получает `container.issues` и inspector artifacts, но решение `acceptance` строится только из MIME/первых и последних PTS и visual samples. Ошибки inspector summary, например `shader_frame_count_mismatch`/`transition_without_two_live_decoders`, и `non_monotonic_sample_pts` не объединяются с итоговым gate. `ExportContract` также остаётся отдельным контрактом, а не общим источником результата.
- **Где:** [VeycadAutomaticEditor.kt:284](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt:284), [VeykadRenderInspector.kt:229](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeykadRenderInspector.kt:229), [VeykadRenderInspector.kt:566](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeykadRenderInspector.kt:566).
- **Сделать/приёмка:** единый итог по обязательным container/render/visual-checks с различием fatal и эстетических warnings. Сохранять playable кандидат при мягком QA-warning — намеренное поведение VME-010, его не нужно отменять. Однако hard failure нельзя подписывать «проверено».

### REV-016 — Ошибка face inference превращается в доказательство отсутствия лица

- [ ] **P2 · код.** В `LocalSemanticFrameAnalyzer` сбой face task сворачивается в `null`; возвращаемый Result не отличает ошибку от успешного «лица нет». `MediaFrameVisualAnalyzer` при любом ненулевом semantic-result очищает `latestFace`, затем сохраняет результат в кэш. `requireFaceEvidence` определяется по тем же observations. Если детектор сбоил на всём источнике, face QA может вообще не потребоваться.
- **Где:** [LocalSemanticFrameAnalyzer.kt:121](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/LocalSemanticFrameAnalyzer.kt:121), [MediaFrameVisualAnalyzer.kt:72](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaFrameVisualAnalyzer.kt:72), [VeycadAutomaticEditor.kt:345](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt:345).
- **Сделать/приёмка:** статусы present/absent/unknown и coverage успешных inference; деградировавший анализ не кэшировать как полноценный. Искусственный timeout face model не должен улучшать QA или превращать портрет в «лица не требуются».

### REV-017 — Прогресс достигает «2 из 1» и не отражает длительные стадии

- [ ] **P2 · код.** После кандидатов отправляется `VERIFYING(alternatives.size + 1, alternatives.size)`: при текущем одном кандидате получается «Готово 2 из 1». Сам `renderCandidate()` уже выполняет mux, inspector и decoded QA до отправки VERIFYING, поэтому всё это время UI остаётся «Рендерю варианты» с 0%. Возможный refinement начинается после формально завершённого прогресса.
- **Где:** [VeycadAutomaticEditor.kt:163](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt:163), [VeycadAutomaticEditor.kt:170](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt:170), [MainActivity.kt:483](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:483).
- **Сделать/приёмка:** callbacks на настоящих границах стадий, прогресс frames/time для encode, отдельный refinement. Всегда `0 ≤ completed ≤ total`, 100% только после готовности результата; пользователь понимает, что работа продолжается.

### REV-018 — DUALITY называется «Сигма» на экране результата

- [ ] **P2 · код.** `showResult()` распознаёт Heartbeat и FEAR, все остальные графы попадают в `else -> sigma.title`.
- **Где:** [MainActivity.kt:524](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:524).
- **Сделать/приёмка:** брать отображаемый стиль из типизированного result/catalog. Все четыре стиля показывают своё название после рендера; добавление нового не должно молча давать Sigma.

### REV-019 — Переключение стиля и перезапуск меняют состав DUALITY-черновика

- [ ] **P2 · код.** Состав восстанавливается по текущему `montageStyle.sourceCount`. После импорта двух видео → выбора одновидео-стиля → перезапуска восстанавливается только самый новый файл, но подпись берётся из `display_name` первого. Возврат в DUALITY в той же сессии больше не подхватывает второй файл из каталога: нужно импортировать заново. Порядок/имя могут перестать соответствовать реальному файлу.
- **Где:** [MainActivity.kt:742](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:742).
- **Сделать/приёмка:** manifest draft из REV-005 хранит все выбранные источники независимо от активного стиля; режим использует нужное подмножество. Проверить DUALITY → Sigma → restart → DUALITY с точным сохранением порядка и подписей.

### REV-020 — Нет управления накопленными копиями и восстановления экспорта

- [ ] **P2 · код, продукт.** Save создаёт private MP4 и отдельную MediaStore-копию; до очистки остаётся ещё pending MP4. Библиотека не умеет удалять/переименовывать, не показывает дату/длительность/превью и не хранит URI галереи. Если галерейный файл удалён отдельно, `showStoredEdit()` всё равно скрывает Save, хотя private-копия цела.
- **Где:** [MainActivity.kt:601](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:601), [MainActivity.kt:623](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:623), [MainActivity.kt:687](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:687).
- **Сделать/приёмка:** хранить запись результата и статусы копий, дать повторный экспорт и удаление с понятным охватом. Удаление из галереи не лишает возможности экспортировать снова; private storage можно освободить из приложения.

### REV-021 — Диагностика сохраняет кадры удалённых монтажей без отдельной политики

- [ ] **P2 · код, долг хранения данных.** Каждый production render записывает contact/transition/layer/opening JPEG и JSON в `filesDir/render-inspector`; pruning оставляет восемь отчётов. New edit удаляет исходник/MP4, но не эти изображения. В manifest включён `allowBackup=true`, явных backup/data-extraction rules нет. Это не доказательство фактической отправки кадров, но декларация «локально» не подкреплена политикой backup и удаления.
- **Где:** [VeykadRenderInspector.kt:328](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeykadRenderInspector.kt:328), [RenderWorkspace.kt:18](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/RenderWorkspace.kt:18), [AndroidManifest.xml:10](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/AndroidManifest.xml:10).
- **Сделать/приёмка:** определить, нужна ли запись изображений в production; отделить лёгкие обязательные QA-данные от debug sheets, задать retention/delete и backup exclusions. Удалённый проект не оставляет неожиданные изображения, а проверка clock не зависит от успешной записи JPEG.

### REV-022 — Capability planner не использует возможности реального устройства

- [ ] **P2 · код, долг.** Production request всегда получает `DeviceCapabilities.conservative()`: GLES2/2048/256MB/lowRam. Реальные codec limits и число одновременно доступных decoder instances не проверяются; renderer создаёт два декодера и фиксированный encoder, Heartbeat — 60 fps. Сильный телефон тоже всегда получает compatibility-план, слабый может упасть поздно при аллокации.
- **Где:** [VeycadAutomaticEditor.kt:29](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt:29), [RenderPassPlanner.kt:28](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/RenderPassPlanner.kt:28), [MediaCodecSpeedRampRenderer.kt:113](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaCodecSpeedRampRenderer.kt:113).
- **Сделать/приёмка:** обнаруживать capabilities до дорогой работы и определять поддерживаемый профиль без скрытой смены художественного результата. Проверить low-RAM, два 4K/HEVC источника, 60fps и устройства разных GPU/codec vendors.

### REV-023 — Аудиопараметры графа не исполняются экспортом

- [ ] **P3 · код, контракт движка.** Модель поддерживает `AudioTrack.gain` и `MASTER_AUDIO_GAIN`, factory создаёт этот parameter track, но AAC path не получает граф/gain и всегда кодирует исходные PCM-значения. Даже gain=0 не делает файл тихим. Текущие продуктовые рецепты используют default gain=1, поэтому это дефект API движка, а не наблюдавшаяся ошибка громкости текущего UI.
- **Где:** [MontageGraph.kt:36](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MontageGraph.kt:36), [NleProjectModel.kt:81](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/NleProjectModel.kt:81), [AacEncoderMuxer.kt:54](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/AacEncoderMuxer.kt:54).
- **Сделать/приёмка:** реализовать sample-clock gain/envelope либо явно запретить неподдерживаемые параметры. Декодированный экспорт с gain=0/0.5/1 соответствует заданной амплитуде, включая keyframes.

### REV-024 — Экспорт временного окна начинает музыку с нуля

- [ ] **P3 · код, debug/API.** `outputWindow()` правильно отбирает video frames по start/end и обнуляет их PTS. При mux передаётся только длительность окна, без `startUs`: `muxMusicFile()` декодирует/копирует музыку от начала трека. Окно середины монтажа получает неправильную музыкальную фразу и не годится как синхронный QA-фрагмент. Полный product export без окна не затронут.
- **Где:** [MediaCodecSpeedRampRenderer.kt:188](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaCodecSpeedRampRenderer.kt:188), [MediaCodecSpeedRampRenderer.kt:214](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaCodecSpeedRampRenderer.kt:214), [AacEncoderMuxer.kt:54](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/AacEncoderMuxer.kt:54).
- **Сделать/приёмка:** передавать audio timeline offset, учитывать loop и sample boundaries; аудио window-export совпадает с соответствующим диапазоном полного MP4.

## Карточки REV-025–034 — продукт, доступность и технический долг

### REV-025 — До запуска не объясняются требования к материалу и формат результата

- [ ] **P3 · продукт/код.** Экран не показывает минимальную длину, нужный тип сцены, длительность/разрешение/пропорции результата и замену исходного звука. Проверка длительности выполняется после анализа. FEAR технически допускает extension от 6 секунд в profile, но product orchestration требует 15; контракт рассредоточен. DUALITY нельзя предварительно просмотреть, поменять местами или заменить один из двух файлов.
- **Где:** [activity_main.xml:43](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/res/layout/activity_main.xml:43), [VeycadAutomaticEditor.kt:110](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt:110), [FearStrobeProfile.kt:113](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/FearStrobeProfile.kt:113).
- **Сделать/приёмка:** описать вход/выход в модели стиля, показывать требования и preview исходников до старта, проверять metadata при импорте; определить единый минимум FEAR. Пользователь понимает, что получится, прежде чем ждать рендер.

### REV-026 — Предпросмотр неудобен для оценки результата

- [ ] **P3 · UI/код.** VideoView имеет фиксированную высоту 500dp, автозапуск и бесконечный loop. Play/pause спрятан в тап по видео, нет видимого состояния, seek, времени и mute. Для короткой проверки конкретной склейки нужно ждать повторения, а на маленьком/горизонтальном экране Save и Share далеко ниже видео.
- **Где:** [MainActivity.kt:195](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:195), [MainActivity.kt:539](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:539), [activity_main.xml:252](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/res/layout/activity_main.xml:252).
- **Сделать/приёмка:** адаптивный aspect-ratio preview, доступные Play/Pause/seek/mute, явная политика pause/resume; управление и экспорт остаются достижимы на малом экране и с крупным шрифтом.

### REV-027 — Каталог заполнен шестью недоступными заглушками

- [ ] **P3 · UI/код.** Помимо четырёх рабочих стилей production-каталог включает Upcoming, Upcoming Lite, Next Motion/Film/Rhythm/Portrait; есть даже `minimumStylesForScrollablePicker`. Это демонстрационные строки без доступного действия, а не шесть разных полезных возможностей. Документ о picker утверждает, что dummy entries только в debug, что уже неверно.
- **Где:** [MontageStyleCatalog.kt:28](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MontageStyleCatalog.kt:28), [стили](artifacts/project-review-2026-09-16/styles.png).
- **Сделать/приёмка:** убрать фиктивные entries из production, оставить fixture в debug; рабочие стили снабдить коротким примером/превью и отличительными признаками. Проверка прокрутки не должна определять состав продуктового каталога.

### REV-028 — Ошибки и QA выведены техническим языком без пути восстановления

- [ ] **P3 · UI/код.** Progress сообщает «GLES, H.264 и AAC», результат — «QA: face-loss…», `showFailure()` выводит произвольный exception.message в Toast. Перевод привязан к поиску строк `at least 15000/6000`. Нет устойчивого error state с повтором операции или сменой материала. В интерфейсе также смешаны Veykad/Veycad, а файлы сохраняются в `Movies/Veycad`.
- **Где:** [MainActivity.kt:483](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:483), [MainActivity.kt:529](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:529), [MainActivity.kt:776](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt:776).
- **Сделать/приёмка:** типизированные ошибки, человекочитаемые причины и действия, детали в диагностике; единый пользовательский бренд. Проверить no-space, неподдерживаемый codec, повреждённое видео, QA-warning и ошибку preview без потери результата.

### REV-029 — Доступность кастомных элементов не проверяется системно

- [ ] **P3 · код, долг UI.** Строка стиля — LinearLayout с `isSelected`, а не radio/checkable control; видимый индикатор выключен из accessibility и нет явной radio/stateDescription-семантики. Видео кликабельно без доступного действия Play/Pause. `ContentDescription` и `SmallSp` глобально отключены в lint. Это пробел проверки, а не заявление о проведённом TalkBack-аудите.
- **Где:** [StylePickerDialog.kt:46](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/StylePickerDialog.kt:46), [app/build.gradle.kts:40](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/build.gradle.kts:40).
- **Сделать/приёмка:** checkable semantics, понятные accessible actions, осмысленные announcements стадии/ошибки; точечные исключения lint. Пройти весь путь TalkBack и keyboard/switch navigation, включая selected/unavailable состояния.

### REV-030 — MainActivity и renderer объединяют слишком много обязанностей

- [ ] **P3 · долг.** Activity (~795 строк) одновременно управляет навигацией, файлами, разрешениями, prefs, музыкой, job, preview и MediaStore; отдельные booleans допускают противоречивые состояния. Renderer (~1826 строк) соединяет ресурсный lifecycle, decoder pool, планирование, GPU и большой shader со style-specific branches. Новый стиль требует согласованных изменений в каталоге, музыке, pipeline, renderer, QA и UI — REV-018 уже показывает пропуск одной ветки.
- **Где:** [MainActivity.kt](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MainActivity.kt), [MediaCodecSpeedRampRenderer.kt](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/MediaCodecSpeedRampRenderer.kt), [VeycadAutomaticEditor.kt](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/java/com/example/autoedit/VeycadAutomaticEditor.kt).
- **Сделать/приёмка:** начать с job state и media repository, затем отделить codec lifetime и style specification. Не переписывать движок целиком ради архитектуры: каждый шаг должен закрывать конкретные гонки/дублирование и сохранять golden-поведение.

### REV-031 — Release-путь и размер дистрибутива не оформлены

- [ ] **P3 · долг.** Локальные debug APK сейчас примерно **92 МиБ arm64, 80 МиБ armeabi-v7a, 204 МиБ universal**; это не размеры release APK. В `app/build.gradle.kts` нет явного release-профиля с shrinking/resource shrinking и процедуры распространения; версия всё ещё 0.1.0/code 1. Документ VME-004 оперирует меньшими устаревшими размерами. Debug manifest содержит exported служебные Activity, поэтому debug APK не должен случайно стать внешним продуктовым артефактом.
- **Где:** [app/build.gradle.kts:1](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/build.gradle.kts:1), [debug/AndroidManifest.xml:1](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/debug/AndroidManifest.xml:1), [docs/VersionControl.md:88](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/docs/VersionControl.md:88).
- **Сделать/приёмка:** воспроизводимый release/AAB-процесс, version policy, инвентаризация native/model payload и измерение установленного размера; подписанный release проходит тот же рендер-регресс и не содержит debug activities/fixtures.

### REV-032 — Тестовая среда не воспроизводится из репозитория

- [ ] **P3 · воспроизведено, долг.** У Python tools нет requirements/pyproject с NumPy/Pillow/onnxruntime. Тесты не собираются полностью в двух доступных Python-средах. `gradlew` — укороченный launcher с приоритетом конкретного Homebrew JDK17, без использования `JAVA_HOME`; нет Windows launcher. README не описывает полный запуск.
- **Где:** [gradlew:5](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/gradlew:5), [tools/probe_semantic_alpha_support.py:10](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/tools/probe_semantic_alpha_support.py:10), [tools/evaluate_matte_probe.py:14](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/tools/evaluate_matte_probe.py:14), [README.md](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/README.md).
- **Сделать/приёмка:** стандартный wrapper и задокументированный JDK/SDK, ограниченные группы Python dependencies для тестов и ML-проб, команды bootstrap/test. На чистой машине все обязательные проверки запускаются по README без угадывания путей.

### REV-033 — Автоматизация не покрывает критический пользовательский путь

- [ ] **P3 · долг проверки.** Есть существенный JVM-набор и debug activities для ручных golden-прогонов, но нет `androidTest`-набора с UI/lifecycle/permissions/MediaStore и checked-in CI workflow. UI-тест выбора стиля проверяет модель/структуру, поэтому не ловит исчезнувшие после rotation кнопки. Golden-видео намеренно локальные — публиковать их ради CI нельзя.
- **Где:** [app/src/test](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/test), [app/src/debug](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/debug), [golden-mp4/README.md](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/testFixtures/golden-mp4/README.md).
- **Сделать/приёмка:** минимальные instrumentation-сценарии для REV-001–011, открытые синтетические fixtures для автоматического регресса, локальный manifest/runner для частных golden. Зафиксировать матрицу API/устройств и свежие результаты по каждому стилю.

### REV-034 — Документация противоречит текущей реализации

- **Разрешение (baseline 2026-09-25):** обновлены README, инструкции сборки,
  README модели и комментарий к QA; ранние карточки UI/Heartbeat помечены как
  исторические. `docs/bugs/` выбран текущим индексом движка, `bugs/` — архивом.
  VME-003/004/034 остаются отдельными задачами с их собственным статусом.

- [x] **P3 · закрыто; исходное несоответствие.** README и EngineReboot описывают удалённые UI/ML и «чистую оболочку», хотя они уже вернулись. README говорит об удалённом встроенном контенте, при этом четыре авторских M4A находятся в raw. README модели называет её debug-only, но provider находится в main manifest. Есть два журнала `bugs/` и `docs/bugs/`; документы о picker и APK-size устарели. Комментарий `VeycadAutomaticEditor` обещает успех только после QA, хотя сознательно возвращается best-available с warning.
- **Где:** [README.md](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/README.md), [docs/EngineReboot.md](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/docs/EngineReboot.md), [models/README.md](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/app/src/main/assets/models/README.md), [docs/reference/style-picker-dialog.md](/Users/l-v-polyakov/Documents/ChatGPT/AutoEdit/docs/reference/style-picker-dialog.md).
- **Сделать/приёмка:** короткий актуальный README с назначением, входными ограничениями, четырьмя стилями, сборкой/тестами и архитектурой; историческим документам дать явный статус, выбрать единый индекс дефектов. Сверить VME-003/004/034 с текущей реализацией, не объявлять их повторно исправленными по старым отчётам.

## Отдельная очередь проверок — дефект пока не доказан

- [ ] **HDR/цвет.** В renderer нет явного HDR→SDR/color-space контракта; источник принимается произвольный, encoder выводит AVC, а комментарий SequentialBitmapDecoder полагается на преобразование устройства. Проверить HDR10/HLG/Dolby Vision против контролируемого SDR-эталона на физических телефонах. Не считать один успешный Samsung-рендер доказательством корректного цвета на всех устройствах.
- [ ] **Честность QA на разнообразном материале.** Пороговые значения и authored-профили многократно настраивались на локальных reference/golden. Нужен независимый holdout: без людей, несколько людей, животные, тёмные сцены, быстрые движения, VFR, 24/25/30/60 fps, разные камеры. Сравнивать decoded gate с просмотром человеком; текущие 245 unit-тестов не измеряют обобщаемость визуального качества.
- [ ] **Малые окна, шрифты и insets.** Помимо доказанного REV-008 проверить font scale 1.3/2.0, 320dp, split-screen и системные панели: в XML фиксированные высоты/отступы и нет явной обработки WindowInsets. Не объявлять перекрытие status/navigation bars на всех устройствах без воспроизведения.

## Порядок работ

Пункты 1–3 ниже описывают исходную последовательность устранения P1; они
выполнены согласно [приёмке P1](docs/p1-acceptance-2026-09-16.md). Следующая
активная очередь начинается с оставшихся P2 и инфраструктуры проверки.

1. **Сначала защитить результат:** REV-003 и REV-009; закрыть разрушительный путь REV-002. Минимальные защитные изменения не должны ждать большого рефакторинга.
2. **Закрепить управление работой:** REV-001; рядом атомарные draft/save из REV-005/010 и восстановление REV-019. Регресс-сценарии из REV-033 добавлять вместе с исправлениями.
3. **Исправить геометрию:** REV-004 с синтетическими входами и проверкой координат масок. Это отдельная задача P1, её завершение не зависит от полного рефакторинга Activity.
4. **Далее очередь 7–13 из таблицы:** picker, бюджеты, зависания, QA, диалог и остальные P2. Быстрые REV-017/018 допустимы попутно, но не заменяют P1.
5. **После стабилизации — P3:** сначала понятность пользовательского сценария; release-подготовку завершить до распространения. Неподтверждённые проверки ниже не считать найденными авариями; при воспроизведении назначить приоритет по реальному влиянию.

Не являются дефектами сами по себе: отсутствие ручного таймлайна (one-director — явный продуктовый выбор), локальные частные golden вне Git, сохранение проигрываемого результата с эстетическим QA-warning, отсутствие сетевой загрузки пользовательского видео.
