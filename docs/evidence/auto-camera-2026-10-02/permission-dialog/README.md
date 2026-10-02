# Нажатие системной кнопки разрешения в UI-тестах

На commit `8d96d42ce66ccd046aaa9eb9cdaddcab2f2a474c` GitHub Actions [Android UI, run 36987207586](https://github.com/rexarmakedonskij-hue/Veycad/actions/runs/36987207586) завершился ошибкой: один из двух permission-тестов не закрыл диалог после нажатия «Отказать». Все 58 экранных тестов прошли. Это терминальный неуспешный результат, он не заменён ретраем.

Артефакт `11218114810` скачан через GitHub connector; SHA-256 ZIP совпал с метаданными API. В logcat видны нажатие UiObject2 в `(160, 441)` и отброшенный InputDispatcher DOWN с теми же координатами: `NO_INPUT_CHANNEL`, затем `all windows would just receive ACTION_OUTSIDE`. Через 15 секунд тест завершился AssertionError. Отрывок и исходный XML — рядом; полные логи сохранены локально в ignored `artifacts/camera-ci-8d96d42`.

Helper теперь ждёт видимую, включённую кнопку настоящего `com.android.permissioncontroller` в окне с фокусом и выполняет её accessibility `ACTION_CLICK` один раз. Он требует успешное принятие действия и исчезновение диалога. Все прежние проверки разрешений, disabled render, отсутствия галереи при отказе, повторного импорта и точного совпадения байтов сохранены. Не добавлены shell grants, пропуски, ослабление assertion или повтор всего теста. Это изменение AndroidTest, production APK не меняется.

На выделенном API 36, `emulator-5558`, обновлённая официальная permission-фаза прошла **2/2** за 10.96 с, без ошибок и пропусков: `permissions-after.xml`, `permissions-after.txt`. `result.json` фиксирует run ID, метаданные CI и обе фазы прежнего CI. Терминальный результат остальных 59 тестов текущего полного прогона и проверок последнего head отслеживается в draft PR #5; здесь зафиксирована только завершённая permission-фаза.

Предшествующий production commit `f3628c41558fc05f5d971f3cfbba3f3c1d4fdeb6` прошёл полный локальный UI-прогон 61/61 и чистый baselineVerify: [доказательства](../preparing-loss/README.md). Телефон пользователя и причина его ошибки этим изменением не подтверждены; физическая приёмка остаётся открытой.

Тексты нормализованы до UTF-8/LF с удалением хвостовых пробелов. SHA исходных и сохранённых файлов — в `log-provenance.json`.
