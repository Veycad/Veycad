# AutoEdit Effects Lab

Two original fragment shaders for mobile portrait edits. They are source assets, not copied stock effects and do not require a third-party runtime or licence.

## Directional Whip

`directional_whip.frag` creates a short side-to-side transition from a nine-tap directional blur with a restrained RGB split. It is active for 160–240 ms after a cut.

Recommended values:

- `uProgress`: `1.0 → 0.0` with an ease-out curve;
- `uDirection`: alternate `(1, 0)` and `(-1, 0)` for consecutive cuts;
- `uStrength`: `0.07` for a normal cut, `0.11` for Aura/Noir.

## Velocity Blur

`velocity_blur.frag` creates an eleven-tap blur along `uVelocity`. It stays fully sharp if detected motion is close to zero. The direction comes from local face-position deltas already captured by `MotionSample`.

Recommended values:

- `uVelocity`: normalised frame-to-frame face movement;
- `uAmount`: `0.35–0.8`, clamped for mobile performance;
- active only around cuts, never across the whole exported shot.

## Android integration target

These shaders belong to the Veycad GLES backend. A future preview must consume the same
`HighQualityFramePlan` and shader parameters as export; Media3 is intentionally outside the
reboot architecture.
