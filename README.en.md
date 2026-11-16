# HearingAssist · 随声字幕

[简体中文](README.md) | **English**

An Android live-captioning app for deaf and hard-of-hearing people. It turns microphone input into large, readable captions, with optional speaker labels and environmental sound alerts.

**Speech recognition runs on the phone. The default lightweight models are bundled for offline use.** Optional model downloads, version checks, and app updates require a network connection; these operations do not upload recordings or voiceprints.

[Download the latest APK](https://github.com/nakamisama/HearingAssist/releases/latest/download/app-release.apk) · [Release notes](https://github.com/nakamisama/HearingAssist/releases) · [Builds](https://github.com/nakamisama/HearingAssist/actions)

> This is the English documentation. The app interface and primary recognition workflow are currently in Chinese.

## Quick start

1. Download and install `app-release.apk`. If prompted, allow the app used to open the APK to install unknown apps.
2. Open **随声字幕**, tap **点击开始** (Start), and grant microphone permission.
3. On first use, the bundled lightweight models are prepared in local storage. Listening starts automatically afterward; downloading the larger model is not required.
4. Open **设置** (Settings) in the upper-right corner to choose a recognition mode, configure live preview and refresh interval, or manage voiceprints.
5. Tap **点击停止** (Stop) to finish. Switching to another app, returning to the home screen, or locking the phone also stops the session. Returning to the app requires a manual start.

### Device requirements

- Android 8.0 (API 26) or later. The APK includes `arm64-v8a` and `armeabi-v7a` native libraries, not x86 libraries.
- The primary target is the **HONOR X50**. Microphone behavior, recognition speed, and memory use on other Android devices require testing.
- Basic captions use the bundled lightweight models. Optional features require additional storage.

## Features

| Feature | Current behavior |
| --- | --- |
| Real-time mode — default | Uses a bundled lightweight Chinese streaming model for live preview. Without the larger model installed, the streaming model also produces final captions. |
| Accurate mode — optional | Uses a separately downloaded SenseVoice model for segment-based recognition. Accuracy, punctuation, and processing time depend on the audio and device. |
| Simplified Chinese | Recognition output is converted to Simplified Chinese through OpenCC. |
| Live-preview controls | Enabled by default, with a **200 ms** refresh interval. Options: 100 / 200 / 300 / 500 / 1000 ms. Disable preview to show only final captions. |
| Speaker recognition — off by default | Enroll a speaker by reading three prompted sentences and choosing a caption color. Successful matches display the name and corresponding color. |
| Sound alerts — off by default | An optional audio-classification model attempts to recognize sounds such as doorbells and alarms during non-speech intervals, with text and vibration alerts. |
| Keep screen on | Keeps the display awake while listening in the foreground; normal sleep behavior resumes when stopped. Manual screen locking is not prevented. |
| Stop in background | Stops recording and cancels pending work and downloads when the app is no longer visible or the phone is locked. Models are released after in-flight inference returns. Listening does not resume automatically. |
| In-app updates | Checks on launch and offers a manual check in Settings. After confirmation, downloads inside the app and asks the system installer to install the APK. |

> In real-time mode, if SenseVoice is already cached locally, the current implementation also loads it to help produce final captions. This increases memory and processing use, but merely using real-time mode does not automatically download SenseVoice.

## Models and offline use

| Model | Purpose | Availability |
| --- | --- | --- |
| Silero VAD | Speech activity detection and segmentation | Bundled with the APK |
| Streaming Zipformer small CTC Chinese int8 — 2025-04-01 package | Lightweight streaming recognition | Bundled with the APK |
| SenseVoice int8 — 2025-09-09 package | Optional segment recognition and final captions | Downloaded on demand when Accurate mode is selected |
| 3D-Speaker CAM++ Chinese | Speaker matching | Prepared on demand for enrollment or speaker recognition |
| Zipformer small audio tagging | Environmental sound classification | Downloaded on demand when sound alerts are enabled |

- Models are stored in the app's private directory. **Complete caches are reused after an in-place app update.** Valid, unchanged models are not downloaded again.
- Uninstalling the app, clearing app data, or incomplete model files can affect the cache and enrolled voiceprints.
- Required models are prepared before the microphone starts, with progress shown on screen. Recognition works without a network once the required models are ready.
- Dates in package names identify published packages; they should not automatically be treated as training dates. Model file size, APK size, and runtime memory use are different measurements.

### Download and network rules

- **Model downloads:** required models may download automatically on unmetered Wi-Fi or Ethernet. Mobile data or metered connections require two confirmations: first recommending Wi-Fi, then warning about potential data charges.
- **APK downloads:** Wi-Fi only. A network change or moving the app to the background stops the download. The user can retry after returning.
- **Version checks:** fetch version metadata, not the APK, and are not restricted to Wi-Fi.
- Downloads try third-party GitHub mirrors first, then official URLs. These are not a domestic CDN operated by this project, and availability is not guaranteed.
- Updates use **a new APK installed over the existing app**, not silent installation or dynamically downloaded code patches. The system installer may require permission to install unknown apps.

In-place installation can preserve data when the package name, signature, and version requirements match. Early builds used different signatures and may not support in-place upgrades. Uninstalling removes app data.

## Enrolling a speaker

1. Open **设置 → 管理声纹 → 添加说话人** (Settings → Manage voiceprints → Add speaker).
2. Enter a name, choose a caption color, and read the three prompted sentences in a quiet environment.
3. After enrollment, return to Settings and enable speaker recognition.

Start with around four familiar speakers and test on the actual phone. Short utterances, similar voices, overlapping speakers, and changes in recording conditions can cause missed or incorrect matches. Speaker labels are not identity authentication and do not provide separation of simultaneously speaking people.

## Background behavior and data

- Moving from the main screen to voiceprint management is **navigation within the app**, not a background exit by itself.
- Enrollment pauses the previous caption session to avoid competing for the microphone. If the app remains in the foreground, completing or canceling enrollment can restore that session. Going to the background revokes automatic restoration.
- Stopping cancels work and stops recording first, then waits for in-flight native inference to return before releasing models. This avoids accessing released resources.
- Final captions are held in the current process's memory, not saved as permanent conversation history. Voiceprint features and settings are stored in the app's private directory. The app does not automatically save or upload raw recordings.
- Android may retain an idle process and some memory pages. Stopping therefore does not guarantee that the process disappears or reported memory immediately falls to zero.

See [Background stopping and resource ownership](docs/BACKGROUND_STOP.md) for implementation details (Chinese).

## Limitations and security

- **There is no guaranteed caption latency or recognition accuracy.** Dialect, distance, speaking rate, noise, device temperature, and load can all affect results.
- Mandarin Chinese is the primary target. There is no dedicated Sichuan or Chongqing dialect model or accuracy guarantee; automatic language mode does not imply support for every dialect.
- Sound alerts may miss events or produce false positives. They are not a substitute for smoke detectors, emergency alarms, or other safety devices.
- **A historical signing private key was committed to public Git history and must be considered exposed.** The current signature is retained for installation compatibility, not as a security assurance. Key migration and private CI storage require a separate plan. Obtain APKs only from trusted sources.
- Model-cache checks cover source identifiers, file lengths, and archive integrity; they are not authenticated publisher-hash verification.
- Passing CI tests and lint does not mean the app has passed real-device acceptance testing on the HONOR X50 or other phones.

## Development and builds

Stack: **Kotlin, native Android UI, coroutines, AndroidX Lifecycle, sherpa-onnx, and OpenCC**.

The current workflow uses GitHub Actions; a local Android build environment is not required:

1. Check out the code and prepare the JDK, AAR, and bundled lightweight models.
2. Run `testReleaseUnitTest` and `lintRelease`.
3. Run `assembleRelease` only after verification passes, then upload the APK and verification reports.
4. Successful push builds on `main` / `master` publish a Release and update `version.json`.

The current configuration also validates pushes to `fix/background-stop` and uploads artifacts without publishing a release. Manual `workflow_dispatch` runs can build a selected branch but do not publish a release either. Refer to the [actual workflow](.github/workflows/build-apk.yml).

There are currently **17 JUnit tests** covering session permissions, cancellation and resource-release ordering, and isolation of APK download temporary files. They use simulated tasks, not real microphone input or JNI performance measurements.

### Project layout

```text
app/src/main/java/com/hearassist/
├── HearingAssistApplication.kt   # App visibility and screen-lock handling
├── MainActivity.kt               # Captions, settings, in-app updates
├── VoiceprintActivity.kt         # Three-sentence voiceprint enrollment and management
├── audio/                       # Capture, recognizers, speaker models, resource ownership
├── data/                        # Session permissions, shared state, cache, downloads
└── service/CaptionService.kt     # Listening sessions and recognition work
app/src/test/                    # JUnit regression tests
.github/workflows/build-apk.yml   # Cloud verification, build, and release
```

## Reporting issues

Include the phone model, Android / MagicOS version, app build number, recognition mode, and reproduction steps. For recognition problems, provide an example of what was spoken and what appeared on screen. Do not publicly upload recordings or voiceprint data without the participants' consent.

## License and commercial licensing

Unless otherwise noted, this project's original code and documentation are released under the **GNU General Public License v3.0 only**, SPDX identifier **`GPL-3.0-only`**. See [LICENSE](LICENSE) for the complete terms.

You may use, modify, and distribute the covered work under GPL-3.0, including commercial use and distribution for a fee. **Commercial use alone does not require paying this project a license fee.** Distribution of covered programs or modified versions carries applicable obligations, including providing Corresponding Source and preserving license and copyright notices. The software comes without warranty; the terms in LICENSE govern.

If you need to integrate or distribute the project's original code in a proprietary product without complying with the applicable GPL conditions, contact the maintainer to discuss a separate paid commercial license. Such permission takes effect only through a separate written agreement with parties entitled to grant it; it is not an additional restriction on GPL users. See [Commercial licensing](COMMERCIAL_LICENSE.md).

Third-party libraries, model weights, and their derivatives retain their respective licenses; they are not all relicensed under the GPL. Sources and unresolved items are listed in [Third-party notices](THIRD_PARTY_NOTICES.md). That inventory is not a declaration that licensing compatibility of the entire APK has been fully cleared.

When distributing an APK, provide the complete Corresponding Source for that version and the necessary build materials, along with relevant licenses. Start with the matching release tag / commit when checking completeness; a moving main branch is not a substitute for matching source. These new notices do not automatically rewrite notices inside previously published APKs.

## Related projects

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [SenseVoice](https://github.com/FunAudioLLM/SenseVoice)
- [3D-Speaker](https://github.com/modelscope/3D-Speaker)
- [opencc4j](https://github.com/houbb/opencc4j)

Use and redistribution of third-party code and models must comply with their respective licenses.
