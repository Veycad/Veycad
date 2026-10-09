# APK0.1.3: продуктовый интерфейс и восстановление, только локально

27 сентября2026, фактическая проверка11:48–11:53UTC. Это локальная
тестовая сборка, не новый выпуск по полной матрице качества и не новая
доставка на Яндекс Диск. Ранее доставленная0.1.1 сохранена отдельно.

## Файл и сборка

`artifacts/quality/apk/Veykad-0.1.3-product-ui-20260927.apk`,
196301170байт, SHA256
`2e54f621b539a03d339e5156a61bbe04b63f60e92bd2e02233db4897b2819e0b`.
Universal debug APK, package `com.example.autoedit`, versionName0.1.3,
versionCode4, minSdk26,targetSdk36. Подпись APKv2 проверена, один подписант.

Финальный `testDebugUnitTest assembleDebug lintDebug verifySecurityContract`:
BUILD SUCCESSFUL,535JVMtests/73suites,0failures/errors/skips.
Из них15новых puretests проверяют продуктовые подписи, минимумы/порядок
исходников и идентичность запросов музыки. Они не заменяют Android lifecycle
проверку. Lint0errors,13warnings; security contract пройден.
Отдельные170Pythonqualitytests также прошли,0failures.

APK установлен поверх прежнего без стирания данных на Fear_API_36,
API36,x86_64,emulator-5554: install-r Success. Фактический base.apk имеет
тот же полный SHA, что локальная копия. Версия в пакете и на экране —0.1.3/code4.

## Исправления

- Heartbeat,FEAR,DUALITY больше не показывают символ и историю Sigma.
  Короткие собственные подписи отражают пульс/репризу, движение/строб,
  две связанные сцены. FEAR не обещает белый конечный кадр: после финальных
  белых вспышек авторская структура имеет чёрный хвост.
- HB/FEAR: одно видео от15секунд; DUALITY: два видео, каждое от6секунд.
  Значения берутся из существующих native constants, пороги не менялись.
- Новая работа, восстановление, picker и очистка используют музыку текущего
  продукта вместо общего «Фонк». Старый draft.music другого стиля больше
  не затирает выбранную музыку при пересоздании Activity.
- Готовность требует музыку выбранного стиля. Поздние callbacks старого
  запроса отбрасываются после новой заявки, сброса или закрытия Activity.
- Восстановление использует упорядоченный source-0 как первый исходник,
  а не самый поздний по lastModified файл source-1 с чужим display_name.
- Sigma по-прежнему недоступна. Её режиссура не менялась, монтажи не запускались.

## Реальная проверка UI и lifecycle

Сохранились XML и PNG в `artifacts/quality/runs/`:

- `autoedit-v013-heartbeat-empty-20260927`:1видео/15секунд, собственная подпись
  и placeholder музыки; render disabled без источника.
- `autoedit-v013-fear-empty-20260927`:1видео/15секунд, собственная подпись,
  Fear122BPM; render disabled без источника.
- `autoedit-v013-duality-empty-verified-20260927`:2видео/6секунд каждое,
  собственная подпись,Duality108BPM; render disabled без источников.
- `autoedit-v013-picker-20260927` и `autoedit-v013-lock-tap-20260927`:
  Sigma🔒,enabled=false,clickable=false,selected=false. Фактическое нажатие
  на заблокированную строку не изменило выбранный Heartbeat.

Только пользовательские A/B скопированы без изменения в Documents эмулятора,
выбраны через стандартный SAF и импортированы в порядке A,B:
`19348088228456.mp4`, затем `19348085803624.mp4`.
`autoedit-v013-import-pair-result-20260927.xml` показывает оба имени в этом
порядке и Duality108BPM. Хеши внутренних source-0/source-1:

A `8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`,12965746байт.
B `81629968d2a0b41b355ec729d900f9b1cd2aec244a543f785796e5aa55d616b2`,6984557байт.

После переключения на Heartbeat показаны A и Heartbeat120BPM.
Потом CLEAR_TOP фактически пересоздал MainActivity: ActivityRecord изменился
189578919→163542887, PID остался13150. После возврата из сохранённого старого
результата без сброса draft экран снова показывает A и Heartbeat120BPM.
Это проверяет observer при живой EditSession со старым DUALITY draft.
XML `autoedit-v013-heartbeat-after-recreate-20260927.xml`.

Затем полный force-stop/start: PID13150→13811. Диагностика11:52:23UTC
`source_draft_restored`:source_count1,bytes12965746,musicheartbeat_author.
XML/PNG `autoedit-v013-heartbeat-after-process-restart-20260927` снова
показывают A,Heartbeat120BPM,version0.1.3/code4. Внутренние хеши обоих
импортов повторно совпали с оригиналами; saved edits не удалялись.

Первый capture `autoedit-v013-duality-empty-20260927` фактически содержал
ещё открытый picker с FEAR: слишком быстрые тестовые нажатия не завершили
выбор. Он не используется как подтверждение DUALITY; корректная отдельная
запись имеет suffix `-verified`. Никакие промежуточные UI captures не
выдаются за положительный монтажный результат.

## Граница доказательств

Новых монтажей/MP4 не было, alternation не изменилась: последний одиночный
реальный прогон был B, следующий должен использовать A.
Независимый binary audit сравнил16native enginefiles напрямую с v22tar:
HeartbeatDirector,FearDirector,DualityLoopDirector,VeycadAutomaticEditor,
VeycadEventPipeline,MaterialSuitability,LocalSemanticFrameAnalyzer,
MediaFrameVisualAnalyzer,SourceAnalysisTimeline,SourceAnalysisProfile,
ProductSourcePools,RenderedMp4Acceptance,RenderedVisualSampler,
VeykadRenderInspector,MediaFrameAnalysisCache,AacEncoderMuxer.
Все16побайтово совпали (SHA/length). Это не равенство APK и не переносит
приёмку старых экспортов на новую сборку.

Сохранённая0.1.2 имеет SHA
`69316f82176c96db404be3462430d1f524b481fe88f071b296cea5811ce18b09`;
Heartbeat v22MP4 и его review остаются связаны именно с ней.
Ранее загруженная0.1.1 имеет неизменённый SHA
`f902ed2fa02d741f37b7de368a9117f614eff4c959ab0716588a3016e984d2c7`.
Cloud APK не заменялся. Baselinev22 — история, нового sealed baseline нет.

Независимый пользовательский корпус и настоящие датированные просмотры
остаются незавершёнными. Эта UI-проверка не добавляет positive cases в
release matrix, не считается новым holdout и не закрывает полную цель.
Фактические default CLI после правок:holdout media_verified0,
pilot_media_verified2,integrity_errors[],corpus_ready=false;
matrix verified_case_count0,errors[],ready=false,
active_products_ready=false,scope_bound_before_disclosure=false,exit1.
