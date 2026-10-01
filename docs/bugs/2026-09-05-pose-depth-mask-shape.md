# Pose-depth rejects non-square external masks

Code inspection during the three-source reveal regression found that
`LocalSemanticFrameAnalyzer` constructs its pose-derived depth plane with constant
256x256 dimensions but allocates `maskValues.size` values. MediaPipe external masks
preserve source aspect ratio, e.g. 270x480. If pose detection succeeds, the plane
constructor rejects these dimensions; the caller catches the exception and loses
the complete semantic result for that frame.

Status: corrected by deriving the depth plane dimensions from the actual mask.
The branch regression covers portrait 270x480, landscape 480x270 and legacy 256x256
planes, checks depth values and ensures the original alpha array is not modified.
Runtime frequency and visual impact on the three Golden sources remain unproven.
Do not attribute missing semantic frames to this branch without runtime evidence.
