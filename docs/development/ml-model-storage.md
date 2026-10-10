# ML models in Git LFS

Install Git LFS before cloning, then run `git lfs install` and `git lfs pull` in
the checkout. Tracked model formats are `.tflite`, `.onnx`, and `.task`.
Both Android CI workflows fetch LFS objects at checkout.

The segmentation model remains bundled in the APK and runs offline. Git LFS
changes repository delivery, not APK size or runtime model loading. Downloading
the repository as a ZIP may yield pointers; use a Git clone with LFS instead.

For an existing checkout after pulling this change, run:

```shell
git lfs install
git lfs pull
git lfs fsck
```

When updating a model, add its file normally and verify `git lfs ls-files` and
`git lfs fsck` before committing. The pre-push hook uploads the model object.
Do not overwrite the LFS pre-push hook with another hook.

The existing model was re-added through LFS without rewriting published history.
Older commits still contain the original binary. The unchanged bundled model is
16,371,837 bytes with SHA-256
`c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0`.

See [GitHub LFS configuration](https://docs.github.com/en/repositories/working-with-files/managing-large-files/configuring-git-large-file-storage).
