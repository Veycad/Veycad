# FEAR reference recovery — 2026-09-24

## Result

`TikVideo.App_7487309285538450710-hd.mp4` is the same authored FEAR montage as the previously
measured reference, delivered as a higher-resolution TikTok transcode. It is accepted as the local
visual reference for further FEAR work. The existing authorised audio asset remains the render
source because the transcode's extracted audio bytes are not identical to the preserved author
track.

## Identity evidence

- Candidate SHA-256: `90d7719b801702e3c52fd1b9a3fc44d6c08c180edc328192d671a4569048dade`.
- Candidate container: 1080 × 1080, 18.367 s, 44.1 kHz stereo audio.
- Previous reference container: 576 × 576, 18.319 s.
- The emulator-decoded contact sheet contains the same opener, centred `FEAR` title, portrait
  cascade, alternating black shutter phrases and terminal black tail.
- The two defining cut boundaries are preserved exactly at 3.600 s and 5.100 s.
- The emulator detector measured 21 hard cuts and 32 visual-change peaks. The cascade cut clock
  continues at 5.600, 6.000, 6.600, 8.100, 8.600, 9.100, 9.600, 10.400, 11.000, 11.500,
  11.900, 12.500, 13.900, 14.400, 14.900, 15.400, 16.000 and 16.500 s.
- The extracted transcode audio SHA-256 is
  `94aeddcb687f0bacddff0c31319066286828646011cbb538e1d6f45e127b2c44`.
- The preserved author-track SHA-256 remains
  `2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.

## Local evidence

The private, ignored artifacts are stored under `artifacts/fear/reference/`:

- `TikVideo.App_7487309285538450710-hd.mp4` — recovered HD visual reference;
- `reference-analysis.json` — full Android-emulator analysis;
- `contact.jpg` — decoded 200 ms contact sheet.

The analysis was produced by the debug `ReferenceAnalysisActivity` on an Android 16 / API 36
x86_64 emulator. No reference footage is packaged into the application.
