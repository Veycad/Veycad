# Пользовательский текст и локальные субтитры Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить редактируемые заголовки и русские автосубтитры, совпадающие со слышимой речью в экспортированном MP4, с полностью локальной обработкой.

**Architecture:** Использовать общие проекты, список источников и `MontageTimeMap` из G1–G3 плана галереи. Собрать исходный звук по окончательному графу, распознать его до добавления музыки, сохранить transcript и отдельный `TextTrack`. Один layout/sampler служит превью и GPU-экспорту; повторный экспорт использует сохранённый граф и ручные правки.

**Tech Stack:** Kotlin/JVM, Android API 26–36, MediaCodec/AAC, Canvas/Paint, GLES 2.0, Android Views, Vosk Android 0.3.75, JNA 5.18.1, `vosk-model-small-ru-0.22`, JUnit4/Espresso.

**Spec:** [2026-10-10-text-subtitles-design.md](../specs/2026-10-10-text-subtitles-design.md), подтверждена пользователем 2026-10-10.

## Global Constraints

- Всё локально; production APK содержит реальную модель, без загрузки на телефоне, аккаунта, `INTERNET`, `ACCESS_NETWORK_STATE` и разрешения микрофона для импортированного видео.
- Первый выпуск STT — русская речь; ручной текст Unicode, включая кириллицу/emoji. Перевод, diarization, custom fonts не добавляются.
- Заголовок по умолчанию `[0,3 s)`, до трёх строк и 180 Unicode code points; пустой текст убирает плашку.
- Три системных семейства sans/serif/mono, три размера; светлый/тёмный текст, фон none/dark/accent; появление none/fade/slight scale, 200 ms с ограничением длительностью надписи.
- Safe area в нормализованных координатах: x=0.08..0.92, y=0.10..0.85; заголовок выше, субтитры ниже, максимум две строки субтитров. FEAR квадратный проверяется отдельно.
- Интервалы полуоткрытые `[startUs,endUs)`, внутри результата, соседние cues не пересекаются.
- Режимы звука: «Музыка», «Исходный звук», «Речь и музыка». Включение субтитров явно выбирает слышимый исходный звук с музыкой.
- STT получает смонтированную исходную дорожку до музыки приложения: PCM 16 kHz, 16-bit mono; `setWords(true)`.
- Cues целевой длительности 1–4 s, максимум 6 s; короткое слово сохраняет реальный интервал без растягивания через тишину.
- Один загруженный экземпляр модели, ограниченные PCM/atlas buffers; отмена и ошибки закрывают ресурсы и удаляют временное аудио.
- Смена оформления/ручная правка не запускает STT; смена источников/графа/звука инвалидирует transcript. Предыдущий MP4 сохраняется.
- Медианная ошибка размеченных границ слов ≤200 ms, 95-й перцентиль ≤500 ms; длительности аудио/видео отличаются не больше одного кадра.
- Ветки `codex/*`, код и необходимые тесты в одном Conventional Commit, scoped staging; draft PR при неполной приёмке, ручное одобренное слияние.

## Review Focus

1. Начало аудио позже видео, пробел в дорожке, нет аудио в середине списка — тишина занимает своё время и не сдвигает следующие слова; тест T2/T9.
2. Музыка приложения содержит голос, в исходнике тишина — слова из добавленного трека не превращаются в субтитры; тест T4/T7.
3. Emoji/комбинируемые символы, длинное русское слово, FEAR title в центре — границы текста учитывают реальные glyph metrics и конфликт областей; тест T1/T5/T6.
4. Process death при STT или повторном экспорте, удалённые исходники, недоступная модель — прежний MP4 и ручные правки сохраняются, ошибка отличается от «Речь не найдена»; тест T3/T7/T8.
5. Быстрая смена цвета/шрифта и несколько taps «Экспорт» — один активный lease, без повторного STT и потери последней правки; тест T7/T8.

## Основа, зависимости и команды

База проектирования — `main=a0395e26b93463f91520181a2eeb09dfd05e78a3`.
Package/namespace `com.veycad.app`; существующие Kotlin-пути содержат
`com/example/autoedit`. Не переименовывать их в этой работе.

Перед реализацией прочитать spec и связанный план
`docs/superpowers/plans/2026-10-10-gallery-batch.md` из draft PR #13.
G1–G3 реализуются один раз в `codex/gallery-foundation`: `MediaSourceSet`,
`EditProjectStore`, `RenderProjectSnapshot`, `MontageTimeMap`, N-source Request.
Текст не зависит от завершения нового рецепта G4–G8: работает с текущими
одним/двумя источниками и с новым набором через общий контракт.

Создать `codex/text-subtitles` от проверенной основы; если foundation не слита,
указать зависимость и base в stacked PR. Не копировать второй project store
или собственную формулу speed ramp. Ветки draft #13/#14 остаются ветками
документации, продуктовый код готовится отдельно. Метод исполнения не выбран.

Команды — из корня worktree в PowerShell. JVM unit tests используют debug,
instrumentation — изолированный `.uitest` на выделенном `AutoEditUi_API36`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PYTHON = 'C:\Users\rexar\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.TextTrackTest'
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.TextOverlayIntegrationTest
```

На этой машине `python` в PATH — Windows Store alias; указанный Python
3.12.14 проверен. На другой машине задать свой рабочий runtime через `PYTHON`
или Gradle `-PpythonExecutable`; SDK/local setup — по `WINDOWS.md`.

Перед каждым commit: добавить только Files задачи, просмотреть
`git diff --cached`, выполнить `git diff --cached --check`. В red run новый
отсутствующий тип может давать compile failure; после реализации ожидается
PASS assertions, а не просто компиляция. Ниже примеры обязательных assertions,
остальной fixture код пишет исполнитель. Runtime проверки в этом документе
запланированы, пока не выполнены.

## Карта компонентов

| Компонент | Ответственность и файлы |
| --- | --- |
| Данные текста | `TextTrack.kt`, `TextTrackCodec.kt`: типы, интервалы и schema JSON. |
| Звук | `SourceAudioComposer.kt`, `PcmStream.kt`, `AudioMixSettings.kt`: единый source PTS, gaps/speed/repeats, PCM и AAC. |
| Offline STT | `VoskModelStore.kt`, `LocalSpeechTranscriber.kt`, `VoskSpeechTranscriber.kt`, build task/lock: упакованная модель и streaming words. |
| Фразы/геометрия | `CaptionSegmenter.kt`, `TextLayout.kt`: pause grouping и измерения шрифтов для всех потребителей. |
| Экспорт | `TextOverlayRenderer.kt`, renderer: bounded атлас и текст после эффектов. |
| Проекты/UI | `ProjectTextState.kt`, `TextRenderCoordinator.kt`, `TextEditorPanel.kt`: cache keys, persistence, ручные правки и re-export. |

### T1: Типы текста, оформление и валидация

**Files:** Create `app/src/main/java/com/example/autoedit/{TextTrack,TextTrackCodec,AudioMixSettings}.kt`; tests `app/src/test/java/com/example/autoedit/{TextTrackTest,TextTrackCodecTest,AudioMixSettingsTest}.kt`.

**Interfaces:** `TextTrack(schemaVersion:Int=1,overlays:List<TextOverlay>,captions:List<CaptionCue>)`; `TextOverlay(id:String,text:String,startUs:Long,endUs:Long,anchor:TextAnchor,style:TextStyle,animation:TextAnimation)`; `CaptionWord(text:String,startUs:Long,endUs:Long,confidence:Float)`; `CaptionCue(id:String,text:String,startUs:Long,endUs:Long,needsReview:Boolean,style:TextStyle)`. `TextAnchor.TITLE/CAPTION`; `TextAnimation.NONE/FADE/SCALE`; `TextStyle(family:TextFamily,size:TextSize,color:TextColor,plate:TextPlate)`, enums `SANS/SERIF/MONO`, `SMALL/MEDIUM/LARGE`, `LIGHT/DARK`, `NONE/DARK/ACCENT`. `TextTrack.validate(durationUs:Long):Unit`; `TextTrackCodec.write(track:TextTrack,file:File):Unit`, `read(file:File):TextTrack`. `AudioMode.MUSIC/SOURCE/SPEECH_AND_MUSIC`; `AudioMixSettings(mode:AudioMode,sourceGain:Float=1f,musicGain:Float=0.25f)`, `withCaptionsEnabled():AudioMixSettings` сохраняет SOURCE или явно переключает MUSIC в SPEECH_AND_MUSIC.

- [ ] **1. Написать `TextTrackTest.titleUsesCodePointsAndHalfOpenInterval`** и соседние tests: 180/181 code points, emoji, пустой заголовок, три/четыре строки, неправильные/пересекающиеся cues, round-trip schema, конец короткого результата. Кодек отклоняет неверный schema/oversize JSON; UI и persistence используют одну validation.

```kotlin
assertEquals(180, validTitle.codePointCount(0, validTitle.length))
track.validate(30_000_000L)
assertThrows(IllegalArgumentException::class.java) { overlappingCues.validate(30_000_000L) }
assertEquals(AudioMode.SPEECH_AND_MUSIC, musicOnly.withCaptionsEnabled().mode)
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.TextTrackTest' --tests 'com.veycad.app.TextTrackCodecTest' --tests 'com.veycad.app.AudioMixSettingsTest'`; ожидается FAIL новых типов/assertions.
- [ ] **3. Реализовать interfaces:** JSON schema 1, finite gains, sorted cues, минимум один code point для непустой cue; title default end `min(3_000_000,durationUs)`. Validate полагается на code points, layout T5 — на реальную ширину. Не смешивать пользовательский текст с `AuthoredTitleProfile`.
- [ ] **4. Green run:** повторить команду; PASS, migration отсутствующего text state даёт пустую дорожку и прежний режим MUSIC.
- [ ] **5. Commit:** `feat(text): добавить дорожку текста и правила оформления`.

### T2: Потоковый исходный звук по времени графа

**Files:** Create `app/src/main/java/com/example/autoedit/{PcmStream,SourceAudioComposer,AudioMixProcessor}.kt`; modify `MediaCodecAudioDecoder.kt`, `AacEncoderMuxer.kt`, `AudioExportPlan.kt`, `VeycadAutomaticEditor.kt` рядом; tests `app/src/test/java/com/example/autoedit/{SourceAudioComposerTest,AudioMixProcessorTest}.kt`; create `app/src/androidTest/java/com/example/autoedit/SourceAudioIntegrationTest.kt`.

**Interfaces:** Consumes G3 `MontageTimeMap.sample(outputTimeUs):SourceTime`. `PcmFormat(sampleRate:Int,channels:Int)`, PCM signed 16-bit LE; `PcmFile(file:File,format:PcmFormat,frames:Long)`, `PcmChunk(ptsUs:Long,format:PcmFormat,samples:FloatArray)`. В `PcmStream.kt` определить `PcmDecoder.decodeChunks(file:File,startUs:Long,endUs:Long,checkCancelled:()->Unit,onChunk:(PcmChunk)->Unit):Unit`; `MediaCodecAudioDecoder` реализует его, сохраняя прежний API. `SourceAudioComposer(decoder:PcmDecoder).compose(sources:MediaSourceSet,timeMap:MontageTimeMap,target:File,format:PcmFormat,checkCancelled:()->Unit):PcmFile`. Там же `PcmResampler.convert(input:PcmFile,target:File,format:PcmFormat,checkCancelled:()->Unit):PcmFile`. `AudioMixProcessor.mix(source:PcmFile,music:PcmFile?,settings:AudioMixSettings,target:File,checkCancelled:()->Unit):PcmFile`. `AacEncoderMuxer.muxPcmFile(pcm:PcmFile,videoFile:File,outputFile:File,durationUs:Long,checkCancelled:()->Unit):Unit`.

- [ ] **1. Написать `SourceAudioComposerTest.preservesLateAudioPtsAndSilentGap`** и tests cuts/repeated clips/variable speed/EOF/20 files. Использовать детерминированный fake PCM chunk decoder с синусоидальными/impulse markers; instrumented test проверяет настоящий AAC decode, не только fake.

```kotlin
assertEquals(480_000L, composed.frames) // 30 s при 16 kHz
assertTrue(samplesBeforeLateTrack.all { it == 0.toShort() })
assertEquals(expectedSourceMarker, markerAtCut)
assertTrue(mixedPeak <= 1f)
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.SourceAudioComposerTest' --tests 'com.veycad.app.AudioMixProcessorTest'`; targeted `SourceAudioIntegrationTest`; FAIL отсутствующего streaming/time mapping.
- [ ] **3. Реализовать:** sequential decode по реальным audio PTS, буфер ограничен 1 s, последние сэмплы вне источника — тишина. Target clock считает frames, не callbacks; `MontageTimeMap` задаёт primary source, включая ramp/repeats. Для PCM использовать anchors из map каждые 1 ms, дополнительно на каждом video frame PTS и границе clip; между ними интерполировать внутри одного источника, без второго speed integral на каждом 48 kHz sample. Test сверяет anchors с video PTS и resampling с map oracle. Линейный resampling без обещания pitch preservation; 10 ms fade-out/in у смены primary source без наложения разных реплик. Mix SOURCE/MUSIC/SPEECH_AND_MUSIC применяет headroom/limiter, music loops по прежнему `AudioExportPlan`. PCM 48 kHz stereo для export, 16 kHz mono до музыки для STT; второй файл получается resampling source PCM, не второго расчёта графа. Старый music-only route сохраняется при выключенном новом тексте/звуке.
- [ ] **4. Green run:** команды выше + `AudioExportHeadroomTest`, `DecodedAudioQualityTest`, `ExportContainerIntegrityTest`; PASS. Выборки слов/markers совпадают с source PTS кадров; AAC и видео имеют один нулевой output clock.
- [ ] **5. Commit:** `feat(audio): сохранить исходный звук по графу монтажа`.

### T3: Реальная модель в APK и воспроизводимая упаковка

**Files:** Create `tools/prepare_speech_model.py`, `tools/test_prepare_speech_model.py`, `config/speech-model.lock.json`, `docs/licenses/vosk-model-small-ru-0.22.txt`, `app/src/main/java/com/example/autoedit/VoskModelStore.kt`; modify `app/build.gradle.kts`, `.gitignore`, `app/src/main/res/values/strings.xml`; create `app/src/androidTest/java/com/example/autoedit/VoskModelPackagingTest.kt`. Existing security gate — task `verifySecurityContract` в `app/build.gradle.kts`; Python discovery `tools/run_tests.py` уже включает `tools/test_*.py`.

**Interfaces:** `prepare_speech_model.py --lock <path> --cache <path> --output <generated-assets-root>` возвращает exit 0 только при совпадении source URL/size/SHA-256, безопасной распаковке и наличии `am/final.mdl`, `conf/model.conf`, `graph`. Lock schema 1: `modelId`, `version`, `sourceUrl`, `archiveBytes`, `sha256`, `license`. Gradle task `prepareSpeechModel` → `build/generated/speechAssets/speech/ru`, зарегистрирован как generated assets всех выпускаемых вариантов. `VoskModelStore.open(checkCancelled:()->Unit):org.vosk.Model` копирует assets в версионированный app-private каталог атомарно; не вызывает сеть.

- [ ] **1. Написать `test_rejects_checksum_mismatch_and_zip_traversal`** на малом fake archive; `VoskModelPackagingTest.realBundledModelOpensWithoutNetwork` проверяет именно assets выпускаемого варианта, missing/corrupt assets дают явную ошибку. Запрещён pass по mock или пустой модели.

```python
assert prepare(valid_lock, valid_archive, output).exit_code == 0
assert prepare(wrong_sha_lock, valid_archive, output).exit_code != 0
assert not (parent / "escaped-file").exists()
```

- [ ] **2. Red run:** `& $env:PYTHON -m unittest discover -s tools -p 'test_prepare_speech_model.py'`; `.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.VoskModelPackagingTest`; ожидается FAIL.
- [ ] **3. Реализовать:** скачать официальный `https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip` при подготовке сборки, вычислить и записать реальный checksum/размер до первого commit модели. Не оставлять placeholder SHA. Проверять hash каждой сборкой, использовать проверенный offline build cache; на устройстве только assets. Зависимости ровно `com.alphacephei:vosk-android:0.3.75@aar`, `net.java.dev.jna:jna:5.18.1@aar`; добавить реальные license notices, archive/generated model не коммитить. Проверить ARM64/ARMv7, 16 KB page-size совместимость native libraries в APK; отсутствие compatible artifact означает открытый blocker выпуска, а не выключенный тест. Manifest сохраняет прежний security contract.
- [ ] **4. Green run:** повторить Python/packaging tests; `.\gradlew.bat :app:verifySecurityContract :app:assembleDebug :app:assembleRelease`; PASS с настоящей моделью. Проверить universal и split APK, checksum assets, наличие лицензии; release artifact может быть unsigned для проверки содержимого. В PR записать фактический рост размера APK и native compatibility; не заимствовать RAM/size модели как замер приложения.
- [ ] **5. Commit:** `build(speech): включить проверенную русскую модель Vosk` вместе с loader и тестами, без binary payload в Git.

### T4: Потоковое распознавание и состояния результата

**Files:** Create `app/src/main/java/com/example/autoedit/{LocalSpeechTranscriber,VoskSpeechTranscriber}.kt`; tests `app/src/test/java/com/example/autoedit/SpeechTranscriptTest.kt`, `app/src/androidTest/java/com/example/autoedit/LocalSpeechIntegrationTest.kt`; create `app/src/androidTest/assets/speech/ru-fixture-manifest.json` и малые разрешённые fixtures с ручной разметкой/лицензией, без пользовательских видео.

**Interfaces:** `LocalSpeechTranscriber.transcribe(pcm:PcmFile,checkCancelled:()->Unit):SpeechResult`. `SpeechResult.Success(words:List<CaptionWord>,modelVersion:String)`, `NoSpeech`, `Failure(code:SpeechError,message:String)`; cancellation не превращается в Failure/NoSpeech. `SpeechError.MODEL_UNAVAILABLE/INVALID_AUDIO/RECOGNITION_FAILED`. `VoskSpeechTranscriber(modelStore:VoskModelStore):LocalSpeechTranscriber`. Input строго 16 kHz mono PCM; confidence ограничена `[0,1]`, timestamps внутри PCM duration.

- [ ] **1. Написать `SpeechTranscriptTest.failureIsDistinctFromNoSpeech`**, tests parsing final JSON, word boundary rounding/clamping, silence, cancel close; интеграцию настоящей русской фразы, шумной речи и source music. Fixture manifest хранит ожидаемые слова/границы и право использования, score измеряет WER и ошибки boundaries.

```kotlin
assertTrue(silenceResult is SpeechResult.NoSpeech)
assertTrue(missingModelResult is SpeechResult.Failure)
assertTrue(realWords.all { it.startUs >= 0 && it.endUs <= pcmDurationUs })
assertTrue(realWords.any { it.text == expectedMarkerWord })
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.SpeechTranscriptTest'`; `.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.LocalSpeechIntegrationTest`; FAIL без провайдера/реальной модели. Точность проверяется размеченными fixtures, не exact-match только одного удачного образца.
- [ ] **3. Реализовать:** один worker/Model, `Recognizer(model,16000f).setWords(true)`, небольшие чанки и объединение accepted/final results без дублей. Проверять отмену между чтением/acceptWaveForm; recognizer/model всегда закрываются. Низкую confidence сохранить для T5, пустую речь не выдумывать. Дополнительная музыка приложения не поступает в transcriber ни в одном route.
- [ ] **4. Green run:** те же JVM/instrumented tests; PASS состояния и markers. Сохранить измеренные WER/границы каждого sample, не считать измерение эмулятора телефонной приёмкой. Offline handset проверяется T9.
- [ ] **5. Commit:** `feat(speech): распознавать русскую речь локально с временными метками`.

### T5: Группировка слов и общая геометрия текста

**Files:** Create `app/src/main/java/com/example/autoedit/{CaptionSegmenter,TextLayout}.kt`; tests `app/src/test/java/com/example/autoedit/CaptionSegmenterTest.kt`, `app/src/androidTest/java/com/example/autoedit/TextLayoutIntegrationTest.kt`.

**Interfaces:** `CaptionSegmenter.segment(words:List<CaptionWord>,durationUs:Long,measure:(String)->TextBounds,style:TextStyle):List<CaptionCue>`. `TextBounds(widthPx:Float,heightPx:Float,lineCount:Int)`; `TextLayout.layout(text:String,style:TextStyle,anchor:TextAnchor,width:Int,height:Int,reserved:List<RectF>):TextLayoutResult`, `TextLayoutResult(lines:List<String>,bounds:RectF,textSizePx:Float)`. `measure(text:String,style:TextStyle,width:Int,height:Int,anchor:TextAnchor):TextBounds` использует тот же line wrapping. `reserved` — активные области фиксированных recipe titles. Sizes как старт: SMALL/MEDIUM/LARGE = 0.032/0.040/0.048 выходной ширины; Canvas glyph measurements определяют fit.

- [ ] **1. Написать `CaptionSegmenterTest.doesNotExtendCueAcrossSilence`**, границы 1/4/6 s, short word, >2 lines, low confidence, empty speech; layout integration: кириллица/emoji/длинное слово, три family/size, 720/1080 и square FEAR, reserved title collision.

```kotlin
assertTrue(cues.all { it.endUs - it.startUs <= 6_000_000L })
assertTrue(cues.zipWithNext().all { (a, b) -> a.endUs <= b.startUs })
assertEquals(singleWord.endUs, singleCue.endUs)
assertTrue(layout.bounds.left >= width * .08f && layout.bounds.bottom <= height * .85f)
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.CaptionSegmenterTest'`; `.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.TextLayoutIntegrationTest`; FAIL.
- [ ] **3. Реализовать:** grouping по pause ≥350 ms и measured width, стремиться к 1–4 s, hard cap 6 s; не разрывать code point/grapheme. Short cue не удлинять искусственно; слово confidence <0.75 помечает `needsReview`. Layout fit в safe area с уменьшением до SMALL и переносами; максимум TITLE=3 строки, CAPTION=2. Overflow/невозможное размещение даёт validation error перед экспортом, не обрезанную строку. TITLE размещается в верхней свободной области, CAPTION — снизу; recipe reserved region рассчитывается из `AuthoredTitleProfile` в output coordinates. Emoji fallback system font сохраняется.
- [ ] **4. Green run:** tests выше; PASS всех размеров/аспектов и reserved regions. Проверить bitmap bounds с реальными Android font metrics, не JVM approximation.
- [ ] **5. Commit:** `feat(text): группировать субтитры и рассчитывать читаемые плашки`.

### T6: GPU-текст после эффектов и одинаковое превью

**Files:** Create `app/src/main/java/com/example/autoedit/{TextOverlayRenderer,TextPreviewView}.kt`; modify `MediaCodecSpeedRampRenderer.kt` и `AuthoredTitleProfile.kt` только для общего доступа к reserved bounds; create `app/src/androidTest/java/com/example/autoedit/TextOverlayIntegrationTest.kt`; extend `GlProgramLifecycleTest.kt` рядом.

**Interfaces:** `TextOverlayRenderer.prepare(track:TextTrack,width:Int,height:Int,graph:MontageGraph):Unit`, `draw(outputTimeUs:Long):Unit`, `close():Unit`. `TextOverlaySample(layout:TextLayoutResult,opacity:Float,scale:Float)`; `TextOverlaySampler.sample(startUs:Long,endUs:Long,animation:TextAnimation,layout:TextLayoutResult,outputTimeUs:Long):TextOverlaySample?` в том же файле. `TextPreviewView.bind(track:TextTrack,graph:MontageGraph):Unit`, `setOutputTimeUs(timeUs:Long):Unit` использует T5 layout и тот же sampler. Renderer Request получает `textTrack:TextTrack=TextTrack(overlays=emptyList(),captions=emptyList())`.

- [ ] **1. Написать `TextOverlayIntegrationTest.previewAndExportUseSameBoundaryAndBounds`**: frame before/at start/end, 200 ms fade/scale, cue <200 ms, кириллица/плашка, FEAR flash/fixed title coexistence, long transcript; отсутствие утечки GL textures после cancel/error.

```kotlin
assertNull(sampleAt(endUs))
assertEquals(previewSample.layout.bounds, exportSample.layout.bounds)
assertEquals(1f, sampleAt(startUs + 200_000L)!!.opacity, .001f)
assertTrue(peakAtlasBytes <= 16 * 1024 * 1024)
```

- [ ] **2. Red run:** `.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.TextOverlayIntegrationTest`; FAIL отсутствующего user-text stage/разных координат. Sample test fixtures используют реальный GL export и decodeOutput frame, не только screenshot UI.
- [ ] **3. Реализовать:** bounded LRU atlas активных/ближайших cues, максимум 16 MiB, evict/deleteTextures, не pre-rasterize весь transcript. Canvas/Paint сохраняет все line glyphs. Draw после color/strobe/visual transforms в output coordinates; текущие authored titles остаются по своему сценарию. Sampler применяет fade/scale только при `[start,end)`, длина появления `min(200_000,intervalUs)`, scale 0.96→1.0; plate и text имеют общую opacity. Preview video со звуком + TextPreviewView используют один output clock.
- [ ] **4. Green run:** новая integration class и `GlProgramLifecycleTest`; PASS. Полный декодированный export показывает текст с теми же bounds/таймингом; визуально проверить readability T9.
- [ ] **5. Commit:** `feat(render): экспортировать пользовательский текст и субтитры`.

### T7: Persistence, STT cache и повторный экспорт

**Files:** Create `app/src/main/java/com/example/autoedit/{ProjectTextState,TextRenderCoordinator}.kt`; modify `EditProjectStore.kt`, `RenderProjectSnapshot.kt`, `VeycadAutomaticEditor.kt`, `EditSession.kt`, `CompletedRenderStore.kt`; tests `app/src/test/java/com/example/autoedit/{ProjectTextStateTest,TextRenderCoordinatorTest}.kt`, `CompletedRenderStoreTest.kt`.

**Interfaces:** `ProjectTextState(track:TextTrack,audio:AudioMixSettings,transcriptKey:String?,modelVersion:String?,words:List<CaptionWord>)`; `TranscriptKey.compute(sources:MediaSourceSet,graph:MontageGraph,audio:AudioMixSettings,modelVersion:String):String` в том же файле, canonical serialization + SHA-256. `EditProjectStore.saveTextState(projectId:String,state:ProjectTextState):Unit`, `loadTextState(projectId:String):ProjectTextState?`. Snapshot получает `textState:ProjectTextState?=null` с обратной совместимостью. `TextRenderCoordinator.render(snapshot:RenderProjectSnapshot,state:ProjectTextState,captionsEnabled:Boolean,checkCancelled:()->Unit):File` и `reexport(snapshot:RenderProjectSnapshot,state:ProjectTextState,checkCancelled:()->Unit):File` вызываются единственным EditSession worker/lease; result публикуется через существующий CompletedRenderStore. `SpeechRecognitionException(failure:SpeechResult.Failure):Exception` сохраняет различимый код для EditSession; NoSpeech — пустые captions и отдельное сообщение, не exception.

- [ ] **1. Написать `TextRenderCoordinatorTest.styleEditReusesAnalysisAndTranscript`** и tests graph/source/audio/model invalidation, process death, ошибка STT, disk full, duplicate taps, удалённые source assets. Fake dependencies считают вызовы, instrumented end-to-end T8/T9 проверяет настоящий pipeline.

```kotlin
assertEquals(1, transcriber.callCount) // render → смена шрифта → reexport
assertEquals(1, sourceAnalyzer.callCount)
assertNotEquals(oldKey, keyAfterGraphChange)
assertTrue(previousMp4.isFile)
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.ProjectTextStateTest' --tests 'com.veycad.app.TextRenderCoordinatorTest'`; FAIL новых persistence/cache assertions.
- [ ] **3. Реализовать:** transactional project state + snapshot, ключ включает ordered fingerprints, все time-map graph fields, audio settings и model version. Хранить raw words отдельно от вручную исправленных cues; style/text edits меняют track без STT. Источник/граф/звук меняют key и явно инвалидируют transcript; никакой stale transcript на новом эдите. Render: compose source → STT/segment → сохранить state → mix/AAC + video/text → acceptance → atomic publish. Reexport использует прежний graph/analysis и исправленный track. Temp source PCM удаляется в finally; model/lease/wake-lock закрываются. Failure сохраняет draft для retry/export без captions; новый partial MP4 не заменяет прежний. Не предлагать редактирование burned captions у старого MP4 без snapshot.
- [ ] **4. Green run:** новые tests + `CompletedRenderStoreTest`, `RenderWorkspaceTest`, foundation snapshot tests; PASS восстановления и cache counters. Подтвердить сохранность CaptureLink при каждом publication.
- [ ] **5. Commit:** `feat(storage): сохранять текст и повторно экспортировать монтаж`.

### T8: Карточка текста, исправление фраз и состояния UI

**Files:** Create `app/src/main/java/com/example/autoedit/TextEditorPanel.kt`, `app/src/main/res/layout/view_text_editor.xml`; modify `MainActivity.kt`, `EditSession.kt`, `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/values/strings.xml`, `app/src/uiTest/java/com/example/autoedit/UiFixtureProvider.kt`; create `app/src/androidTest/java/com/example/autoedit/TextEditorScreenTest.kt`; modify `ResultScreenTest.kt` и `DirectorScreenTest.kt` рядом.

**Interfaces:** `TextEditorPanel.bind(track:TextTrack,audio:AudioMixSettings,durationUs:Long,busy:Boolean,error:String?):Unit`, callbacks `onTrackChanged:(TextTrack)->Unit`, `onAudioChanged:(AudioMixSettings)->Unit`, `onCaptionsEnabled:(Boolean)->Unit`, `onReexport:()->Unit`. `EditSession.updateText(track:TextTrack):Unit`, `setAudioMix(settings:AudioMixSettings):Unit`, `setAutoCaptionsEnabled(enabled:Boolean):Unit`, `reexportText(projectId:String):Unit`. Состояния `PREPARING_AUDIO/TRANSCRIBING/RENDERING` и error/noSpeech доступны UI отдельно.

- [ ] **1. Написать `TextEditorScreenTest.correctCueAndReexportWithoutNewRecognition`** плюс tests title, fonts/plate/animation, interval validation, переключение слышимого звука, cancel, model failure vs noSpeech, поворот/restart, два taps и удалённые originals. Для UI fixtures fake transcript допустим, реальность STT проверяется T4/T9.

```kotlin
onView(withText("Текст и субтитры")).check(matches(isDisplayed()))
assertEquals(correctedText, store.loadTextState(projectId)!!.track.captions.first().text)
assertEquals(1, fixtureTranscriber.callCount)
assertTrue(previousResultFile.isFile)
```

- [ ] **2. Red run:** `.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.TextEditorScreenTest` на `.uitest`; FAIL отсутствующего editor/callback. Интервалы вводятся в выходном времени, не source-time.
- [ ] **3. Реализовать:** карточка «Текст» на Create, редактируемый список phrases на Result, три presets family/size/colors/background/animation, title default 0–3 s. Валидировать до export; низкую confidence отмечать, текст ошибок понятен пользователю. При включении captions показать выбранный «Речь и музыка» и доступный SOURCE; captions с MUSIC-only не экспортируются. `TextPreviewView` слушает тот же preview clock. Сохранение осуществляется EditSession/store, Activity не владеет распознаванием. Для missing originals объяснить недоступность re-export, прежний MP4 остаётся в library.
- [ ] **4. Green run:** `TextEditorScreenTest`, `ResultScreenTest`, `DirectorScreenTest`, gallery/capture screen regressions; PASS. Проверить экран в существующем визуальном языке `docs/design/approved/`, длинные русские labels и scroll/keyboard.
- [ ] **5. Commit:** `feat(ui): редактировать заголовки и автоматические субтитры`.

### T9: Реальный экспорт, offline телефон и финальный gate

**Files:** Create `app/src/androidTest/java/com/example/autoedit/TextSubtitleExportIntegrationTest.kt`, `docs/testing/text-subtitles-device-review.md`; extend `SyntheticVideo.kt` рядом для audio markers; modify `docs/testing/ui-tests.md`, `docs/SupportMatrix.md`, `README.md` только подтверждёнными возможностями.

**Interfaces:** device review report фиксирует SHA/APK/model hash, device/API/ABI/page size, permissions/offline condition, fixture license и annotation method, WER, median/p95 start/end error, STT/export wall time и peak RAM, cancel latency, полный MP4 audio/video durations, preview/export samples и статус человеческой приёмки.

- [ ] **1. Написать `TextSubtitleExportIntegrationTest.wordsMatchAudiblePrimarySourceAcrossCuts`**: ≥3 разных источников, offset audio PTS, silence, repeats/speed ramp, название и recipe title вместе, source silence + voiced app music. Сверять decoded audio/video markers/текст с разметкой, не только cues JSON; corrupted/missing source даёт checkpoint без публикации плохого MP4.

```kotlin
assertTrue(abs(decodedVideoDurationUs - decodedAudioDurationUs) <= 1_000_000L / fps)
assertTrue(cues.none { it.text.contains(appMusicOnlyWord) })
assertTrue(wordBoundaryMedianErrorMs <= 200)
assertTrue(wordBoundaryP95ErrorMs <= 500)
```

- [ ] **2. Red run:** `.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.TextSubtitleExportIntegrationTest`; FAIL конкретного несоответствия, scoped fix, затем green. Выполнить `.\gradlew.bat clean baselineVerify` и `pwsh tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`; PASS всех discovered tests без исключения suites. Security и model packaging входят в gate.
- [ ] **3. Физический Samsung A25 в авиарежиме:** отдельная `.uitest` установка без сброса production-данных; русский 30/60 s, настоящая модель, полный re-export после ручной правки, 720/1080/квадратный FEAR. Измерить WER, median/p95 границ по фиксированной разметке, время/RAM, cancel и process interruption. Проверить ABI/native compatibility на заявляемых устройствах; один A25 не подтверждает все ABI.
- [ ] **4. Просмотр человеком со звуком 1×:** речь совпадает с текстом, плашки читаются, не пересекают authored titles и UI платформы, emoji/кириллица не пропали. Отдельно подтвердить качество распознавания и читаемость. Недоступный телефон/разметка/непройденный порог остаются явно незакрытой приёмкой в draft PR.
- [ ] **5. Commit изменившихся tests/docs:** `test(text): проверить локальные субтитры в полном экспорте`; если обнаружен дефект продукта, его код и regression test оформить отдельным `fix(text): ...` до итогового документа. Publish PR с actual last-SHA checks, измерениями и limitations. Без новых изменений пустой commit не создавать. Владелец вручную решает о слиянии после проверки.

## Карта покрытия спецификации

| Требование | Задачи |
| --- | --- |
| Заголовок, Unicode, оформление/анимация, safe area | T1, T5, T6, T8 |
| Слышимый звук, offsets/speed/cuts/silence, limiter/AAC | T2, T7, T9 |
| Реальная bundled модель, lock/license/ABI, offline STT | T3, T4, T9 |
| Word times/confidence, silence/error, фразы/ручная правка | T4, T5, T8 |
| Durable snapshot, invalidation, repeat export, старый MP4 | G1–G3, T7, T8 |
| Bounded memory, cancel, recovery, no microphone/network | T2, T3, T4, T6, T7, T9 |
| Baseline/UI, decoded MP4, Samsung/человеческая приёмка | T6, T9 |

## Передача в исполнение

План подготовлен для ревью; шаги ещё не выполнены. Рекомендуемый метод —
Native: общий PTS/audio/project контракт требует последовательного внедрения
G1–G3 → T1–T9. Для всей работы удобно завершить галерею G4–G8 после foundation,
затем текст; это даёт отдельный проверяемый продуктовый PR каждой функции.
Subagent-driven допускается после выбора пользователем с ревью каждой задачи.
До подтверждения плана и метода продуктовый код не изменять.
