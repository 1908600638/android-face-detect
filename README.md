# 人脸识别

检测画面里的人脸，并给出**表情**、**性别**、**年龄**、**是否戴眼镜**、**是否戴帽子**等属性。

## 功能

- 摄像头实时预览，点「识别」抓取当前这一帧
- 一次可检测多张人脸，逐个人脸画框
- 每个人脸输出：主表情 + 置信度、性别、年龄、眼镜、帽子
- 点一下画面回到实时预览

## 实现原理

### 整体流程

```
摄像头抓帧（JPEG）
   │
   ▼
ImageSource.fromJpeg()  ──▶  Bitmap（最长边 ≤ 2048，已旋正）
   │
   ▼
FaceEngine.scan()
   ├── MLFrame.fromBitmap(bitmap)
   ├── analyzer.asyncAnalyseFrame(frame)
   │     │
   │     ▼
   │   华为 HMS 端侧人脸分析（一次调用同时给出轮廓、关键点、表情、特征）
   │
   ▼
List<MLFace>
   ├── getBorder()    ──▶ 人脸框
   ├── getEmotions()  ──▶ 7 种情绪概率，取最大的作为标签
   └── getFeatures()  ──▶ 性别概率、年龄、眼镜概率、帽子概率
   │
   ▼
List<Detect> ──▶ BoxPainter 画框 ──▶ 预览层 + 结果文本
```

### 1. 华为 HMS

端侧人脸的「表情 + 性别 + 年龄 + 配饰」这套能力，自己训练要几套模型加标注数据。华为 HMS Core ML Kit 一次调用就能同时给出，模型随 SDK 下发，不需要额外打包。

用的是 `MLFaceAnalyzer`，配置里打开：

| 配置项 | 值 | 含义 |
|---|---|---|
| `setKeyPointType` | `TYPE_KEYPOINTS` | 检测人脸关键点（眼、鼻、嘴） |
| `setShapeType` | `TYPE_SHAPES` | 检测人脸轮廓点 |
| `setFeatureType` | `TYPE_FEATURE_EMOTION｜TYPE_FEATURE_GENDAR` | 只启用人脸表情和性别检测 |
| `setPerformanceType` | `TYPE_SPEED` | 速度优先 |
| `setTracingAllowed` | `false` | 不做跨帧追踪（本工程是单帧识别） |
| `setPoseDisabled` | `true` | 不做姿态估计，省算力 |

### 2. 表情怎么取

HMS 给 7 种情绪各自一个概率，本工程取最大的那个：

| 索引 | 中文 | HMS 字段 |
|---|---|---|
| 0 | 微笑 | `getSmilingProbability()` |
| 1 | 无表情 | `getNeutralProbability()` |
| 2 | 惊讶 | `getSurpriseProbability()` |
| 3 | 悲伤 | `getSadProbability()` |
| 4 | 愤怒 | `getAngryProbability()` |
| 5 | 厌恶 | `getDisgustProbability()` |
| 6 | 恐惧 | `getFearProbability()` |

最大值对应的概率就是这条结果的置信度。

### 3. 其它属性

`MLFaceFeature` 里直接读：

- `getSexProbability()` — < 0.5 判男，≥ 0.5 判女
- `getAge()` — 估算年龄
- `getSunGlassProbability()` — > 0.6 判为戴眼镜
- `getHatProbability()` — > 0.5 判为戴帽子

## 代码架构

```
app/src/main/java/com/shizi/face/
├── MainActivity.java        178 行   摄像头预览、抓帧、结果展示
├── img/
│   └── ImageSource.java      74 行   JPEG 解码 + 降采样 + 旋正
├── scan/
│   ├── Scanner.java          13 行   引擎接口
│   ├── Detect.java           17 行   一条结果
│   ├── ScanOutcome.java      17 行   结果集合 + 汇总文本
│   ├── BoxPainter.java       84 行   画框与标签
│   └── FaceEngine.java      117 行   ★ HMS 人脸分析封装
└── util/
    └── UiKit.java            11 行   Toast
```

### 各文件职责

| 文件 | 职责 |
|---|---|
| `MainActivity` | 相机权限 → CameraX 预览 → `ImageCapture` 抓帧 → 调引擎 → 显示标注图和文字 |
| `ImageSource` | JPEG 解码、降采样到最长边 2048、按元数据旋转角旋正 |
| `Scanner` | 引擎接口，`scan(bitmap, callback)` + `close()` |
| `Detect` | 一条结果：`label`（框上短标签，如「微笑 / 女」）、`note`（详细属性）、`score`（置信度）、`box`（位置） |
| `ScanOutcome` | 结果列表 + 汇总多行文本 |
| `BoxPainter` | 把每个 `Detect` 的框和标签画到图片副本上 |
| `FaceEngine` | **核心**：HMS 分析器配置、调用、把 `MLFace` 转成 `Detect` |
| `UiKit` | Toast 封装 |

### 核心代码位置

| 位置 | 作用 |
|---|---|
| `FaceEngine.SETTING` | 分析器配置，决定开哪些能力 |
| `FaceEngine.scan()` | 建 `MLFrame`、异步分析、回调 |
| `FaceEngine.toDetect()` | **核心**：7 种情绪取最大 + 组装属性文本 |
| `FaceEngine.describe()` | 拼接结果文本 |
| `FaceEngine.close()` | 释放分析器 |

## 环境要求

| 项 | 版本 |
|---|---|
| Android Studio | 2021.3.1（Dolphin）及以上 |
| JDK | 11 或 17（Android Studio 自带的 JBR 即可） |
| Android Gradle Plugin | 7.3.1 |
| Gradle | 7.4 |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0） |
| NDK / CMake | **不需要**（HMS 的 so 由依赖提供） |
| 设备 | 真机 + 摄像头 |
| 网络 | **首次构建需要联网**，从华为 maven 下载依赖 |

`settings.gradle` 里必须声明华为仓库，否则解析不到依赖：

```groovy
maven { url 'https://developer.huawei.com/repo/' }
```

## 编译运行

1. 用 Android Studio 打开本目录
2. 首次 Sync 会联网从华为 maven 拉依赖，耐心等它下完（几十 MB）
3. 首次打开会自动生成 `local.properties`（记录本机 SDK 路径），不要提交到版本库
4. 连接真机，点 Run
5. 首次启动请求相机权限，允许后看到实时画面
6. 对准人脸，点「识别」

命令行构建：

```bash
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

> 工程放在中文路径下时 AGP 会报非 ASCII 路径错误，本工程已在 `gradle.properties` 加了一行 `android.overridePathCheck=true`。

### 常见编译问题

| 现象 | 原因与处理 |
|---|---|
| `Could not find com.huawei.hms:ml-computer-vision-face` | `settings.gradle` 里没加华为 maven 仓库 |
| `Manifest merger failed : Attribute application@allowBackup` | HMS 的 aar 也声明了该属性，本工程已通过不声明 `allowBackup` 规避 |
| 首次构建超时 | 华为仓库在国内速度不稳定，多试几次或挂代理 |

## 依赖

| 依赖 | 用途 | 许可 |
|---|---|---|
| `com.huawei.hms:ml-computer-vision-face:3.11.0.301` | 人脸分析主 SDK | 华为开发者协议（闭源） |
| `...-face-shape-point-model:3.7.0.301` | 人脸轮廓关键点模型 | 同上 |
| `...-face-emotion-model:3.7.0.301` | 表情模型 | 同上 |
| `...-face-feature-model:3.7.0.301` | 属性（性别/年龄/眼镜/帽子）模型 | 同上 |
| CameraX 1.3.0（core / camera2 / lifecycle / view） | 摄像头预览与抓帧 | Apache-2.0 |
| AndroidX AppCompat、Activity、Core | 基础 UI | Apache-2.0 |

> 华为 HMS Core ML Kit 是**闭源 SDK**，构建时从华为官方 maven 下载，**不随本仓库分发**。
>

端侧人脸分析不需要 AppGallery Connect 配置（不需要 `agconnect-services.json`），华为官方的 ML Kit 示例仓库同样没有配置该文件。
