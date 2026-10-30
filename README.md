# 应用构建工具 (BuildApp)

一个运行在 Android 设备上的应用程序构建工具，可以选择工作目录、部署 Gradle 环境并构建 Android 应用。

## 功能

### 主界面
首次进入应用，屏幕中间显示三个按钮：
- **📁 选择工作目录** - 弹出文件夹选择器，选择包含源码的目录
- **⚙️ 部署环境** - 跳转到终端界面，自动下载并安装 Gradle 环境（通过代理）
- **🚀 构建应用** - 使用 Gradle 默认命令或自定义命令构建应用

### 终端界面
- 实时显示构建/安装过程的终端输出
- 左上角返回箭头：运行过程中点击会弹窗提示"返回将强制停止当前构建"，可选择取消或确定
- 部署环境成功后弹窗提示，可点击"返回主界面"按钮回到主界面

### 构建功能
- 默认使用项目的 `gradlew build`，若无 gradlew 则使用系统 Gradle
- 支持自定义构建命令
- 构建过程中终端实时显示输出

### 环境部署
- 自动下载 Gradle（通过 `https://gh-proxy.com/` 代理或腾讯镜像）
- 自动检测/下载 Android 兼容的 JDK（Termux openjdk-17）
- 下载完成后自动验证

## 构建本项目

### 环境要求
- JDK 17
- Android SDK (compileSdk 34, build-tools 34.0.0)
- Gradle 8.14.6

### 构建命令
```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

APK 产物位于 `app/build/outputs/apk/debug/app-debug.apk`

## 下载代理
所有环境包下载均优先使用代理：
- Gradle: `https://gh-proxy.com/` + GitHub release 或腾讯镜像
- JDK: Termux openjdk-17 (aarch64)
