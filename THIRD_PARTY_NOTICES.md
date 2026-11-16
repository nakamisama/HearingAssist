# 第三方许可清单 / Third-party notices

本清单区分应用代码、第三方依赖和模型权重。项目的 [GPL-3.0-only](LICENSE) 声明不会覆盖第三方原有权利和条款，也不是所有组件均已完成法律审查的保证。分发者应保留所用**具体版本**的许可、版权及适用的 NOTICE；仅提供下列链接不一定满足二进制分发要求。

This inventory separates project code, third-party dependencies, and model weights. The project's [GPL-3.0-only](LICENSE) declaration does not replace third-party rights or terms, nor does this file establish complete legal clearance. Preserve the license, copyright, and applicable NOTICE material for the **exact versions** you distribute; links alone may not satisfy binary-distribution obligations.

## 代码及构建依赖 / Code and build dependencies

| 组件 / Component | 当前版本 / Version | 许可 / License | 来源 / Source |
| --- | --- | --- | --- |
| sherpa-onnx | 2.0.1 AAR | Apache-2.0（代码 / code） | [LICENSE](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE) |
| AndroidX core-ktx | 1.13.1 | Apache-2.0；保留组件随附声明 / retain component notices | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| AndroidX appcompat | 1.7.0 | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| AndroidX lifecycle-runtime-ktx / lifecycle-process | 2.8.4 | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Material Components for Android | 1.12.0 | Apache-2.0 | [项目 / Project](https://github.com/material-components/material-components-android) |
| kotlinx.coroutines（android / test） | 1.8.1 | Apache-2.0 | [项目 / Project](https://github.com/Kotlin/kotlinx.coroutines) |
| opencc4j | 1.8.0 | Apache-2.0（项目代码 / project code） | [LICENSE.txt](https://github.com/houbb/opencc4j/blob/master/LICENSE.txt) |
| Apache Commons Compress | 1.26.1 | Apache-2.0 | [项目 / Project](https://commons.apache.org/proper/commons-compress/) |
| JUnit（测试依赖 / test dependency） | 4.13.2 | EPL-1.0 | [项目 / Project](https://github.com/junit-team/junit4) |
| Gradle Wrapper | Gradle 8.7 | Apache-2.0；脚本中已有原声明 / existing notices in wrapper scripts | [项目 / Project](https://github.com/gradle/gradle) |

这不是传递依赖的完整 SBOM。AAR 内的 ONNX Runtime、原生库以及 Maven 传递依赖可能带有额外声明，发布前需要核对实际构建产物。JUnit 为测试用途，不因列在这里就表示它被打包进 APK。不能把 sherpa-onnx 的代码许可当作其所有模型的许可。

This is not a complete transitive-dependency SBOM. ONNX Runtime and other native code inside the AAR, as well as Maven transitive dependencies, may carry additional notices that must be checked against the built artifact. JUnit is a test dependency; its inclusion here does not mean it ships in the APK. The sherpa-onnx code license must not be assumed to license every model it hosts.

## 模型权重 / Model weights

包名日期是发布包标识，不一定是训练日期。权重格式转换也不会自动改变上游许可。以下是已查到的来源信息及尚未核实的范围，不是新的授权授予。

Dates identify packages and are not necessarily training dates. Format conversion does not automatically change an upstream license. The following records identify sources and unresolved scope; they do not grant new permissions.

| 使用的模型 / Model used | 获取方式 / Delivery | 授权依据与核实状态 / Licensing evidence and status |
| --- | --- | --- |
| Silero VAD (`silero_vad.onnx`) | APK 内置 / Bundled | [上游 LICENSE](https://github.com/snakers4/silero-vad/blob/master/LICENSE) 为 MIT；分发具体导出文件时仍应保存其适用声明。 / Upstream specifies MIT; preserve notices applicable to the actual exported file. |
| `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01` | APK 内置 / Bundled | [原始 checkpoint 模型卡](https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01) 标注 `apache-2.0`；[官方导出文档](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/pretrained_models/online-ctc/zipformer-ctc-models.rst) 提供来源关联。 / Original checkpoint card declares Apache-2.0; export provenance is documented separately. |
| `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09` | 可选下载 / Optional download | [SenseVoiceSmall 模型卡](https://huggingface.co/FunAudioLLM/SenseVoiceSmall) 标注 `license: other`，链接 [FunASR MODEL_LICENSE](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)。**自定义模型协议，非标准 GPL/Apache；分发、商用及与本项目组合的适用性须核实。** / Custom model agreement, not standard GPL/Apache; distribution, commercial rights, and compatibility of the intended combination require verification. |
| `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | 可选下载 / Optional download | [3D-Speaker 代码](https://github.com/modelscope/3D-Speaker) 使用 Apache-2.0，但[具体模型页面](https://modelscope.cn/models/iic/speech_campplus_sv_zh-cn_16k-common)的权重授权尚未完整核实。 / Apache-2.0 for repository code is not sufficient evidence for the exact weight file; weight terms remain to be verified. |
| `sherpa-onnx-zipformer-small-audio-tagging-2024-04-15` | 可选下载 / Optional download | [官方资产](https://github.com/k2-fsa/sherpa-onnx/releases/tag/audio-tagging-models)及[训练来源](https://github.com/k2-fsa/icefall/pull/1421)可查，但本轮未确认明确覆盖该权重文件的许可声明。 / Assets and training provenance are available; a grant explicitly covering the distributed weights has not been confirmed. |

### 模型协议注意事项 / Model-agreement notes

- SenseVoiceSmall 模型卡所链接的 FunASR Model Open Source License Agreement 1.1 有来源和作者归属、模型名称保留、“参考和学习用途”措辞、社区行为相关许可终止及协议更新条款。不要将其简化成 Apache-2.0 或宣称无限制商用。应保存适用于具体权重版本的条款，并向上游确认不明确之处。
- 把模型改成用户自行下载不会自动免除使用和分发责任；GPL 与额外限制如不能同时满足，应停止相应组合的分发，先解决授权问题。
- 第三方模型的授权不会因为用户购买本项目的商业授权而改变。

- The linked FunASR Model Open Source License Agreement 1.1 includes source/author attribution, model-name retention, reference-and-learning wording, conduct-related termination, and revision terms. Do not describe it as Apache-2.0 or unrestricted commercial permission. Preserve the agreement applicable to the exact weights and clarify ambiguities with the upstream rights holder.
- User-initiated downloads do not automatically remove licensing obligations. If GPL obligations and additional restrictions cannot both be met, do not distribute the affected combination until the rights issue is resolved.
- Purchasing a commercial license for this project's code does not change any third-party model license.

## 发布前检查 / Before distributing

1. 保留 GPL 及全部适用第三方许可、版权和 NOTICE 文本；核对 AAR 与传递依赖。 / Include GPL and all applicable third-party license, copyright, and NOTICE texts; review the AAR and transitive dependencies.
2. 提供与 APK 对应的完整相应源代码及必要构建材料；不能只指向持续变化的 main。 / Provide complete Corresponding Source and build materials matching the APK, not just a moving main branch.
3. 明确每个模型权重的许可与使用范围；未核实的权重不要宣称已获得商业分发许可。 / Establish the terms for each model; do not claim commercial redistribution rights for unverified weights.
4. 不将第三方代码中的版权或许可头替换成本项目 GPL 声明。 / Do not replace third-party copyright or license headers with this project's GPL notice.
5. 保存发布时使用的许可证版本和来源证据；上游链接内容可能变化。 / Keep the license versions and provenance evidence used for each release; upstream links may change.
