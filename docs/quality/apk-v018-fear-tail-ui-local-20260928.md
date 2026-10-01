# Локальная APK 0.1.8: проверка финала FEAR и исправления интерфейса

Сохранён universal debug APK:
`artifacts/quality/apk/Veykad-0.1.8-fear-tail-ui-20260928.apk`,
196329375 байт, SHA-256
`35683fe6c277f5670ed3f39bca5c88c0759bd1aebd82e3a12e598d0e9b50eab4`.
Копия побайтно совпала по SHA с `app/build/outputs/apk/debug/app-universal-debug.apk`.
Актуальный manifest: `com.example.autoedit`, versionCode9, versionName0.1.8,
minSdk26, targetSdk36. `apksigner verify` прошёл с подписью v2 и одним подписантом.

Нативная проверка FEAR теперь отклоняет любой пропущенный или неверно окрашенный
авторский импульс, включая чёрный хвост после последней вспышки. Раньше native
acceptance учитывал три ранних интервала финала, но мог пропустить неверный
последний чёрный участок; отдельный offline gate уже проверял все интервалы.
Фокусный JVM тест подтверждает принятие полного расписания и отказ при
искажении/удалении каждого из34интервалов. Структура монтажа, музыка и пороги
не менялись этой правкой.

Сборка также включает изменения интерфейса из соседней работы: доступность
нижней кнопки, паузу предпросмотра и видимость ошибки монтажа. Там были
выполнены46/46 UI-тестов на отдельной `uiTest` установке до этой сборки.
Они не являются UI-прогоном самого сохранённого APK0.1.8.

Для текущего исходного кода root выполнил `baselineVerify --rerun-tasks` с
явно указанным bundled Python: BUILD SUCCESSFUL за2м37с,55tasks executed.
Фактические отчёты:595JVM тестов/77suites, fail/error/skip0;
242Python теста, fail/error/skip0; lint0errors/14warnings; release security
contract passed. Первая попытка этой команды без явного пути Python завершилась
до Android tasks из-за отсутствия системного `python`; повтор с указанным
интерпретатором прошёл полностью. Это проблема PATH, не тестовый дефект.

Установка на `emulator-5554` через `adb install -r` завершилась `Success` без
очистки данных. Прочитаны actual version0.1.8/code9 и SHA установленного
`base.apk`, совпадающий с сохранённой копией. Холодный запуск MainActivity
вернул `Status: ok`, процесс появился. Перед установкой native golden worker
отсутствовал.

FEAR/DUALITY v28 и Heartbeat v27 были выполнены на прежнем точном APK0.1.7,
их результаты не приписываются новой версии. Затем выполнены FEAR(A) и
FEAR(C) на0.1.8: первый дал отказ по недостатку измерений движения, второй
прошёл actual execution/retained inspector/independent AAC gates. Подробности:
`user-v29-fear-tailgate-results-20260928.md`.
Человеческая приёмка для неё пока не выполнена. Три пользовательских
исходника уже pilot, независимых unused0, releasecases0; Sigma остаётся
заблокированной. На Яндекс Диск этот APK не загружался.

30 сентября пользователь подтвердил продолжение на имеющихся тестовых данных.
На той же точной0.1.8 завершены Heartbeat(A), DUALITY(A,B) и DUALITY(B,A), без
новой сборки и без изменения понравившихся exports. Все три actual execution /
retained inspector / independent AAC проверки прошли. У Heartbeat изменились
10из27окон относительноv27; он сохранён как отдельный вариант, не одобренная
копия. DUALITY сохранил decoded pixels/audio обоих порядков иv28.
Протокол: [`user-v30-v018-active-results-20260930.md`](user-v30-v018-active-results-20260930.md).

30 сентября на exact установленной0.1.8 дополнительно проверена реальная
строкаSigma: видимый замочек, enabled/clickable=false, touch не меняет
selectedHeartbeat; диалог отменён, preferences сохранены. Это узкая UI-проверка
самой0.1.8, не полный повтор46UItests. Heartbeat(B) на том же APK дал
actual insufficient_duration безMP4; сохранены raw и пустая negative review.
[Доказательстваv32](user-v32-sigma-ui-and-duration-20260930.md).
