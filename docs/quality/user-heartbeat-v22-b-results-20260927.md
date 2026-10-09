# Heartbeat v22(B): фактический отказ по длительности

27 сентября2026. Точная версия0.1.2/code3, runtime APK SHA
`69316f82176c96db404be3462430d1f524b481fe88f071b296cea5811ce18b09`.
После завершённого v22(A) взят лично присланный оригинал B SHA
`81629968d2a0b41b355ec729d900f9b1cd2aec244a543f785796e5aa55d616b2`.
Source/music/APK SHA до запуска совпали; целей и монтажного worker не было.
Исходник не преобразовывался. Предварительный протокол сохранён до запуска.

Настоящий маркер `user_heartbeat_v22_b_20260927.mp4.result`,582байт,
SHA `3ca2de68210462a67dbdfc8a551314f43cc5c7c853fffa5d89e130d10c930ff3`:
status=material_rejected,rejection_code=insufficient_duration,
detail=Each source needs at least15000ms for HEARTBEAT.
MP4 отсутствует на устройстве и локально: отказ не считается монтажным успехом.
Runtime source/render_source SHA равны B; music SHA
`cc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48`.

Новая `.negative-review.json` привязана к точным raw bytes и оставлена пустой:
проверяющий/дата/material_case_confirmed/message_specific не заполняются
автоматически. Это регрессионный машинный отказ на знакомом коротком видео,
не независимая человеческая приёмка отрицательного случая.

Последняя одновходовая попытка теперь B; следующая должна A.
Sigma остаётся заблокирована, публичные видео не использовались.
