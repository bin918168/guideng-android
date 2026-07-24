# Guideng Android

归灯是一个面向家庭位置共享的 Android 应用。当前项目由原 Web 端改造而来，使用 React/Vite 构建 Web 界面，并通过 Capacitor 打包为 Android 应用。

## 功能

- 登录自建归灯服务器
- 注册当前设备并持续上报位置
- 查看设备最新位置和最近轨迹
- 使用高德地图展示位置与轨迹
- Android 端申请位置、后台定位、通知和后台常驻相关权限
- 通过前台服务通知维持后台位置共享任务

## 更新说明

### 1.1.1

- Android 原生前台服务统一负责位置上报，避免应用前台运行时重复上传
- 为位置上传增加失败重试与指数退避，降低网络异常时的请求压力
- 位置上传成功后不再立即刷新全部设备，减少不必要的网络请求
- 优化后台位置共享的稳定性

## Android 权限说明

应用会申请以下与定位和后台运行相关的权限：

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`：获取当前设备位置
- `ACCESS_BACKGROUND_LOCATION`：在应用不在前台时继续共享位置
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION`：启动定位类型前台服务
- `POST_NOTIFICATIONS`：显示后台常驻通知
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：请求用户允许忽略电池优化
- `WAKE_LOCK`：辅助后台任务保持运行

Android 11 及更高版本通常要求用户在系统设置中手动授予“始终允许”定位权限。部分厂商系统还需要用户手动开启自启动、后台运行或电池无限制设置。

## 开发

安装依赖：

```bash
npm install
```

启动 Web 开发服务器：

```bash
npm run dev
```

构建 Web 资源：

```bash
npm run build
```

同步到 Android 工程：

```bash
npx cap copy android
```

构建调试 APK：

```bash
cd android
./gradlew assembleDebug
```

调试 APK 输出位置：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

构建 release APK：

```bash
npm run build
npx cap copy android
cd android
./gradlew clean assembleRelease
```

release APK 输出位置：

```text
android/app/build/outputs/apk/release/app-release.apk
```

如果存在 `android/keystore.properties`，构建会使用其中配置的签名；否则生成未签名的 release APK。

## Google Play 发布

Google Play 推荐上传 Android App Bundle。发布前需要配置 release 签名，然后执行：

```bash
npm run build
npx cap copy android
cd android
./gradlew bundleRelease
```

发布包输出位置通常为：

```text
android/app/build/outputs/bundle/release/app-release.aab
```

由于本应用使用后台定位，上架 Google Play 时需要在 Play Console 中填写后台定位权限声明、Data safety 表单，并提供隐私政策 URL 与后台定位用途说明。

## 隐私与合规提示

归灯会把设备名称、设备标识、当前位置、精度、速度、方向、时间戳和轨迹数据发送到用户填写的自建服务器。发布者应在隐私政策中说明数据类型、用途、存储位置、删除方式和联系方式。

本项目不适用于紧急救援、医疗、执法或其他高风险场景。

## 开源协议

本项目采用 Apache License 2.0 开源。

```text
SPDX-License-Identifier: Apache-2.0
Copyright 2026 FM619 TECHNOLOG
```

详见 [LICENSE](LICENSE)。
