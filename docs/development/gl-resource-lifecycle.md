# GLES resource ownership

`effects-lab` contains shader source assets, not a separate screen renderer.
The GLES export backend lives in `MediaCodecSpeedRampRenderer.GlSession`;
analysis uses `SequentialBitmapDecoder.GlOutput`.

An export belongs to `EditSession` and can continue while its Activity is paused
or recreated. Releasing its GL context from Activity.onPause would interrupt a
valid background export. The render worker owns cleanup on completion, failure,
or cancellation. Sequential analysis similarly releases its session in finally.

Both GL sessions make their own context current before deleting programs,
textures and framebuffers, unbind the program and EGL context, then destroy
surfaces and contexts. Release is idempotent and constructor failures clean up
partially initialized sessions. Cleanup of codec, input Surface and muxer is
independent so an exception from one owner does not skip the others.

`GlProgramOwnership` deletes temporary shaders on compile/link failure and after
success. `GlesProgram` detaches linked shaders before deleting them so their
storage can be reclaimed immediately. It returns only the successfully linked
program to the session.

JVM regressions cover both compile stages, link failure and successful transfer.
`GlProgramLifecycleTest` checks real GLES handles through 50 create/delete cycles
and invalid shader compilation in a pbuffer context. Run it on an emulator or
device with the uiTest instrumentation variant.

Before accepting device memory behavior, repeat export/cancellation and screen
pause/resume/recreation on a physical device, check EGL/GLES errors and compare
graphics memory after warmup. Unit tests and APK compilation do not establish
device VRAM stability or visual MP4 acceptance.
