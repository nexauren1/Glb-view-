# GLB View

A lightweight-first Android 3D model viewer and batch utility, built with Kotlin, Jetpack Compose and SceneView/Filament.

**Languages:** Portuguese and English (in-app toggle).

## First implementation

- Open GLB and glTF files from Android's document picker or from another app's Share/Open action.
- Import many files at once.
- Recursively scan a folder and its subfolders using Android's Storage Access Framework; no broad storage permission.
- Keep a local library of URI references and basic file metadata.
- Search and multi-select models.
- View one 3D model at a time with touch orbit/zoom controls and model animation support.
- Share one or many source files with compatible apps.
- Batch-inspect GLB headers and JSON chunks; count meshes, nodes, materials and animations when the JSON is available.
- Export inspection results to CSV.
- PT/EN interface toggle saved on-device.

The library stores references to user-selected files; it does not duplicate all source files inside app storage. Only the active model is passed to the 3D renderer, avoiding unnecessary simultaneous GPU allocations.

## Build

Requirements:
- JDK 21
- Android SDK Platform 37
- Android Gradle Plugin 9.4.0 / Gradle 9.6.0
- Android Studio with support for the above toolchain

Open the repository folder in Android Studio and sync Gradle. To build from a shell with Gradle 9.6.0 installed:

\`\`\`bash
gradle :app:assembleDebug
\`\`\`

The 3D renderer is the main native dependency. Release builds enable R8 code shrinking and resource shrinking. Actual APK size and performance still need to be measured on target devices.

## Notes / current scope

- This is the initial app foundation, not a final release.
- The validator checks GLB v2 structure and the first JSON chunk. For glTF JSON files, it checks JSON structure only and does not validate external buffers/textures.
- Model viewing depends on the format and complexity of each asset and the device GPU/RAM.
- Folder scans run off the main thread; very large files are not all opened in the renderer at once.
