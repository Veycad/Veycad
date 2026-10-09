# Veycad Montage Engine reboot

> Историческая запись об архитектурной границе после reboot. Описания удалённой
> оболочки и «следующего контракта» относятся к тому этапу. Текущее приложение,
> включая UI, локальный анализ и четыре рецепта, описано в [README](../README.md)
> и [EventDrivenEngine](EventDrivenEngine.md).

## Архитектурная граница

Ядро не зависит от камеры, UI, ML-моделей, эвристического режиссёра или Media3.
Любой новый анализатор пишет только `MontageGraph`. Renderer не имеет права
выбирать моменты, менять структуру монтажа или подменять неудачный граф fallback-ом.

## Что сохранено

1. `MontageGraph`, `ParameterTrack`, миграция схемы и интерпретатор таймлайна.
2. `HighQualityFramePlan` с целочисленным PTS и кривыми скорости.
3. `MediaCodecSpeedRampRenderer`, GLES compositor и type-specific transition windows.
4. `AacEncoderMuxer` и `AudioExportPlan` с sample-clock синхронизацией.
5. `ExportContract`, локальная диагностика и Render Inspector.
6. Бренд Veykad: имя, launcher icon и минимальная reboot-оболочка.

## Что удалено намеренно

- старый AutoDirector/ReferenceDirector и все связанные эвристики;
- старые preview/result/settings/gallery/diagnostics экраны;
- Media3/Crimson export path;
- proxy selector и QA, принимавшие визуально слабые ролики;
- ML Kit анализ, CameraX-запись и старые capture flows;
- встроенные Pixabay MP3 и вся продуктовая демо-логика.

## Следующий контракт

Новая режиссура должна подключаться отдельным модулем и проходить golden/device
acceptance до появления в приложении. Ни один UI-сценарий не возвращается раньше,
чем один end-to-end монтаж заметно превосходит сохранённый предыдущий результат.

Текущая реализация event-driven контракта и её честные границы описаны в
[`EventDrivenEngine.md`](EventDrivenEngine.md).
