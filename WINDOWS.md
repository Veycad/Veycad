# AutoEdit / Veykad: запуск с Windows

Это Android-приложение: Windows используется для разработки и сборки APK,
а само приложение запускается на Android-устройстве.

## Подготовка

1. Распакуйте ZIP целиком, например в `C:\Projects`. Корень проекта будет
   `C:\Projects\AutoEdit`. Скрытая папка `.git` тоже должна быть извлечена.
2. Установите Git for Windows и Android Studio с поддержкой Android Gradle
   Plugin 9.1.1. Через SDK Manager установите Android SDK Platform 36,
   Android SDK Build-Tools 36.0.0 и Android SDK Platform-Tools.
3. Для Gradle используйте JDK 25. Настройте его в Android Studio:
   Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK.
   Версии проекта: AGP 9.1.1, Gradle 9.3.1, compileSdk/targetSdk 36,
   минимальная версия устройства — Android 8.0 (API 26).
   [Требования AGP](https://developer.android.com/build/releases/agp-9-1-0-release-notes).
4. Откройте папку AutoEdit в Android Studio и дождитесь Gradle Sync.
   Первый запуск требует интернета для загрузки Gradle и зависимостей.
   Отдельно устанавливать Gradle или Node.js для сборки APK не нужно.
   Для полного `baselineVerify` нужен Python 3.12: он запускает также тесты
   вспомогательных инструментов, которым нужны NumPy и Pillow.

## Сборка из PowerShell

Перейдите в распакованный проект. Укажите фактический путь к JDK 25
(пример — JBR в Android Studio):

```powershell
cd C:\Projects\AutoEdit
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Android Studio обычно создаёт `local.properties` с расположением SDK.
Если собираете без открытия IDE, создайте его самостоятельно; для стандартной
папки SDK под текущим пользователем:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk".Replace('\', '/')
"sdk.dir=$sdk" | Set-Content -Encoding ascii local.properties
```

Если SDK установлен в другой папке, подставьте её путь с прямыми слешами.
Затем выполните:

```powershell
.\gradlew.bat --version
$env:PYTHON = 'C:\путь\к\python.exe'
& $env:PYTHON -m pip install -r tools\requirements-test.txt
.\gradlew.bat clean baselineVerify
```

В Codex можно использовать уже поставляемый интерпретатор:
`C:\Users\rexar\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`.
Переменная `PYTHON` выбирает среду только для текущего процесса PowerShell.
Отдельные проверки: `pythonTests`, `testMutationCheck`, `pythonMutationCheck`.
Мутационные проверки не изменяют исходники приложения или инструментов.

APK появятся в `app\build\outputs\apk\debug\`; универсальный файл —
`app-universal-debug.apk`.

## Запуск на телефоне

Включите режим разработчика и отладку по USB на Android-телефоне, подключите
его к Windows и подтвердите разрешение отладки на экране телефона.
Если устройство не определяется, установите USB-драйвер производителя.
В Android Studio выберите устройство и нажмите Run для модуля `app`.
Либо установите APK из PowerShell:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb install -r .\app\build\outputs\apk\debug\app-universal-debug.apk
```

После установки откройте Veykad на телефоне. При нестандартном пути SDK измените
`$adb`. Предпочтителен физический ARM-телефон: проект собирает ARM ABI
`arm64-v8a` и `armeabi-v7a`, а также использует аппаратные кодеки и GPU.

## История и текущая работа

Первоначальный архив переноса содержал незакоммиченные изменения. Состояние
рабочей копии после переноса зависит от последующих checkpoint-коммитов.
Проверить состояние после распаковки:

```powershell
git status --short
git log -5 --oneline
git branch -a
```

Не выполняйте `git reset --hard` или `git clean` без проверки текущих изменений.
Если Git сообщает `dubious ownership` после переноса, разрешите только эту папку:
`git config --global --add safe.directory C:/Projects/AutoEdit`.

## Состав переноса и ограничения

Сохранены исходники, ресурсы приложения, документация, `tools`, доступные
тестовые fixtures и история Git. Исключены зависимости `node_modules`, `.venv`,
локальные кеши Gradle/Kotlin/Swift/Python, результаты сборки `build`, настройки
IDE, машинный `local.properties`, логи и локальные результаты рендеринга
`artifacts`, `outputs`, `captures`, `diagnostics`. Файлы внутри Git-истории
сохранены без переписывания истории, даже если раньше туда попали бинарные данные.
Точный список переданных файлов и SHA-256 находится в `TRANSFER-MANIFEST.json`.

Ссылки из старых отчётов на исключённые рендеры и абсолютные пути macOS
на Windows работать не будут. Скрипты `tools/*.swift` используют Apple
AVFoundation/AppKit и требуют macOS; для сборки приложения они не нужны.
Python-утилиты из `tools` являются отдельными вспомогательными инструментами;
для части из них нужны numpy, Pillow, onnxruntime и внешние модели/видеофайлы.

Архив проверен на целостность и сохранность файлов/Git на macOS.
Проверки, проведённые после переноса, фиксируются в отчётах проекта; эта запись
об архиве не подтверждает успешную сборку текущего checkout.
