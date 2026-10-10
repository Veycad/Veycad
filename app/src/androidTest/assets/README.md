# Synthetic music fixture

`synthetic-120-bpm.mp3` is generated test audio, without recorded music or user content.
It has a 2-second silent intro followed by 100 Hz decaying pulses every 0.5 seconds,
at 16 kHz mono, for 16 seconds total. The samples follow `SyntheticAudio.writeWave`.
Encoded with FFmpeg/libmp3lame at 32 kbit/s, with source metadata removed.
Tests use it to check Android MP3 decoding, seeking, local BPM and downbeat analysis.
