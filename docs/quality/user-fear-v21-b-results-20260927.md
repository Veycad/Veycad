# FEAR v21 на B — подтверждённый отказ по длительности

Фактический завершённый маркер `user_fear_v21_b_20260927.mp4.result`,
SHA `d94b108c60ba4219f1db716be417878f9ddc6c7ff54fa1abae5b080fedc88d43`.
APK0.1.1/code2 `f902ed2fa02d741f37b7de368a9117f614eff4c959ab0716588a3016e984d2c7`,
API36/x86_64, split=0, source B и FEAR-музыка совпадают с регистрацией.

- `status=material_rejected`, `recipe=FEAR_STROBE`.
- `rejection_code=insufficient_duration`.
- `detail=Each source needs at least 15000 ms for FEAR_STROBE`.
- MP4 не создан; исходник не растягивался, искусственное движение не добавлялось.
- Перед отказом декодирование получило все 58 exact-PTS целей второго файла,
  включая lastTargetUs14377322 при declaredVideoDurationUs14377000.

Это реальный типизированный отказ, не прежняя ошибка декодирования v20.
Создан новый незаполненный negative-review.json, связанный с SHA raw-маркера,
рецептом и исходником. Человеческая корректность отказа ещё не подтверждена;
это знакомый источник, не новый независимый отрицательный родитель.
Следующий одновходовый источник — A.

Дополнительное чтение завершённого cache18 с профилем
editorial-correspondence-v1: 58/58 assessments выполнены, но валидных
физических измерений тела/независимой камеры 0/0. Все 58 inconclusive,
а не статичные. Отчёт `user_fear_v21_b_20260927.cached-motion.json`
явно cache-only/not-acceptance; SHA прочитанного cache
`04b3354edc4af54e0ea705c888b5670bdc83cf4710c1aed00a400cec6783cc1e`.
Он не заменяет свежую MP4-проверку, ground truth движения или raw-код
отказа: фактическая причина этого прогона остаётся insufficient_duration.

