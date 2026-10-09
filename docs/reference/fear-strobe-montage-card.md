# FEAR montage card

## Rights and scope

The project owner supplied the reference `ssstik.io_@kum.fx_1789469507014.mp4` and confirmed that
the montage style may be reproduced one-to-one and improved. The embedded score may also be used.
That permission does not grant rights to redistribute the film footage visible in the reference;
FEAR renders only user-selected source media.

## Measured reference

- Container: 576 × 576, 30 fps, 18.319 s.
- Decoded visual schedule: 549 frames, 18.300 s (`0` through `18.266667` s).
- Author track SHA-256:
  `2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
- Source reference SHA-256:
  `881d308b23fb6afb001beaee95dcbfde3e269855e1446f8cfec426bbc7c2e471`.
- Product output is square; the default 720 px width therefore renders as 720 × 720.

## Choreography

1. `0.000–3.600`: continuous opener at approximately native source speed, with source-native
   foreground occlusion or, when unavailable, the strongest source motion. Never stretch a
   one-second candidate across this entire interval. Keep a small authored zoom and short
   chromatic/lens accents over a cool, textured grade so a quiet source does not feel static.
2. `3.600–5.100`: clean face close-up and the large centred `FEAR` cue. Opacity grows in two
   measured stages and holds fully visible from 4.500 s.
3. `5.100–10.000`: short hard-cut portrait cascade.
4. `10.000–10.967`: first shutter phrase; black on every other 30 fps frame (15 black frames).
5. `10.967–15.867`: second portrait cascade.
6. `15.867–16.833`: second shutter phrase; black on every other frame (15 black frames).
7. Frames `505–509`: `white, white, black, white, white`.
8. `17.000–18.300`: terminal black tail while the authored audio completes.

All scene changes are hard cuts. Phrase-entry defocus, short end-of-shot zoom punches and
selected chromatic accents decorate the cascade but never alter the cut clock. Full-frame
measured steps bypass post effects so black and
white frames remain exact. The style uses no masks or segmentation.

## Source direction

The director reserves separate source windows for opener and title close-up, splits the remaining
selected windows into non-overlapping moments, and orders the cascade by farthest-neighbour
variation across subject scale, face pose/gesture and motion. Sixteen selected windows provide the
exact 27-moment cascade. With less coverage, the same authored output clock remains intact and the
graph generator explicitly ends in `:adaptive`.

## Acceptance

- exactly 549 scheduled and encoded frames;
- duration error and A/V drift no more than one 30 fps frame;
- 30/30 decoded black shutter frames across the two measured windows;
- decoded `WW/B/WW` finale;
- no decoded black frame outside an authored black interval;
- hard-cut boundaries aligned to beat or onset events within 85 ms;
- no overlap between visible source ranges in exact mode;
- face evidence retained within the FEAR-specific decoded tolerance;
- review the exported MP4 at 1× and 0.5× and inspect the frame-pair layer sheet.

The three-source device acceptance run is recorded in
[`fear-strobe-acceptance-2026-09-15.md`](fear-strobe-acceptance-2026-09-15.md).
