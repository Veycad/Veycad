# Текущий кандидат Veycad: 5b22308

Исходники: `5b22308e2519e93136b7ad24e846c7992644efc7`,
ветка `codex/api26-save-recreation-test`, [draft #124](https://github.com/Veycad/Veycad/pull/124).
Включает исправление AAC на Android 8 [#123](https://github.com/Veycad/Veycad/pull/123).
**Не подписан, не установлен и не принят как окончательный release.**

## Сборка и состав

`GIT_SHA=<полный SHA> gradlew.bat assembleRelease` — успешно, 3м44с.
Исходное дерево чистое. В DEX каждого APK найден полный source SHA; BuildConfig и
badging сверены: `com.veycad.app`, 0.1.16/code17, minSdk26, targetSdk36,
DEBUG=false, LIVE_PREVIEW_ENABLED=true. Тринадцать старых прототипов отсутствуют.
Whisper model SHA-256:
`60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`.
Проверены байты и хэши дополнительных runtime notices; это не полная проверка
прав всех SDK/музыки/моделей. `apksigner verify` ожидаемо отвергает unsigned APK;
сертификат, постоянный ключ и установленная release-сборка не заявлены.

Неизменяемые копии и `receipt.json`:
`D:/CodexWork/veycad-current-release-5b22308-20261110/`.

| APK | Байты | SHA-256 |
| --- | ---: | --- |
| app-universal-release-unsigned.apk | 280869897 | `d3464aaa0ef100a40d8295073f5093daa9a2454bd77f3988d56a7a05acee0227` |
| app-arm64-v8a-release-unsigned.apk | 220742528 | `7c9d73ae9adb3bd17cc372919655a860648451a218548c5a1a2881412158924d` |
| app-armeabi-v7a-release-unsigned.apk | 209872046 | `3a8a6439f8109d42d83036dbd076329bba078867b0732442a3856a4a4d1b89ac` |
| app-x86_64-release-unsigned.apk | 225949539 | `121ddb0bfde319075314dc44f0ab14f3631d102f9dfe269a913db920e6a4400b` |

## Выравнивание и разрешения

Все четыре APK прошли SDK `zipalign -c -P 16 -v 4`. У 14 уникальных
64-bit ELF проверены все PT_LOAD: alignment >=16384 и совпадение offset/vaddr
по модулю 16384. Доказательства:
`D:/CodexWork/veycad-current-alignment-5b22308-20261110/receipt.json`.
Это статическая проверка, не runtime на физическом устройстве.

У всех четырёх APK один набор uses-permissions: CAMERA, POST_NOTIFICATIONS,
WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE и собственное signature
`com.veycad.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Нет INTERNET,
ACCESS_NETWORK_STATE, RECORD_AUDIO, чтения всей медиатеки, контактов или геолокации.
Вывод aapt и SHA каждого APK — `privacy-permissions.json` рядом с APK;
[памятка для консоли](rustore-data-safety.md) сохраняет границы аудита SDK.

## Что подтверждено отдельно

[Runtime/CI](runtime-2026-11-10.md): GitHub baseline и API28 legacy 8/8 текущего
коммита успешны; полный UI ещё выполняется. Локально на API26 AAC/редактор
и сохранение прошли в документированных отдельных прогонах. На API35/x86_64
эмулируемый 16 КБ userspace общий редактор/AAC/реальная речь прошли 11/11 в одном
процессе; независимое декодирование подтвердило маркеры, длину и headroom.

Это UI/debug APK с отдельно зафиксированными source bindings. Пять x86_64 SDK
библиотек совпадают с release побайтно; `libveycad_preview_memory.so` и
`libveycad_whisper.so` различаются. Полное сравнение —
`ui-release-native-comparison.json` рядом с release APK.
Успех тестовой сборки не переносится на эти release-библиотеки автоматически.

Открыты причина прежних VerifyError/JIT SIGSEGV, приёмка окончательного
подписанного APK на physical ARM/16 КБ, same-key update и человеческая оценка
изображения/звука. Условия публикации и продолжение — [handoff](release-handoff.md).
