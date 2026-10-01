# FEAR: диагностический replay A — до запуска

FEAR(A) реально отказан insufficient_motion_evidence, raw
4ca1d298…e23d28d; 112/112 assessments, все физические измерения unknown.
Нужна стадия отказа каждого измерения, не искусственно положительный FEAR.

- Installed APK f902ed2fa02d741f37b7de368a9117f614eff4c959ab0716588a3016e984d2c7.
- Original A 8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca.
- Уже завершённый cache18 editorial-correspondence-v1, reference SHA
  d8848659ff9d274291ca0e09ff1b7451520859f9a5cd0bf2cf96fef04a3e1034.
- Отдельная FEAR-музыка 2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37
  нужна служебной точке входа; диагностический replay не экспортирует звук.
- Новая device цель `.../files/golden/user_fear_v21_a_motion_probe_20260927.json[.result]`,
  локальная `artifacts/quality/runs/` с тем же именем.

MotionEvidenceProbe заново декодирует exact PTS того же оригинала и
повторяет физическую корреспонденцию с завершёнными текущими семантическими
масками. Нового семантического inference/директора/MP4/приёмки нет.
Профили/метод/пороги не меняются. load может обновить mtime cache,
но не его байты; до/после сверяется SHA. Это диагностика, не новый
одновходовый монтаж и не изменение истории B→A следующего монтажа.
Публичных источников/замен/новой работы над Sigma нет.
