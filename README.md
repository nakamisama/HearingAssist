# 随声字幕 HearingAssist

给听障人士的离线实时字幕 App：把周围人说的话实时转成文字显示在屏幕上，可选识别门铃、警报、婴儿哭声等环境音并振动提醒。

**目标机型：中端 Android 机均可，100% 离线运行，不联网、不上传任何音频。**

## 功能

| 功能 | 说明 |
|------|------|
| 实时字幕 | 软/硬件 AGC 增益 → Silero VAD 切分语音 → SenseVoice-small(int8) 离线识别 → OpenCC 转简体 → 大字号屏幕显示 |
| 方言支持 | 普通话 / 自动识别双模式；川渝话（西南官话）与普通话接近，可识别大部分内容 |
| 环境音识别 | 可开关（默认关闭）。检测门铃/警报/婴儿哭声等约 60 类声音，振动 + 高亮提示 |
| 隐私 | 所有推理在手机本地完成，无任何网络请求 |

## 架构

```
AudioRecord(16kHz/mono, AGC) → VAD(Silero, 0.6MB)
                             ├─ 语音段 → SenseVoice-small(int8) → 繁转简 → 字幕（大字显示）
                             └─ 非语音（开关开启时）→ Zipformer 音频打标 → 振动+提示
```

- 引擎：[sherpa-onnx v2.0.1](https://github.com/k2-fsa/sherpa-onnx)（一个 AAR 同时提供 VAD / STT / 音频打标）
- 模型在 CI 构建时自动下载并打进 APK，APK 约 300MB
- 中文识别：SenseVoice-small（专为中文训练，自带标点），输出经 OpenCC 统一为简体

## 如何获得 APK（不需要本地安装任何开发环境）

1. 代码推送到 GitHub 后，Actions 自动构建
2. 构建完成后，在仓库 **Releases** 页面下载 `app-release.apk`
3. 传到手机安装（设置里允许"安装未知来源应用"）
4. 打开 App → 授权麦克风 → 点"开始收听"

也可以在 GitHub 仓库的 **Actions** 页面点 "Run workflow" 手动触发构建。

## 项目结构

```
app/src/main/java/com/hearassist/
├── MainActivity.kt              # 界面：字幕屏 + 环境音开关
├── data/CaptionBus.kt           # 服务↔界面共享状态
├── audio/VadProcessor.kt        # Silero VAD 封装
├── audio/SttProcessor.kt        # SenseVoice 离线识别封装（普通话/自动模式）
├── audio/EnvSoundProcessor.kt   # 环境音分类 + 中文标签映射
├── data/Settings.kt             # 偏好存储（识别语言）
└── service/CaptionService.kt    # 前台服务（音频循环总调度）
```

## 后期路线

- [ ] 声纹识别（识别"是谁在说话"，字幕前加说话人名字）
- [ ] 说话人分离（多人对话自动区分）
- [ ] 更大模型可选（Whisper small，识别更准、更耗电）
- [ ] 字幕翻译（对方说方言/外语时显示普通话）
