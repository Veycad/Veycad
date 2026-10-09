# Самостоятельный gate случайных повторов и ритма активных продуктов

## Подтверждённый пробел до правки

Read-only запуск прежнего quality_render_report.assess на явно синтетических
unit fixtures показал machine_and_human_pass=true для Heartbeat с
repeated_source_ratio1.0 и acceptance=true; отсутствие ratio и NaN тоже не
проверялись. Подставленный beat_hit_rate0.01 при единственном Android
audio-unclamped-headroom-unavailable и unit independent audio fixture тоже
давал true. Это не реальная человеческая подпись или плохой новый MP4:
так воспроизводится недостаточная самостоятельная проверка численных полей.

Причина: offline Heartbeat branch ошибочно считал ratio исключительно
Sigma penalty и вообще его не читал. Прежний stock heartbeat_result
с ratio1.0 закреплял это неверное предположение. Offline beat_hit_rate
также отсутствовал у Heartbeat/FEAR, как и repeat check у FEAR.

## Уже существующий native контракт

RenderedMp4Acceptance.ReferenceMontageCard.maximumRepeatedSourceRatio=.02f.
Production VeycadAutomaticEditor Heartbeat card меняет beat min на.90,
FEAR card на.85; оба наследуют maximum repeat.02. evaluate сравнивает
численную метрику с пределом независимо от итоговой acceptance.
repeatedSourceRatio для Heartbeat считает только15не-echo ролей первой
фразы; авторская реприза и black-tail handle не включены. Для FEAR исключён
последний authored white finale. Никакого нового ослабления или штрафа
за разрешённую репризу не вводится.

## Изменение

Только tooling quality_render_report.py и его tests:
HBbeat[.90,1],FEARbeat[.85,1],обаrepeat[0,.02],все значения должны быть
конечными и присутствовать. Отрицательный ratio невозможен по native
coerceIn[0,1] и не должен проходить по одному upper-bound. Сохранены
native flags, resolved audio unknown и обязательная human review.
Независимый звук не исправляет неверные/отсутствующие video metrics.

StockHBfixture теперь содержит first-phrase ratio0 и beat.95. Tests отдельно
проверяют точные inclusive boundaries, реальные v21.029531915,1.0,missing,
NaN/Inf/negative values, ложный acceptance=true и audio-only unknown.
FEAR test проверяет.85/.02boundary,missing/bad metrics и прежние physical
motion/shutter gates. Синтетические aggregation tests не являются реальными
FEAR/DUALITY positive exports. Sigma алгоритм и paused-state не изменялись.

Полный инструментарий:170 Python tests,0failures. Новых монтажей/семантических
декодов на исходниках не запускалось; только read-only повторная оценка
существующих MP4 и независимое декодирование их AAC.

## Фактическая повторная оценка пользовательских экспортов

Существующие raw/MP4/audio/review не изменялись.
v21(A) MP4 SHA `355bff625845adc72511b729d625552bb8d71bd421983610c0bec4207fd6063e`,
raw SHA `4490590160a8ca2afc4e6158f6083f13285e4486335397056c01da226c62c8c1`:
текущий gate дополнительно явно выдаёт repeated_source_ratio0.029531915>0.02,
а не лишь доверяет native repeated-source-moments. Полный pass=false;
исходные native failure/audio unknown и пустая human form сохранены.

v22(A) MP4 SHA `4ba33db0b7d05dfc31147e4a9f3f7bcd0644d467c3d0c0badc163594129c834c`,
raw SHA `252f387fef9a74e626d1cbb2c96ca57554bc00f39ab707deb53a93a15bcb6362`:
actualbeat0.9230769,authoredrepeat0.0;новые самостоятельные checks проходят.
Независимый AAC повторно измерен и совпал с сохранённым report. Остались
только незаполненные человеческие пункты; machine_and_human_pass=false.
Это знакомый калибровочный вход, не независимая приёмка.

Дополнительно пересчитаны реальные authored windows нового inspector:
первые15клипов sum11750мс,pairwiseoverlap0мс;все26визуальных sum19800мс,
pairwiseoverlap7916мс,ratio≈0.39979798. Именно исключение авторской репризы
из audited subset сохраняет корректный0.0, а не отключение проверкиповторов.
Нельзя применять предел0.02 к этой диагностической сумме всех26слотов.

Независимый read-only аудит74focusedtests подтвердил parity границ и
синтетический matrix attack с v21ratio, который снижает HBpositivecount3→2
и блокирует обе readiness даже при подтверждённом unit audio. Отдельный
регрессионный matrix test добавлен в полный170-testпрогон; это fakefixture,
не человеческая подпись и не реальный новый MP4.

Метрика остаётся authored interval overlap. Один boundary VFR texture
timestamp в v22 inspector описан отдельно в v22result; его не называют
нулевым actual-pixel repeat или независимым распознаванием sourceidentity
из encodedMP4pixels. Artistic reprise/readability требуют человека.

APK0.1.2 и облачный0.1.1 не менялись. Прежние baseline/seal не переписываются;
следующий новый baseline должен включить эти tooling-критерии до результата.
Новые лично присланные unused parents и реальные human reviews остаются
необходимыми. Общая цель не завершена и не сужена.
