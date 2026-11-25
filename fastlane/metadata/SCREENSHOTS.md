# Screenshots

F-Droid shows the PNG or JPEG files in `metadata/android/<locale>/images/phoneScreenshots/`
in name order. Languages without their own folder use the `en-US` ones, which are also
shown in the README.

| File | Shows |
| --- | --- |
| `1_editor.jpg` | Editor with clangd, shortcut and symbol bars |
| `2_projects.jpg` | Recent projects with language, build system and libraries |
| `3_new_project.jpg` | New project: console and graphics templates |
| `4_new_project_options.jpg` | New project: examples, language, standard and build system |
| `5_project_libraries.jpg` | Project libraries |
| `6_packages.jpg` | Packages: Android and Termux libraries |
| `7_model_viewer.jpg` | Asset viewer: glTF 3D model preview |
| `8_export.jpg` | Export: app name, package id, version, icon and display |
| `9_export_signing.jpg` | Export: permissions, APK or App Bundle, signing key and WebAssembly |

The README also shows two captures of the remote IDE in a computer's browser, kept in
`docs/screenshots/` because F-Droid expects phone captures here. Crop the browser's
address bar from those so no local IP address is published.

Before adding a capture, remove its metadata (EXIF: date, device and Android build) and
check that it shows no personal data. A lossless way for JPEG files:

```bash
exiftool -all= -tagsfromfile @ -icc_profile -overwrite_original capture.jpg
```

Useful additions: a graphical program running full screen on the phone (SDL3 or
Vulkan), clangd completion and live errors in the editor, and tablet captures of the
split editor in `tenInchScreenshots/`.
