# Текст и автосубтитры Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking. Execution is authorized by the owner's request to prepare the plan and implement the whole feature in PR 12.

**Goal:** Добавить пользовательский текст и редактируемые локальные автосубтитры для исходного видео и готового MP4.

**Architecture:** Отдельный экран завершения видео сохраняет порядок кадров и звук выбранного файла. Общая модель и Canvas-раскладка обслуживают предпросмотр и GLES-экспорт. Whisper работает на ограниченных PCM-окнах и освобождается до видеоэкспорта.

**Tech Stack:** Kotlin, Android API 26–36, Canvas, MediaCodec/MediaMuxer, GLES2, whisper.cpp 1.8.7, multilingual base, NDK 28.2.13676358, CMake 3.22.1, JUnit и Android instrumentation.

**Spec:** [Подтверждённая спецификация](../specs/2026-10-10-text-subtitles-design.md).

**Execution notes:** План исполняется в этом чате. JVM-модель, часы, окна,
outcome/parser/integrity и регрессии проходили RED→GREEN. Первые UI-сценарии
добавлены после начальной реализации экрана: pre-implementation RED для всего
UI не заявляется. Отдельный device-RED воспроизвёл откат A→B при recreation,
подмену первой аудиодорожки и ошибку длительности при ненулевом PTS. UI-RED также проверил доступность
кнопок через прокрутку и привёл к переносу системных отступов за пределы ScrollView.
Подготовка модели размещена в основном `app/build.gradle.kts`: отдельный
подключаемый `.gradle.kts` вызывал падение UAST Android lint.

## Global Constraints

- Все изменения остаются в `codex/text-subtitles`, PR 12; слияние вручную.
- Обработка локальная, без INTERNET, аккаунта, ключа и разрешения микрофона.
- Заголовок по умолчанию 0–3 с; интервалы `[startUs,endUs)` ограничены видео.
- Четыре шрифта с кириллицей, три положения, три цвета, прозрачная/тёмная/акцентная подложка, none/fade/slide/scale.
- Новые TITLE <=180 Unicode code points/3 строки; plate/motion в x=.08.. .92/y=.10.. .85, без пересечений.
- Субтитры максимум в две строки; RU/EN/авто; исправление текста и времени.
- Исходное видео сохраняет длительность, ориентацию, порядок кадров и слышимый звук.
- Готовый музыкальный эдит распознаётся по своей дорожке; удалённая речь не восстанавливается.
- Веса не входят в Git; закреплённые артефакты проверяются SHA-256 и входят в APK.
- ARM64/ARMv7 поддерживаются сборкой; x86_64 используется для проверки на эмуляторе.
- Новый MP4 публикуется только после успешного mux и проверки контейнера.

## Review Focus

- Ротация 90/270°: предпросмотр и экспорт используют одну геометрию.
- Ненулевой PTS и короткая звуковая дорожка: тишина сохраняет синхронизацию, реплики не зацикливаются.
- Исправленные субтитры: повторный STT не заменяет их без подтверждения пользователя.
- Отмена и смерть процесса: прежний MP4 и черновик сохраняются, временный результат не публикуется.
- Тишина и длинные видео: нет придуманных субтитров и загрузки всего PCM в RAM.

### Task 1: Модель, черновик и сегментация

**Files:** Create `app/src/main/java/com/example/autoedit/TextEditProject.kt`, `TextEditStore.kt`, `CaptionSegmenter.kt`; tests `TextEditProjectTest.kt`, `TextEditStoreTest.kt`, `CaptionSegmenterTest.kt`.

**Interfaces:** `TextEditProject(sourcePath:String, durationUs:Long, width:Int, height:Int, layers:List<TextLayer>, captions:List<CaptionCue>, captionStyle:TextStyle)`; `activeLayers(timeUs:Long):List<TextLayer>`; `TextEditStore(directory:File).save(project)` / `load(sourcePath:String):TextEditProject?`; `CaptionSegmenter.merge(existing:List<CaptionCue>, incoming:List<CaptionCue>, durationUs:Long):List<CaptionCue>`.

- [x] Написать тесты: заголовок короткого видео ограничен 800000 us; интервал исключает end; неверное время отклонено; кириллица/переносы и исправления переживают сохранение; разные файлы не смешиваются; дубликат окна не повторяется.
- [x] Запустить `:app:testDebugUnitTest --tests '*TextEdit*Test' --tests '*CaptionSegmenterTest'`; ожидается отказ из-за отсутствующих классов.
- [x] Реализовать immutable-модель, валидацию, Properties-черновик с атомарной заменой и явной версией, объединение пересекающихся фраз.
- [x] Повторить тесты; ожидается PASS. Просмотреть diff и создать `feat(text): добавить модель текста и устойчивый черновик`.

### Task 2: Раскладка и экспорт с исходным звуком

**Files:** Create `TextLayerLayout.kt`, `TextPcmDecoder.kt`, `TextAudioMuxer.kt`, `TextVideoExporter.kt`, `TextVideoGeometry.kt`; modify `MediaCodecSpeedRampRenderer.kt`; tests `TextVideoGeometryTest.kt`, `TextPcmTimelineTest.kt` и `TextExportDeviceTest.kt`.

**Interfaces:** `TextLayerLayout.draw(canvas:Canvas, project:TextEditProject, timeUs:Long)`; `TextVideoGeometry.oriented(width:Int,height:Int,rotation:Int):Pair<Int,Int>`; `TextPcmDecoder.decode(source:File, directory:File, sampleRate:Int, channels:Int, checkCancelled:()->Unit):PcmFile`; `TextAudioMuxer.mux(video:File, pcm:PcmFile, output:File, checkCancelled:()->Unit)`; `TextVideoExporter.export(context:Context,project:TextEditProject,output:File,checkCancelled:()->Unit,onProgress:(Int)->Unit)`.

- [x] Написать независимые проверки ориентации 90/270 и PCM-времени: 500 ms задержки дают 8000 mono-сэмплов тишины при 16 kHz; короткая дорожка не повторяется. Device-тест экспортирует цветовой видеофикстур с надписью и проверяет пиксели, аудио и длительность.
- [x] Запустить JVM/device-тесты; зафиксировать RED до реализации.
- [x] Реализовать общую Canvas-раскладку и дополнительную RGBA-текстуру поверх итогового GLES-кадра. Прямой граф из одного клипа 1× не использует монтажную приёмку рецептов.
- [x] Декодировать PCM потоково на диск по фактическим PTS; ограничить RAM буфером. Кодировать звук в AAC потоково; добавить тишину до длительности видео, без loop.
- [x] Проверить настоящий экспорт на эмуляторе, запустить JVM-серию; ожидается PASS. Коммит `feat(text): экспортировать текст с сохранением исходного звука`.

### Task 3: Настоящий локальный STT

**Files:** Create `SpeechTranscriber.kt`, `WhisperSpeechTranscriber.kt`, `WhisperNative.kt`, `app/src/main/cpp/CMakeLists.txt`, `whisper_jni.cpp`, `app/src/main/assets/licenses/whisper.txt`; modify `app/build.gradle.kts` (including verified model assets task); tests `SpeechWindowTest.kt`, `WhisperDeviceTest.kt`.

**Interfaces:** `SpeechTranscriber.transcribe(context:Context, source:File, language:String, checkCancelled:()->Unit,onProgress:(Int)->Unit):List<CaptionCue>`; native `open(modelPath:String):Long`, `recognize(handle:Long,samples:FloatArray,language:String,cancel:NativeCancellation):Array<String>`, `close(handle:Long)`.

- [x] Написать тесты границ окон и удаления дубликатов; device-тест: настоящий bundled base распознаёт короткую EN-фикстуру и не выдаёт фразу на тишине.
- [x] Запустить тесты и зафиксировать RED. Закрепить whisper.cpp commit `48f628a84833905ee4a0658ee6d4a5c915ce1997` и model revision/checksum после чтения метаданных поставщика.
- [x] Подключить CPU JNI, 16 KiB alignment, отмену и release в finally; модель в assets проверяется на сборке, при распаковке и перед каждым открытием кэша.
- [x] Читать PCM окнами до 30 с, переносить временные метки в абсолютные PTS, отбрасывать пустые/безречевые результаты и объединять перекрытия.
- [x] Собрать ARM64/ARMv7 и test x86_64, запустить device-тесты; ожидается реальное распознавание. Коммит `feat(subtitles): добавить локальное распознавание Whisper`.

### Task 4: Экран и подключение двух сценариев

**Files:** Create `TextEditActivity.kt`, `TextEditSession.kt`, `TextPreviewOverlay.kt`, `TextVideoProbe.kt`, `res/values/text_strings.xml`; modify `MainActivity.kt`, `activity_main.xml`, `AndroidManifest.xml`; test `TextEditActivityDeviceTest.kt`.

**Interfaces:** `TextEditActivity.open(context:Context, file:File?=null)`; `TextEditSession` owns import/STT/export jobs and observable state; Activity never owns worker resources. State contains project, busy/progress/error/result and cancellation.

- [x] Написать device-сценарий: вход через главное меню и готовый MP4; добавить заголовок и плашку; изменить оформление и время; восстановить черновик; отменить работу; экспортировать.
- [x] Запустить и зафиксировать RED. Реализовать предпросмотр с общей раскладкой, три раздела, seek к фразам, все настройки из спецификации и confirmation только для замены исправленного STT.
- [x] Добавить главный вход и кнопку готового результата; импорт независим от стиля. Восстанавливать сохранённый проект и прерванные задания, писать изменения атомарно.
- [x] Запустить device/JVM-проверки; ожидается PASS. Коммит `feat(ui): добавить редактор текста и автосубтитров`.

### Task 5: Полная проверка и PR

**Files:** Update spec status, README and this plan; create `docs/testing/text-subtitles.md` with actual evidence and limitations.

**Interfaces:** Final feature uses all contracts above. Publication uses `CompletedRenderStore`; previous source/result never overwritten.

- [x] Запустить `clean baselineVerify`, соответствующие UI/device-тесты и собрать APK всех объявленных ABI; ожидается PASS без ослабления gates.
- [x] Проверить MP4: читаемая кириллица, time boundaries, оригинальный звук, ориентация и длительность. Зафиксировать доступные устройства; не заявлять Samsung-проверку без устройства.
- [x] Провести свежий обзор всего изменения через отдельного reviewer согласно executing-plans; исправления подтверждать RED→GREEN.
- [x] Подготовить обновление PR 12 вокруг фактической реализации, результатов и оставшейся человеческой приёмки. Опубликовать итоговый пакет этого плана push без force; сохранить draft для аппаратной и визуальной приёмки.

## Итог выполнения

Проверенный код: `a2e36bd60f756cf374673562d8323d71698fd474`. Clean gate прошёл:
655 JVM, 255 Python, lint 0 ошибок / 26 предупреждений, APK трёх ABI. Все 12
уникальных feature-device сценариев прошли в сериях 8 export/STT + 4 UI;
производственный APK между сериями не менялся. После тестовой правки навигации
повторён baseline и собран test APK. Полная остановка процесса вручную проверила
восстановление исправленного черновика и результата. Точные границы этих проверок,
SHA-256 APK и открытая приёмка A25 приведены в [протоколе](../../testing/text-subtitles.md).
Результат удалённых CI последнего коммита фиксируется в PR; локальные результаты
не подменяют CI или одобрение слияния.

## Совместимые интеграционные расширения

10 октября координатор по делегированному полномочию владельца передал сверку
принятого интеграционного сценария. Базовый MP4 checkpoint `109b0c7a` сохраняется;
следующие изменения принадлежат этому же PR 12. Общая схема ревизий остаётся у
PR 15, второй STT и project store не создаются.

### Task 6: Модель, Unicode и review evidence

- [x] RED: MONO/accent/scale roundtrip, старые BOLD/SLIDE/v1 без новых полей;
  TITLE 180/181 Unicode code points (кириллица, emoji, combining), отличия
  TITLE/PLATE/SUBTITLE и неизвестная/низкая confidence.
- [x] Добавить совместимые enum/optional поля: MONO, ACCENT, SCALE, вид слоя,
  cue confidence/manual review. Новые заголовки ограничить 180 code points;
  старые длинные слои загружать как LEGACY без обрезания. Субтитры две строки,
  новые заголовки три. Evidence не присваивает измерения или калибровку.
- [x] GREEN JVM; scoped commit модели и сохранения `784d1b3`.

### Task 7: Общая safe-area раскладка и редактор

- [x] RED отсутствующих pure-geometry/motion API → GREEN. Device-тесты
  фактических границ 720p/1080p и квадратного видео добавлены; runtime RED
  этих Canvas-тестов не заявляется. Они проверяют одновременные слои и появление.
- [x] Геометрия x=.08.. .92/y=.10.. .85 включает подложку и анимацию.
  Упорядоченные слои размещать без пересечений; чрезмерную плотность явно
  показывать в preview и отклонять в экспорте, сохраняя черновик/MP4.
- [x] Подключить MONO/accent/slight scale и review evidence к существующему
  экрану, сохранив прежние BOLD/SLIDE и ручные исправления.
- [x] Проверить реальный layout/UI/export, свежий reviewer, новый clean gate;
  scoped commit, exact checked contract, push в PR 12.
  Код `cb64b287` проверен удалёнными baseline/UI: 662 JVM, 255 Python,
  81 Android (79 экранных + 2 permissions), включая 16 feature-сценариев.
  XML и SHA-256 скачанных CI-артефактов проверены; ошибок и пропусков нет.

### Task 8: Честный word-alignment контракт и оценка

- [x] Проверить закреплённый Whisper/JNI: token timestamps, DTW и probability
  semantics. Не делить фразы на равные интервалы и не объявлять token p
  откалиброванной уверенностью слова.
- [x] Подготовить конкретный outcome/alignment proposal и протокол RU corpus:
  лицензия/согласие, ручная разметка start/end, holdout, WER, coverage,
  отдельные median/p95 ошибок обеих границ <=200/500 ms.
- [x] Оставить word-quality gate открытым без настоящего alignment и измерений;
  сохранить draft. Последние CI фиксируются отдельно для exact head.

### Task 9: Raw token evidence и воспроизводимый валидатор

Продолжение по принятому координатором proposal: один существующий
SpeechTranscriber/JNI, без дополнительного recognizer или project store.

- [x] JVM RED отсутствующих API → GREEN: UTF-8 через границы BPE, UNKNOWN/zero
  timing, malformed wire, immutable raw evidence, повторы на перекрытиях,
  исходная причина однократной отмены. Семь новых тестов и связанные speech
  тесты прошли; промежуточные логи сохранены локально.
- [x] Добавить ASCII/hex JNI protocol с backend commit и SHA исходника bridge,
  raw сегментами/токенами, языком каждого окна и requested language. Производные
  слова маркировать TOKEN_HEURISTIC; confidence остаётся некалиброванной.
- [x] Провести независимое ревью, scoped commit `07ec407`; P3 phantom token
  воспроизведён regression RED 7/1 failure → GREEN 17/5 suites.
  ARM64 NDK syntax-only прошёл; это не native build.
- [x] Запустить JNI на реальной модели, коротком и последнем неполном окне.
  Baseline `a5310149` успешен: 917 JVM / 121 suites, 285 Python и native build
  трёх ABI без ошибок/пропусков. Android UI: 82 cases, 1 failure tick800>756
  в auto-RU MP4, errors/skips0; 30s/6s tail прошёл. Общий PASS не заявляется.
  Исправление bounded decode domain выполняется до sampling, со своим patch
  SHA в header v2; raw rows не обрезаются, strict parser не ослабляется.
  Проверки исправления обязательны для exact head и фиксируются в PR 12.
- [x] TDD валидатора: hashes/WAV/provenance, 8 pilot + 2 holdout speakers,
  две независимые разметки, WER/coverage и median/p95 обеих границ.
  Отдельные alignment/confidence gates, неизменяемые evidence/report files.
- [x] Сверить окончательный diff и независимое ревью валидатора; targeted
  30/30 и полная Python-серия 285/285 без failures/errors/skips. Подтверждённые
  P2 calibration gate/duplicate audio/hardlink overwrite воспроизведены до
  исправлений. Preregistration связывает source/protocol/Unicode до holdout.
  Инструменты и протокол сохраняются отдельным scoped commit.
- [x] Обновить контракт и PR; результаты каждого CI закреплять за его коммитом.
  Отсутствующие RU gold/holdout, acoustic alignment, A25 и человеческая
  приёмка остаются явно открытыми; структурные тесты не заменяют измерения.

### Согласование с обновлённой main

`5f836b6` объединяет main `a32a6a84` без переписывания истории. Сохранены
sourceAttachments и global project clock в renderer; временные дубликаты
текстовых типов убраны. Actual compile RED выявил redeclaration; после исправления
46 speech/frame/core/legacy-codec tests в 8 suites прошли за 2m23s.
Независимое review разрешения конфликтов не нашло P1/P2. Incoming integration
spec/plan сохранены отдельными `-integration` файлами, ссылки исправлены.
Rich-text Hybrid codec сохраняет пока только старые поля: его расширение и
gallery/source-audio handoff остаются отдельной открытой интеграцией владельцев.
