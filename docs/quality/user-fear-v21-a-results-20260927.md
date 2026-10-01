# FEAR v21 на A — измерение движения не подтверждено

Фактический маркер `user_fear_v21_a_20260927.mp4.result`,
SHA `4ca1d2981906ae4f8dec0533f09c5d4405e8bcb08c92e8bde7ed9fc63e23d28d`.
Runtime APK0.1.1/code2 `f902ed2fa02d741f37b7de368a9117f614eff4c959ab0716588a3016e984d2c7`,
API36/x86_64/split0, source A и авторская музыка совпали с регистрацией.

- `status=material_rejected`, `rejection_code=insufficient_motion_evidence`.
- MP4 отсутствует; это не успех FEAR и не отрицательный пример
  `insufficient_motion` на подтверждённо неподвижном материале.
- Android получил 112 exact-PTS целей, декодировано 1670 кадров.
- Завершённый cache18: editorial-correspondence-v1, 112/112 assessments;
  0 валидных body и 0 независимых camera samples, 112 inconclusive.
- Cache SHA `d8848659ff9d274291ca0e09ff1b7451520859f9a5cd0bf2cf96fef04a3e1034`,
  отчёт `user_fear_v21_a_20260927.cached-motion.json` явно cache-only/not-acceptance.

Unknown не означает неподвижность героя. Читаемое лицо/уверенная маска и
старые luma-векторы не подменяют новую физическую корреспонденцию. По этим
полям нельзя установить точную стадию отказа каждого измерения; требуется
отдельная диагностическая запись stage/support на тех же реальных кадрах.
Пороги не изменены, движение не синтезировалось.

Новая negative-review.json связана с exact raw SHA/recipe/source и пустая:
реальная оценка человеком материала/сообщения ещё не получена. Источник
знакомый, не новый слепой родитель. Следующий одновходовый монтаж — B.
