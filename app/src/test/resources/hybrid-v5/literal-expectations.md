# Literal v5 capture inputs and expectations
Project=project; schema=1; fps=30; original=0; current=6; nextRevisionId=7; undo=0,1,2,3,4,5; redo=empty.
Video UTF-8 bytes="A2t-v5 synthetic video bytes\n"; audio="A2t-v5 synthetic audio bytes\n"; each length=29.
Video SHA256=588f04ca0b7c6725771dd73d2bd7fa15de84ed1660793b4a5e7ce85910b2aa1f; audio SHA256=43e74c725c738e1b3c99eed0a69540f7251d2bbd612e2f03b6a7e50d119f9305. The \n denotes one literal LF byte.
Selection=capture-selection/Synthetic v5 selection/IMPORTED; geometry=1080x1920, rotation=0, PAR=1;
mime=video/avc, colorTransfer=3, hasAudio=true; declared=3000000; firstVideoPtsUs=120000; videoEndPtsUs=3000000.
Both physical endpoints are unversioned facts. v5 has no recognized timing proof field.
Three sourceIndex=0 clips: left[0,30), right[30,60), last[60,90); original maps=(0,500000),(30,1500000).
r1 moveCut(right,28): right span[28,60), phase=-2, requests frame0=433333, frame2=500000.
r2 slipClip(right,123456): phase=-2, frame0=556789, frame2=623456, boundary32=1623456;
right backing phaseStart=0, selectedRevisionId=0, slipUs=123456, automatic=true.
Export references r2 as synthetic-old-export.mp4 (reference only; no media output), 1080x1920/30fps/1000+100 bitrate.
r3 restoreAutomatic: resetEvent=true; right[30,60), phase=0, firstRequest=500000;
all backings automatic=true, selectedRevisionId=3, slipUs=0.
r4 explicit right map=(0,300000),(10,600000),(30,1500000): selectedRevisionId=4, automatic=false.
r5 moveCut(right,42): right[42,60), phase=12, firstRequest=690000, selected authored backing retained.
r6 slipClip(right,123456): right[42,60), phase=12; frame0=813456, frame5=1038456, boundary18=1623456;
right backing phaseStart=0, slipUs=123456, selectedRevisionId=4, automatic=false;
hidden backing frame0=300000, frame10=600000, boundary30=1500000. Current resetEvent=false.
All seven revisions published through the real store. All old manifests, map hashes, source bytes,
revision blobs, CURRENT and its complete referenced manifest are retained unchanged inside store.zip.
No analysis planes, actual inspection, recognition version, Gallery admission, native EOS or A2b acceptance.
