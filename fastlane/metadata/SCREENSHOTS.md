# Screenshots

F-Droid shows the PNG or JPEG files in `metadata/android/<locale>/images/phoneScreenshots/`
in name order. Languages without their own folder use the `en-US` ones, which are also
shown in the README.

| File | Shows |
| --- | --- |
| `1_editor.jpg` | Editor with the command palette |
| `2_physics_3d.jpg` | 3D physics example (raylib + Box3D) |
| `3_new_project.jpg` | New project: templates |
| `4_examples.jpg` | Example projects and build options |
| `5_export_apk.jpg` | Export as APK or App Bundle |
| `6_packages.jpg` | Packages |
| `7_themes.jpg` | App and editor themes |
| `8_settings.jpg` | Settings |

Before adding a capture, remove its metadata (EXIF: date, device and Android build) and
check that it shows no personal data. A lossless way for JPEG files:

```bash
exiftool -all= -tagsfromfile @ -icc_profile -overwrite_original capture.jpg
```

Useful additions: a graphical program running full screen (SDL3 or Vulkan), clangd
completion and live errors in the editor, the asset viewer with a 3D model, and tablet
captures of the split editor in `tenInchScreenshots/`.
