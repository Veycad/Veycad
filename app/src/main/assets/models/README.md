# Local perception models

`selfie_multiclass_256x256.tflite` is Google's MediaPipe SelfieMulticlass model. It predicts
background, hair, body skin, face skin, clothing, and accessories. It is evaluated entirely
on-device by `MulticlassMatteProvider` in a private, separate application process.
The provider is part of the production manifest; debug activities only expose
local test entry points.

- Source: `https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite`
- Model card: `https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Multiclass%20Segmentation.pdf`
- License: Apache License 2.0
- SHA-256: `c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0`
