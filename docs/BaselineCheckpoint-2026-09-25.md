# Development baseline — 2026-09-25

## Проверка текущего checkout

Среда: Windows, JBR 25.0.3 из Android Studio, Gradle 9.3.1, AGP 9.1.1,
Android SDK Platform/Build-Tools 36.0.0. Из корня проекта запущено:

```powershell
.\gradlew.bat --no-daemon clean baselineVerify
```

Итог: `BUILD SUCCESSFUL`, 56 задач выполнены после `clean`.
`baselineVerify` включает `testDebugUnitTest`, `lintDebug`,
`verifySecurityContract` (merged release manifest) и `assembleDebug`.
В JUnit XML — 268 тестов в 40 suites, 0 failures/errors/skipped.
Lint: 0 ошибок, 13 предупреждений. Предупреждения оставлены видимыми;
этот gate требует нулевого числа ошибок, а не предупреждений.

Созданы три debug APK: arm64-v8a, armeabi-v7a и universal. Для universal
`app/build/outputs/apk/debug/app-universal-debug.apk` SHA-256:
`2edd312d45849cae511b846bb0a0a2ecdda9ed08d9e282330bde25a8aa59ed29`.
Проверка packaged manifest показала `minSdk 26`, `targetSdk 36` и
`uses-gl-es: 0x20000`.

Gate повторён после checkpoint-коммита с тем же результатом: 56 выполненных
задач, 268 тестов без сбоев, lint 0 ошибок/13 предупреждений. Хеши debug APK
двух чистых запусков различались, поэтому здесь фиксируется хеш последнего
запуска; побайтовая детерминированность APK не заявляется.

## Границы

Это воспроизводимая локальная сборка и unit/lint/security gate, не новый
полный device-render каждого стиля. Проверки жизненного цикла и геометрии P1
описаны в [приёмке P1](p1-acceptance-2026-09-16.md). CI workflow добавлен в
`.github/workflows/android-baseline.yml`; у этого локального checkout нет Git
remote, поэтому серверный запуск workflow на эту дату не подтверждён.

Контракт устройств и видео, включая ещё не принятый нижний аппаратный профиль,
описан в [SupportMatrix.md](SupportMatrix.md). Отдельные P2/P3 из
[todo.md](../todo.md) остаются открытыми и не объявляются закрытыми данным gate.
