# KillBill 记账

悬浮球截屏实时记账 Android App。点一下飘在屏幕上的悬浮球，自动截屏 → Qwen-VL 识别账单 → 悬浮卡片确认 → 入库统计。

## 功能

- **悬浮球**：常驻屏幕边缘，可拖动贴边，点击即截屏记账
- **三级识别链路**：① 本地 ML Kit 中文 OCR（内置模型，无 GMS 也能用，~0.3s）+ 本地规则解析——微信/支付宝/滴滴/银行动账等标准页面**零网络秒出卡片**；② 规则没把握 → OCR 文本送千问（省 token）；③ 无 OCR 引擎才回退全图送模型。模型默认 qwen3.8-flash（实测与 max 同精度同延迟）
- **网关延迟警示**：Token Plan 网关实测任何请求排队 2-4 分钟（与模型/流式/图文无关），走模型的链路就是慢，客户端无解；标准页面走本地链路不受影响
- **确认卡片**：识别结果悬浮卡片展示，可改金额、切类别、切收支，确认后入库（脏数据进不来）
- **统计看板**：当日/当月收支、类别占比环形图（点击扇区联动明细）、环比上月支出增减
- **明细管理**：按月分组、左滑删除、点击编辑、手动补记
- **类别管理**：预置 15 类 + 自定义增删（删除类别交易自动归"其他"）
- **隐私**：账单数据纯本地 Room 存储，金额全程整数分存储零浮点误差；API Key 用 EncryptedSharedPreferences 加密

## 构建

需要 JDK 17 + Android SDK 34：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease   # 产物 app/build/outputs/apk/release/app-release.apk
```

## 使用

**推荐流程（无弹框、无录制指示）：**
1. 安装 APK，打开 App，授予通知 + **相册图片**权限，设置页填 API Key 并"测试连接"
2. 设置页开启悬浮球 → 授权悬浮窗权限
3. 看到账单页面 → **自己按系统截屏**（电源+音量下 / 三指下滑）→ 悬浮球会脉冲闪两下表示"看到了"
4. 点悬浮球 → 自动从相册取最近 10 分钟内那张截屏 → AI 识别
5. 确认卡片核对金额类别 → 确认入账

**降级流程：** 最近 10 分钟没有截屏时，点球会走 MediaProjection 直截当前屏（Android 14+ 每次需点系统授权框，截完录制指示即灭；设置页开「截屏会话保持」可免重复授权，代价是录制指示常亮）。

## 权限说明

| 权限 | 用途 |
|---|---|
| READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE | 读取你的系统截屏（主取图路径） |
| SYSTEM_ALERT_WINDOW | 悬浮球 + 确认卡片 |
| MediaProjection | 直截屏降级路径（仅识别时单次使用，截图不落盘） |
| FOREGROUND_SERVICE (specialUse + mediaProjection) | 悬浮球常驻、Android 14 截屏合规 |
| POST_NOTIFICATIONS | 前台服务通知（保活） |
| INTERNET | 调用千问视觉 API |

国产 ROM（MIUI/ColorOS 等）请把 App 加入电池优化白名单，否则后台悬浮球可能被杀。

## 已知边界（v1）

- 不做开机自启、离线 OCR、云备份、预算提醒
- Android 14 上 MediaProjection 会话失效后需重新授权一次（系统限制）
- 截图会上传到千问接口用于识别，介意的别用

## 项目结构

```
app/src/main/java/com/killbill/
├── KillBillApp.kt              # Application，手动 DI
├── MainActivity.kt             # Compose 四 Tab 宿主
├── service/                    # 悬浮球前台服务 + 悬浮层
├── capture/                    # MediaProjection 截屏
├── ai/                         # Qwen-VL 客户端 + Prompt + 解析
├── data/                       # Room 实体/DAO/仓库 + 加密 Prefs
├── stats/                      # SQL 聚合统计
└── ui/                         # Compose 页面（统计/明细/类别/设置）
```
