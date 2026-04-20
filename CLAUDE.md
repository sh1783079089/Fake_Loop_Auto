# Fake Loop Auto

Android 应用，通过 BLE 外设模式模拟 Loop 智能跳绳设备，使手机能被 Loop 小程序识别并接收跳绳数据。

## 项目结构

```
app/src/main/java/com/fasa2333/fakeloop/
├── MainActivity.kt          # 主界面 + 全部业务逻辑
└── BlePeripheralManager.kt  # BLE 外设 / GATT 服务器
```

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose (BOM 2024.09.00)
- 最低 API 23，目标 API 36
- Gradle 8.13.1 (Kotlin DSL)，需要 **JDK 17**（本机路径：`C:\Program Files\Zulu\zulu-17`）

## BLE 协议

**广播参数**
- 设备名：`LR029429BD`
- 服务 UUID：`FFF0`，通知特征：`FFF1`，读写特征：`FFF2`
- 制造商 ID：`0x00EE`，Payload：`4C4F4F50000200`（ASCII "LOOP"）

**数据包格式**

| 用途 | Hex 示例 | 说明 |
|------|---------|------|
| 开始跳绳 | `6F 02 01 00 00 72` | 6 字节，末字节为字节和校验 |
| 跳绳结果 | `6F 04 0B 00 00 00 [cnt_hi] [cnt_lo] 69 [...] [sum]` | 16 字节 |

结果包规则：
- `bytes[6-7]` = `count × 10`（大端序）
- `bytes[8]` = `0x69`（常量）
- `bytes[9-14]` 在 N=800 / N=1600 两个真实捕获样本间线性插值
- `bytes[15]` = `sum(bytes[0..14]) & 0xFF`

## 功能说明

### 快捷按钮
- **开始跳绳**：发送 6 字节开始包
- **跳绳 800 / 1600 下**：发送硬编码结果包
- **发送自定义数据**：输入任意 Hex 字符串发送

### 自动跳绳（新增）
参数：总次数、平均速度（次/分钟）、减速率（0~1）

流程：
1. 发送开始包
2. 每秒计算当前速度（线性减速 + ±5% 随机扰动）并累加跳数
3. 每秒发送一次带当前跳数的结果包
4. 完成后发送精确总数的最终包

速度模型：`speed(t) = avgJpm×(1+decel) − 2×decel×avgJpm/totalSec × t`，下限为 `avgJpm×0.3`

## 构建

```bash
# 命令行（在项目根目录）
JAVA_HOME="C:/Program Files/Zulu/zulu-17" ./gradlew assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

Android Studio / IntelliJ IDEA：Gradle 面板 → `app > Tasks > build > assembleDebug`

如缺少 `local.properties`，需手动创建并填写 Android SDK 路径：
```
sdk.dir=C\:\\Users\\tick47\\AppData\\Local\\Android\\Sdk
```