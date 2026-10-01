# Heartbeat v22(A): фактический экспорт и независимый звук

27 сентября2026, API36 x86_64 emulator. APK0.1.2/code3 SHA
`69316f82176c96db404be3462430d1f524b481fe88f071b296cea5811ce18b09`.
Лично присланный оригинал A SHA
`8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca`;
отдельная музыка SHA `cc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48`.
APK/source/music проверены до запуска; соответствующие runtime SHA в raw
совпадают. Baseline и предварительный протокол сохранены до запуска.

## Реальные файлы

- MP4 `artifacts/quality/runs/user_heartbeat_v22_a_20260927.mp4`,13173528байт,
  SHA `4ba33db0b7d05dfc31147e4a9f3f7bcd0644d467c3d0c0badc163594129c834c`.
- Raw `.mp4.result`,4992байт, SHA
  `252f387fef9a74e626d1cbb2c96ca57554bc00f39ab707deb53a93a15bcb6362`.
- Новый `-inspector.json`,1264051байт; `-contact.jpg`,234611байт.
- Новая `.audio.json` и пустая output-bound `.review.json`.

## Машинные наблюдения

status=ok,HEARTBEAT_V1:production,27клипов,1270кадров,21.166с.
repeated_source_ratio=0.0 вместо v21(A)0.029531915; unchanged gate0.02.
Это измерение случайного пересечения исходных временных окон, не обещание
отсутствия всех одинаковых пикселей или художественно неуместной репризы.

26/26пульсаций совпали. Полный contiguous decoded black-tail:82ожидаемых,
82измеренных,82чёрных;2кадра границы, matched=true,смещение начала0.
Свежие decoded-face-exact-pts-v1:242/242измеренных,unknown0,7потерь,
face_loss_rate0.02892562<unchanged0.05. Это не гарантия читаемости лица
человеком на каждом кадре. Контейнерных issues нет, A/V drift3378мкс.

Независимый FFmpeg7.1unclamped-f32 AAC decode:44100Гц,2канала,
1867776PCM samples,21.176598с,peak0.4500930607318878;
nonfinite/overfullscale/fullscaleplateau=0,passed=true. Повторное независимое
измерение в текущем quality_render_report совпало с сохранённым audio report.
Android acceptance=false только из-за audio-unclamped-headroom-unavailable;
offline gate разрешил только этот unknown. Остались исключительно незаполненные
человеческие проверки. machine_and_human_pass=false, не ложный полный успех.

## Приёмка и границы вывода

Независимый read-only аудит нового реального inspector подтвердил
authored-clip-intervals-v1:27окон,production generator,все26визуальных
source/output durations одинаковы,actual speed1. Первые15 authored half-open
интервалов пересекаются на0мс. Но actual decoded_source_us sets дают один
общий кадр26895666мкс у clip6/clip14, sourceIndex0. Clip6 заканчивает
authored26888000мкс,clip14начинает26889000мкс (зазор1мс).
Последний запрос clip6:26887667мкс, actual26895666мкс;первый clip14:
26889000мкс,actual26895666мкс. MediaCodec ceiling sampling берёт первый
PTS>=target; оба запроса попали на один VFR кадр. Это существующий контракт,
не подмена actualPTS authored target. Других общих primary decodedPTS нет.

Диагностически1повтор из705primary outputframes≈0.00141844 (≈16.667мс
из11.750с); эта величина не равна graph-based repeated_source_ratio0.0
и не объявляется новым заранее утверждённым gate. Авторская реприза отдельно
исключена. Строгого нулевого actual-pixel-repeat gate здесь нет; unchanged
0.02критерий для authored случайного пересечения не изменён. Не заявляется
доказанное отсутствие всех реальных повторённых текстур.
Actual source timestamps — SurfaceTexture.timestamp рядом с draw, а не
независимое распознавание идентичности исходных кадров из encoded MP4pixels.
Все1270output timestamps совпали с rounded60fps clock0..21150000мкс;
1188визуальных renderer frames имеют speed1 и известный decoded_source_us.

Контактный лист просмотрен как диагностика, не как человеческая приёмка
полного ролика со звуком на1×/.5×. Создана новая пустая форма с точным MP4 SHA,
recipe/generator; reviewer/date/playback/7общих+3стилевых checks не заполнены.
Человеку отправлен отдельный запрос просмотра именно этого файла.
Старый отзыв WhiteKing и оценки v21 не переносятся.

A уже использовался для настройки: это калибровочный регрессионный успех,
не sealed независимый корпус, не закрытие общей цели. Sigma заблокирована.
Новая0.1.2 остаётся локальной тестовой сборкой; доставленный на Яндекс Диск
APK0.1.1 не изменён и не объявляется новым задним числом.
