# 后台停止与资源释放

## 行为

- App 不可见或锁屏后停止麦克风、字幕服务、声纹录入、模型准备及 APK 下载；回前台保持停止，需要再次点“点击开始”。
- 主界面进入声纹管理属于 App 内跳转；若始终前台，录入结束/取消后可以恢复原来的字幕收听。
- Application 的 Activity 可见计数处理快速切换，进程生命周期作兜底；锁屏接收 SCREEN_OFF。
- 不使用 killProcess、System.exit 或强制 GC。Android 可能保留空闲进程及部分页面，释放模型不等于进程内存瞬间归零。

## 资源所有权

- SessionPolicy 的前台代际和操作序号撤销过期启动、结果、确认与恢复请求。
- CaptionService 返回 START_NOT_STICKY；模型只有获得许可的会话可以加载。
- ExclusiveAudioSession 在新录音会话前等待旧会话及其 child jobs 结束；所有 native close 在推理返回后执行。
- 停止先请求取消及 AudioRecord.stop；任务 finally 负责唯一释放 AudioRecord/AGC/模型，不再猜测 500/800ms 足够。
- 定稿队列有界。积压时给出跳段提示，而不是无限持有音频。
- 模型准备使用临时目录，完整后原子发布缓存指针；取消或网络变化不把未完成内容作为有效缓存。旧缓存可迁移而不重新下载。
- 下载源是第三方 GitHub 镜像及官方回退，并非本项目自有的国内 CDN；可用性不保证。

## 验证状态

新增 JUnit：SessionPolicyTest 10 项、ExclusiveAudioSessionTest 4 项。测试使用假的推理任务验证许可、取消、close 与下一会话顺序，不代表真实 JNI/设备测量。

GitHub Actions 顺序执行 `testReleaseUnitTest lintRelease` → `assembleRelease`。工作分支只产出 artifact，main/master 才发布 Release；没有本地 Android 构建或模拟器运行。

真机应覆盖：
1. 开始加载期间按 Home，回来不继续加载/收听，点击开始能重新开始。
2. 连续转写中切后台/锁屏，通知与麦克风停止；回到 App 不自动恢复。
3. 切后台后马上返回；设置触发重启时切后台；手动停止后旧任务不得复活。
4. 进入声纹管理、三句录入、取消后返回主页；真正后台后录入不继续，原声纹不丢。
5. 下载模型/APK时后台取消；重新开始不将半截模型当成可用。WiFi切流量不得静默续下。
6. 反复开始/停止，确保无额外录音实例、native 崩溃或无界队列。

## 尚需另行处理的安全事项

现有签名私钥曾入公开 Git 历史；不能视为安全。本次保持原签名以兼容覆盖安装，不擅自改签名或重写历史。后续需单独制定签名迁移、私密CI保管和下载制品验证方案。模型缓存当前校验来源标识、尺寸及归档完整性，不是可信发布方哈希认证。
