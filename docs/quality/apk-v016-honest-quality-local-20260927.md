# APK0.1.6: точное объяснение отказов и человеческая приёмка

27 сентября2026. Это локальная тестовая APK, не release-ready приложение.
Universal debug0.1.6/code7, min26/target36, signaturev2 verified.
Файл `artifacts/quality/apk/Veykad-0.1.6-honest-quality-20260927.apk`,196329375bytes,
SHA-256 `5ba771a9dbc778738f15eadff3d2ca68533ab53bdd7d95678b7fa1bcd1a27ce7`.
SHA копии совпадает с actual build output. Установка `-r` без очистки данных
успешна; actual version0.1.6/code7 и base.apk SHA повторно подтверждены.
После установки app PID отсутствует: это не запуск монтажа и не ошибка установки.
На Яндекс Диске исторически доставленная0.1.1 не заменена.

## Что изменено относительно проверенной0.1.5

`MainActivity.showFailure` использует чистую `MontageFailurePresentation`.
Исчерпание конечного семейства measured-anchor pools не выдаётся за доказательство
непригодности всего видео. Достижение вычислительного лимита отдельно объясняется
без visits/validations в пользовательском сообщении. Typed exceptions и debug
счётчики сохраняются; отказ не становится успешным монтажом. Прежние сообщения
длительности15секунд/DUALITY6секунд, людей, FEAR motion/unknown-motion, отмены,
codec и fallback сохранены. Sigma locked/paused, не accepted.

Отдельные offline quality tools теперь требуют непустое строковое имя проверяющего
и корректную ISO datetime с часовым поясом, не из будущего. Истина просмотра
не выводится из валидной даты; это не подпись и не новый человеческий отзыв.
Shared validation применяется к positive/negative matrix cases; отдельное
требование даты после pre-disclosure seal сохранено. Blank forms не заполнялись.

Positive matrix cases также обязаны хранить inspector JSON с exactSHA;
missing/outside-repo/hash-mismatched artifact не считается прошедшим даже при
зелёных raw flags. Native format/outputname+bytes/graph/summary/container/frame
records должны согласоваться с MP4 и raw. Это integrity/self-consistency, не
новый physical motion threshold, подпись или подтверждение human viewing.
Repeated decoded PTS допустимы; прежние кадры не генерируются заново.
Округление последнего shader/mux timestamp допускается только в пределах±1us:
actual historical FEAR/DUALITY выявили этот случай;±2us и frame-sized mismatch
отвергаются. Их отсутствие новых execution fields всё ещё исключает full pass.

## Проверенная область и ограничения

Full build `testDebugUnitTest assembleDebug lintDebug verifySecurityContract`
завершился SUCCESS2m16s. Actual XML:578JVMtests/76suites,
failures/errors/skipped0; среди них13новых pure failure-copy tests.
Lint0errors/13advisorywarnings, security contract passed, signaturev2 verified.
Первый offline identity/date checkpoint:190Python tests passed3.456s,
включая11новых методов с malformed-value subcases. Первая попытка имела один
неактуальный expected-message assertion: более ранний строгий отказ в матрице
заменил поздний общий отказ. Исправлено ожидание, требования не ослаблены.

После inspector retention checks итоговая Python suite205tests passed5.969s;
root независимо повторил205tests passed5.894s. Новые malformed/absent inspector,
wronghash/name/bytes/summary/container/clock/record tests не являются реальными
human/material cases. Root повторно декодировал AAC каждого v25 A/C: saved==fresh,
retained inspector issues[], full assessmentfalse ровно по12human требованиям.
Текущая полная matrix CLI: errors[], verified_case_count0, readyfalse,
active_products_readyfalse, Sigma paused_not_release_ready; exit1 ожидаем при
незавершённом покрытии. Ни одного fake positive case в реальные данные не добавляли.

Read-only binary SHA audit against frozen v25 archive сравнил87прежних
production Java/Kotlin files: отличается только MainActivity. Новый presenter
и его13tests добавлены отдельно, build version обновлена. Directors, source
selection, renderer, acceptance thresholds и music не изменены. Ошибка первого
inline audit launcher (shell quoting) возникла до сравнения; повторный read-only
запуск выполнил описанные87сравнений. Никакие архивы/исходники он не извлекал
и не перезаписывал. Это source preservation evidence, не native execution0.1.6.

Actual native v25 A→C выполнены на exact0.1.5/code6 и сохранены с её runtimeSHA.
Оба execution/AAC checks passed; новые human forms полностью пусты, full gatefalse
только по12human requirements. Их доказательства и старые likedv23 отзывы
не переименовываются в native/human acceptance0.1.6. На0.1.6 монтаж не запускали.
Сохранены exact liked FEARv23(C) и Heartbeatv23(A), для обоих только1× feedback.

Original full scope неизменён: четыре самостоятельных продукта, Sigma одна
на паузе; diverseunused corpus0, exposedpilots3, sealed releasecases0.
Все имеющиеся MP4 — пилотные проверки, не независимая приёмка выпуска.

После успешных проверок создан отдельный frozen dirty-worktree pilot snapshot:
`artifacts/quality/baseline-v26-v016-honest-quality-20260927.tar`,254239232bytes,
SHA-256 `1365bdf56531533ada8029f7106a779cc970fc5e8793aa57f68735bf9213e3b0`.
App sources/assets/tests, tools, quality docs, README и build/Gradle configs
сохранены без изменения прежнего v25 архива. Эта строка добавлена после
фиксации архива; архив не перезаписывается. Это pilot snapshot, не corpus seal.

Связанные материалы:

- [Фактические A/C результаты](user-v25-native-results-20260927.md).
- [Новый лист человеческой приёмки A/C](user-v25-heartbeat-human-review-20260927.md).
- [Текущий манифест](holdout-user-only-active-candidate-20260927.json).
