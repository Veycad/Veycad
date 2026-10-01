# APK0.1.5: локальный measured-pair кандидат, не release ready

27 сентября2026. Universal debug0.1.5/code6, min26/target36, signaturev2 verified.
Файл `artifacts/quality/apk/Veykad-0.1.5-measured-pairs-20260927.apk`,196329375bytes,
SHA-256 `c784516f5787ffacff039b83c0a2cdb2a174395255f8937f8b6430fb826932e1`.
SHA копии равен build output. Установлен `-r` без очистки данных на emulator-5554;
version0.1.5/code6 и SHA фактического base.apk совпали. Впоследствии оба
зарегистрированных native экспорта A→C завершены на этой exact APK.

Изменения: Heartbeat-only fallback после типизированного selected-pool отказа;
окна соседних реальных измерений; совместные замены с прежними scores и
двухотсчётным membership; полный директор и неизменённые readability/echo gates.
Прежний успешный путь возвращает точный прежний pool/graph. Computation cap —
отдельный nonmaterial failed, finite-domain refusal — scoped material rejection,
с неизменяемыми счётчиками/limits в debug raw. Отмена проверяется в подборе,
резервировании и interlude. Приложение не генерирует новые исходные кадры/движение.

Проверки: focused40pass; полная JVM565tests/75suites fail/error/skip0;
Python179pass; assembleDebug/lintDebug/security SUCCESS1m14s, lint0errors13warnings.
Реальные cache probes A/C−67 и C+67 прошли только planning contracts.
Оба новых output-bound decoded inspector/AAC проходят, включая повторный AAC
decode. C использует три совместные замены; все27 rendered окон совпали с
planning. Полная human acceptance по новым bytes остаётся незавершённой.
Проверенные исходники A/B/C — pilot, не независимый unused корпус.

FEARdirector/profile, Heartbeatprofile, frameplan, compositor, decoded acceptance
и inspector побайтово совпали с v24 snapshot. FEAR source selector неизменён
(общему private generator добавлена только отмена с пустым default callback).
Понравившиеся HBv23(A)/FEARv23(C) MP4 сохранены; положительный отзыв и только1×
не переносятся на этот кандидат. Sigma всё ещё locked/paused, не accepted.

Это локальная тестовая APK. На Яндекс Диске остаётся исторически доставленная
0.1.1; новый cloud upload/публикация release ready здесь не заявлены.
Native протокол:
[`user-v25-measured-pair-candidate-plan-20260927.md`](user-v25-measured-pair-candidate-plan-20260927.md),
[`user-v25-native-results-20260927.md`](user-v25-native-results-20260927.md).
