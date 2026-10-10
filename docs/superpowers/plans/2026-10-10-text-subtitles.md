# Текст и локальные субтитры: план общей интеграции

> Исполнение: superpowers:subagent-driven-development, отдельный исполнитель и ревью каждой связной части. Изменение от 2026-10-10 учитывает согласование координатора по делегированному пользователем полномочию. Повторное подтверждение обычной интеграции не требуется.

**Цель:** сохранить весь одобренный сценарий редактируемого текста и локальных русских субтитров, включая слышимую исходную речь после монтажа, без конкурирующих хранилищ, редакторов и STT.

**Спецификация:** [2026-10-10-text-subtitles-design.md](../specs/2026-10-10-text-subtitles-design.md). Продуктовые требования сохранены. Выбор Vosk и независимых TextTrack/EditProjectStore из первоначальной версии плана заменён общей реализацией ниже. Это архитектурное согласование, а не подтверждение качества Whisper.

## Владельцы и зависимости

- PR #15, `codex/hybrid-mode-design`, чат «Добавить экспорт и шеринг»: единственные HybridProject, ProjectAsset, ревизии, ProjectClock 30/60 fps, SourceTimeMap, долговечные assets и общий project store. Закоммиченная основа при согласовании: `74b58038abedc918c0877cfa3cec215a0f4d9a9e`. Store ещё реализуется; использовать закоммиченный проверенный контракт, не копировать незакоммиченные TODO.
- PR #12, `codex/text-subtitles`, чат «Добавить субтитры и текстовые плашки»: TextLayer, CaptionCue, редактор, layout/preview/export и Whisper за SpeechTranscriber. Общий интерфейс должен принимать подготовленную дорожку исходной речи, не требуя фиктивного видео для PCM.
- PR #18, музыкальный коммит `fc21f629`: сверить MediaCodecAudioDecoder/AacEncoderMuxer, encoder delay и MP3 offset перед подключением смешивания; не откатывать эти исправления.
- Этот план и план галереи #13: мультивыбор и импорт, анализ/режиссура, список источников, адаптеры source audio/time map/mix, связь текста с общим проектом, интеграционные проверки. Второй Vosk-модуль и второй текстовый редактор не создаются.
- Изменения публикуются в отдельных `codex/*` ветках и stacked PR с явно указанной базой. Не сливать зависимости ради продолжения без явного одобрения владельца.

## Сохраняемые требования

- Локальная обработка без аккаунта, runtime-загрузки модели и передачи кадров/аудио. Production APK содержит настоящую модель; для импортированного видео не требуется микрофон или новое сетевое разрешение.
- Обязательна русская речь. Ручной текст Unicode с кириллицей, emoji и комбинируемыми символами. Перевод и diarization вне объёма.
- Заголовок по умолчанию `[0,min(3 s,duration))`, до трёх строк и 180 code points. Пустой текст убирает надпись.
- Sans/serif/mono, три размера, светлый/тёмный текст, фон none/dark/accent, появление none/fade/slight scale до 200 ms с ограничением интервалом надписи. Различия текущих enum модуля #12 закрыть там совместимым расширением, не вторым набором классов.
- Safe area x=0.08..0.92, y=0.10..0.85; заголовок выше, субтитры ниже, максимум две строки субтитров. Отдельно проверить FEAR квадратный и существующие авторские надписи.
- Полуоткрытые интервалы `[startUs,endUs)` в пределах результата; соседние cues не пересекаются.
- Режимы «Музыка», «Исходный звук», «Речь и музыка». Включение субтитров явно выбирает слышимый исходный звук с музыкой; тихие субтитры на музыке без речи не добавляются молча.
- Два различных входа STT сохраняются: слышимый звук готового MP4 и исходная речь, собранная по окончательному монтажу до добавления музыки приложения. Музыка приложения с вокалом не создаёт слова для второго сценария.
- STT вход — PCM 16 kHz, signed 16-bit mono, точные интервалы. Единственная модель в памяти; decode PCM buffer максимум одна секунда, отмена закрывает ресурсы и удаляет временное аудио. Peak-buffer test проверяет этот предел на длинном наборе источников.
- Cues обычно 1–4 s, максимум 6 s; паузы от 350 ms разделяют фразы. Короткое слово не растягивается через тишину; confidence ниже 0.75 помечает needsReview; при отсутствии сопоставимой word confidence результат явно требует проверки, без фиктивного значения уверенности.
- Ручная правка/оформление не запускают STT и анализ видео; изменение источников/графа/режима звука инвалидирует transcript. Предыдущий MP4 сохраняется при ошибке.
- Медианная ошибка размеченных границ слов ≤200 ms, p95 ≤500 ms; audio/video duration различается максимум на кадр. Если backend не выдаёт слова, расширить общий контракт и проверить качество, а не заменить измерение точностью фраз.
- Проверки сейчас на выделенном эмуляторе. Samsung A25, скорость/память на телефоне и человеческая приёмка — отдельные непроверенные критерии, реализацию не останавливать в ожидании телефона.

## Предварительное согласование API

До адаптеров зафиксировать в отчёте фактические закоммиченные сигнатуры #12/#15/#18:

1. Импорт до наличия graph/music: draft envelope под тем же project ID и владельцем assets; не создавать фиктивный HybridRevision. Порядок выбора отличается от дедупликации файлов по hash.
2. Открытие/закрытие lease, удаление источников без удаления опубликованного MP4, атомарная публикация и migration marker принадлежат общему store.
3. SourceTimeMap обеспечивает источник и PTS по тому же frame clock, что GPU. Старый speed ramp сохраняет точный результат; аудио использует предвычисленные якоря, не интегрирует ramp 80 раз на каждый PCM sample.
4. SpeechTranscriber принимает уже подготовленный PCM/WAV, а путь готового MP4 продолжает существовать. Общая структура результата различает Success, NoSpeech, Failure и содержит реальные слова/границы либо документированное расширение backend.
5. Общая ревизия хранит текст и ключ transcript; временный TextEditProject для готового MP4 может служить adapter, но не независимым источником истины для HybridProject.

### T1: Сверка общей модели текста и валидации

**Владелец:** #12; этот поток проверяет интеграционный контракт.

- [ ] Сверить TextLayer/CaptionCue/TextStyle с требованиями выше: code points, интервалы, пустая надпись, сохранение Unicode/ручных правок, 180/181 symbols и короткий результат.
- [ ] Недостающие mono/accent/scale/ограничения реализовать у владельца; не дублировать TextTrack/CaptionCue.
- [ ] Получить конкретный commit и red/green evidence необходимых тестов; review diff и совместимость сериализации.

### T2: Исходный звук после монтажа и три режима микса

**Владелец:** этот поток. Files: `SourceAudioComposer.kt`, `PcmStream.kt`, `AudioMixSettings.kt`, адаптеры общего source map и действующего AAC muxer; JVM/device tests.

- [ ] Red tests для 3+ источников, ненулевого первого audio PTS, gap, клипа без аудио между звучащими, speed ramp, повторного использования источника, EOF и отмены.
- [ ] MediaCodec декодирует ограниченными chunks. Собрать timeline 48 kHz 16-bit stereo по source map; пустые места остаются тишиной, за EOF чтения нет. На склейке основной источник и 10 ms fade, без наложения двух несвязанных голосов.
- [ ] Якоря времени на границах видео-кадров/склеек и с шагом ≤1 ms; внутри — интерполяция. Не выполнять интеграл legacy ramp на каждый из 48k samples.
- [ ] Сделать STT вход 16 kHz mono до музыки. Потоковый AAC export, режим MUSIC сохраняет старое поведение; SOURCE не добавляет музыку, SPEECH_AND_MUSIC смешивает явно заданные конечные gain в 0..1. Headroom/limiter удерживает mixedPeak ≤1; overlapping full-scale source/music проверяются regression test на пики и слышимый клиппинг в WAV/AAC.
- [ ] Проверить waveform/маркерные точки и длительность, учесть исправления музыкального #18; commit с тестами и scoped review.

### T3: Настоящая offline модель и упаковка

**Владелец:** #12. Этот поток потребляет и проверяет артефакт.

- [ ] Зафиксировать source artifact модели, версию, byte size, SHA-256 и license; при каждой сборке проверять размер/checksum до packaging. В каждом production APK проверить checksum payload и license notices; generated model payload не коммитить. Архивы распаковывать с проверкой traversal/required files. Проверить ABI, native page alignment и universal APK; несовместимость заявляемого ABI/page size блокирует выпуск.
- [ ] Не создавать Vosk/JNA/model downloader поверх Whisper. Ошибка модели отличается от отсутствия речи; runtime сеть не нужна.
- [ ] Реальный model load/recognition и освобождение ресурсов на эмуляторе, без fake STT. Missing/corrupt-model tests подтверждают явную Failure и сохранность проекта/прежнего MP4; ограничения телефона обозначить отдельно.

### T4: Адаптер распознавания смонтированной речи

**Совместная граница:** SpeechTranscriber в #12; PCM integration в этом потоке.

- [ ] Red test: prepared PCM без video track проходит в общий recognizer. Путь готового MP4 продолжает работать.
- [ ] Success/NoSpeech/Failure различаются; progress/cancellation проходят до decoder/model, нет повторного распознавания из-за style.
- [ ] Лицензированные русские fixtures и ручные границы; per-sample WER, шумная русская речь и речь с музыкой исходника. Проверить тишину, слово после gap, повтор source, app music с голосом поверх цифровой тишины без речи. Тихая разборчивая source speech распознаётся без подмены словами музыки приложения.
- [ ] Если Whisper доказанно не подходит по RU, RAM/времени или границам, предложить совместную замену за тем же интерфейсом с evidence; не объявлять backend пригодным по одному load.

### T5: Фразы, шрифты и геометрия

**Владелец:** #12; этот поток добавляет проверки интеграции со стилями.

- [ ] Настоящие glyph metrics Canvas/Typeface, grapheme-safe переносы, две строки субтитров, fit до SMALL или понятная ошибка overflow до экспорта.
- [ ] Сохранить реальный интервал короткого слова; паузы и max duration, без дубликатов на overlapping STT windows.
- [ ] Проверить квадратный FEAR, авторские Heartbeat/FEAR titles, emoji и длинное русское слово; один layout для preview/export.

### T6: Текст в общем GPU-экспорте

**Владелец:** #12; multi-source renderer integration — этот поток.

- [ ] Текст после эффектов, существующие авторские надписи сохраняются. Активные/ближайшие atlas, ограниченный LRU ≤16 MiB.
- [ ] Одинаковые half-open интервалы и sampler в preview/export, fade/scale ограничены cue; проверка первого/последнего кадра надписи и координат.
- [ ] Упаковка и GL cleanup проверяются реальным экспортом, не только unit bitmap tests.

### T7: Ревизии, transcript cache и повторный экспорт

**Общий store:** #15; text types — #12; этот поток реализует adapters/coordinator.

- [ ] Ключ transcript включает ordered fingerprints, graph/source map, audio mode/settings и model hash. Styling/ручные cues не меняют этот ключ.
- [ ] Сохранять raw результат и правки в общей ревизии; ни один cache path не заменяет долговечный asset.
- [ ] compose → STT → segment/save → mix/video/text → QA → atomic publish. Ошибка сохраняет прошлый MP4 и редактируемый draft, временный PCM удаляется finally.
- [ ] Re-export из общей ревизии без нового video analysis/STT. Source deletion делает недоступность редактирования явной, MP4 остаётся; старый MP4 без sources редактируется как готовое видео, не как восстановленный исходный монтаж.
- [ ] Tests: process death, missing source/model, transcript invalidation, сохранность исправлений, один lease на render.

### T8: Общий UI и жизненный цикл

**Владелец текста:** #12; gallery/audio adapter — этот поток.

- [ ] Согласованный экран для заголовка, исправления captions/интервалов, style и re-export; второй редактор не добавлять.
- [ ] Явный выбор звука, PREPARING_AUDIO/TRANSCRIBING/RENDERING, NoSpeech отдельно от Failure/retry; один активный render при повторных taps.
- [ ] Rotation/process restore не запускает заново STT. Preview использует те же часы и слышимый audio mode, что export.
- [ ] Instrumentation проверяет действие пользователя и durable corrections, а не наличие кнопок.

### T9: Финальная интеграционная проверка

- [ ] `clean baselineVerify`, полная обнаруженная UI suite на выделенном `.uitest`; для AGP9.1.1 известного device-filter failure использовать документированный direct AndroidJUnitRunner workaround с фактическими результатами.
- [ ] Настоящие 3+ source MP4: offset, silent middle, ramps, repeats; полный decode без артефактов. Маркеры слова/audio/video, median/p95 boundaries и duration diff ≤frame.
- [ ] App music с голосом поверх цифровой тишины без source speech даёт NoSpeech для исходной дорожки. Тихая разборчивая исходная речь не исчезает и не заменяется словами музыки; MP4-caption workflow распознаёт слышимый финальный звук отдельно.
- [ ] Offline emulator: настоящая RU модель, manual edit/re-export, отсутствие rerun STT и утечек/лишних decoders. CI относится к точному последнему commit.
- [ ] Evidence report фиксирует commit/APK/model hash, device/API/ABI/page size, fixture license, annotation method, per-sample WER, median/p95 word boundaries, STT/export runtime, peak RAM/buffer, cancel latency, MP4 durations и preview/export samples. Отдельно перечислить непроверенные Samsung A25, RAM/time/cancel SLA на телефоне и человеческую читаемость; draft PR не выдавать за полную приёмку.

## Команды и публикация

Package `com.veycad.app`, пути Kotlin пока `com/example/autoedit`; не переименовывать. Windows setup по WINDOWS.md. Bundled Python, не Store alias:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PYTHON = 'C:\Users\rexar\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.SourceAudioComposerTest'
.\gradlew.bat clean baselineVerify
.\tools\run_ui_tests.ps1 -Serial emulator-5556
```

Тестовые имена фиксируются вместе с реальными adapters. Перед commit scoped staging, просмотр cached diff и `git diff --cached --check`. Каждая связная часть — отдельный Conventional Commit с тестами, затем review. Публикация через PR; merge только после явного одобрения владельца и успешных checks последнего commit. Эти проверки запланированы, их прохождение документируется только после фактического запуска.
