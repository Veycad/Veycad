# Actionable processing failures

Owner context: autonomous execution of the approved RuStore preparation plan,
Task2. The implementation choices below are the agent's, not a new owner approval.

- Unknown codec/execution failures show: «Не удалось завершить операцию. Попробуй ещё раз. Если ошибка повторится, выбери другое видео или стиль.»
- Existing typed material/search outcomes and cancellation retain their distinct meanings.
- Remove legacy free-text duration classification. Mark the three actual legacy minimum-duration producers in `EditDurationPolicy`, `DualityLoopDirector` and `ReferenceMontageProfile` as owned copy without changing their conditions. Vendor errors and filenames containing the same words must not turn into a source-duration rejection.
- An explicit `UserMessageException : IllegalArgumentException` identifies owned actionable validation text. Use it for analysis-budget refusals and unavailable source files, preserving thresholds and messages. Internal invalid analysis cadence remains an ordinary programmer error.
- Preserve the original throwable in Android's local system log at the two general UI boundaries. The app's diagnostic export records existing bounded exception-type/cause fields, not raw messages, paths or user text.
- Reuse existing presentation, validation, diagnostics and UI-test fixtures; no dependencies, renderer changes, new settings or data migrations.
- Verify JVM negative/positive cases and existing boundary limits; native UI must show the prompt, preserve safe diagnostics, allow retry and survive recreation. Do not interrupt the active V12 full run to install these APKs.
- Scope is owning-main branch behavior. Integration, signed/device/media acceptance and owner-approved merge remain separate.
