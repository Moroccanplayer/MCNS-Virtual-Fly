# MCNS Virtual Fly 0.2 — Android prototype

A phone-first 2D fruit-fly experiment app that can load the existing `MCNS_30000_MERGED.npz` and use the weighted edge list in a toy neural-activity simulation.

## This archive is source, not an APK

This workspace has no Android SDK/Gradle Android plugin cache and outbound downloads are disabled. That means an APK could not be compiled and install-tested here. The included GitHub Actions workflow builds a signed-by-Android-Gradle debug APK once the project is pushed to a GitHub repository. The resulting artifact is `app-debug.apk`; it is an installable debug build but not a Play Store release.

## Features in this version

- **World:** low-cost 2D fly arena, tap-to-touch input, moveable food target, visual looming pattern, slow/normal/fast visualization, manual nudge, pause and reset.
- **Sensory bench:** visual flash, looping looming pulses, touch, sound proxy, cold, heat, nociception-like abstract stimulus, balance, velocity and central stimulation.
- **Brain view:** live coarse activity heatmap, neuron/edge counts, active-neuron count, single-neuron inspect/pulse/silence controls, model controls (threshold, leak, gain), simulation speed, and edge-propagation/motor toggles.
- **Experiment lab:** repeatable presets for visual, looming, touch, sound, temperature, balance/self-motion, motor silence, edge blocking, central stimulus, recovery, baseline, and randomized probe, plus an explicit PPGA bridge-status panel (it explains that live PPGA integration is not connected in this build).
- **Export:** save the event/session log as CSV through Android's document picker.
- **Import:** Android document picker loads NPZ without broad storage permissions. The loader expects `edge_pre`, `edge_post`, and `edge_weight`, with optional `body_id`, `visual_mask`, `central_mask`, `motor_mask`, `somaSide`/`rootSide`, and `subclass`.

## Android compatibility

- `minSdk = 23`; compatible in API terms with Android 15 / Galaxy A14.
- `targetSdk = 28` for a conservative sideload target. This does not guarantee behavior across all vendor firmware.
- Pure Kotlin/Android UI, no native libraries or CPU-specific binaries.
- The current build workflow compiles with Android SDK Platform 35, Android Gradle Plugin 8.7.3, Gradle 8.9, and JDK 17.

## Build the APK using GitHub Actions

This is the no-local-SDK route, but it does require a GitHub repository and running its Actions workflow.

1. Create a repository on GitHub.
2. Upload/commit the contents of this project (the `settings.gradle.kts` file must sit at repository root; don't upload the ZIP as a single file).
3. Open the repository's **Actions** tab and enable Actions if prompted.
4. Select **Build MCNS Virtual Fly APK** and press **Run workflow**. A push to `main` or `master` also runs it.
5. When the workflow succeeds, open its run and download the artifact named **MCNS-Virtual-Fly-debug-APK**.
6. Extract `app-debug.apk` on the phone, tap it, and allow installation from that file source if Android asks.
7. Open the app → **LOAD CONNECTOME** → select `/storage/emulated/0/Download/Fruitfly/MCNS_30000_MERGED.npz`.

If you have Android Studio instead, open this project, install SDK Platform 35, sync Gradle, and run `gradle :app:assembleDebug` (or use Android Studio's Build APK action). Output: `app/build/outputs/apk/debug/app-debug.apk`.

## NPZ endpoint mapping and memory

The importer supports two edge conventions:

1. Zero-based row indices, if all endpoints fall inside `0..N-1`.
2. External neuron body IDs, if the archive contains `body_id.npy` so it can map IDs to row indices.

If only some IDs match, loading stops with a diagnostic instead of quietly wiring most edges to the wrong neurons. Masks and side labels are expected to be aligned to the `body_id` row order. NumPy object/pickle arrays and Fortran-ordered arrays are not supported by this lightweight importer.

A 4.4M-edge graph uses substantial memory even when stored sparsely. The importer reads one ZIP member at a time, stores integer row indices and float weights for adjacency construction, and limits per-tick edge work to help prevent an activity avalanche from freezing the UI. Keep other heavy apps closed during import on a 4 GB RAM phone.

## Scientific limitations — important

This is an **experimental toy model, not a biologically validated Drosophila brain**. It uses a leaky threshold rule. The NPZ's wiring/weights alone do not provide validated membrane constants, synapse signs, neurotransmitter effects, receptor dynamics, muscle mechanics or sensory transduction. Weights are treated as nonnegative because sign/type data are not wired into this prototype.

- `visual_mask` is used when available.
- Touch/sound/temperature/balance/velocity/nociception buttons use stable proxy neuron groups unless a suitable modality-specific mask is added. They are **not** evidence of the real circuit for those senses.
- Left/right motor control is mapped only if recognizable `somaSide`/`rootSide` labels are present. Without them, the fly's direction includes a small visualisation fallback and is not a real left/right motor decoding.
- This prototype has no PPGA/People Playground connection. A connectome ZIP does not by itself create a working game mod or native sample.

Treat activity and movement as exploratory software behavior, not a prediction of a living fly.
