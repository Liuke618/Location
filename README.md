<p align="center">
<img src="./docs/images/LOGO.png" height="80"/>
</p>

<div align="center">

### 任意门

任意门 - 用于 Android 12+ 的无需 ROOT 权限的虚拟定位 APP

简体中文 | [English](./README.en.md)

</div>

## 简介

&emsp;&emsp;任意门是 [ZCShou/GoGoGo](https://github.com/ZCShou/GoGoGo) 的二次开发分支,基于 Android 调试 API(TestProvider)+ 高德 LBS SDK 实现的安卓定位修改工具:在地图上选定一个位置(搜索、点选、长按或手动输入坐标),一键即可将系统定位瞬移到该点,无需 ROOT 权限。只做静态瞬移,不模拟移动轨迹。

&emsp;&emsp;默认**不读取真实位置**——选点只来自搜索/地图选点/手动输入;如需真实位置蓝点辅助,可在设置中开启"定位自己的位置"。

## 功能

1. **地图选点模拟定位**:单击/长按地图选点,或手动输入经纬度(支持 GCJ02/WGS84),一键 GO 瞬移;模拟中可随时传送到新选点
2. **POI 搜索 + 逆地理**:关键字搜索建议选点,自动带出地址;搜索/定位历史本地留存
3. **收藏地点**:常用地址一键收藏,可设默认收藏——冷启动与未选点 GO 时自动兜底
4. **真实定位可选**:默认关闭,不发起任何真实定位请求;开启后显示真实位置蓝点并支持"回到当前位置"
5. **应用内更新**:自动检测新版本并下载安装
6. **协议与隐私同意**:首次启动明示用户协议与隐私政策,同意后才可使用

## 系统要求

- Android 12(API 31)及以上
- arm64-v8a 设备

## 用法

1. 下载 APK 直接安装,启动后同意用户协议与隐私政策,授予定位权限
2. 在系统**开发者选项**中将本应用选为"模拟位置信息应用"
3. 搜索地址或在地图上选点,点击 GO 按钮开始模拟;再次点击 GO 结束模拟
4. (可选)设置 → 地图定位 → 开启"定位自己的位置",显示真实位置蓝点

## 构建

环境:JDK 17 + Android SDK(AGP 9.x / targetSdk 36,纯 Java 单模块 `:app`)。

1. 在 `local.properties`(不入库)中配置:
   - `sdk.dir`:Android SDK 路径
   - `AMAP_API_KEY`:高德 Web 端申请的 Native key(绑定包名 `com.yybb.location` 与签名证书 SHA1,key 无效时地图空白、搜索报错)
   - `KEY_ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`:签名凭据(缺失则 release 构建在签名阶段失败)
2. 构建与验证:

   ```bash
   sh gradlew assembleDebug test lint                              # 日常
   sh gradlew clean assembleDebug assembleRelease test lint        # 全量(合入门槛)
   ```

   > `gradlew` 已带执行位,直接 `./gradlew ...` 即可(`sh gradlew ...` 亦可)。

## 免责声明

1. 本项目仅供学习 Android 定位调试技术之用,请勿用于游戏作弊、校园运动类 APP 作弊等违反法律法规或平台规则的场景,使用后果自负
2. 模拟定位依赖系统调试 API,个别应用(如微信等腾讯系应用)可能不生效

## 文档

- [业务全景梳理](./docs/business-overview.md):按业务域逐文件核对整理的"代码现在怎么做",随改动同步维护
- [AGENTS.md](./AGENTS.md):构建、包结构边界与开发约定

## 上游与致谢

- 上游项目:[ZCShou/GoGoGo](https://github.com/ZCShou/GoGoGo)(感谢原作者的开源工作)
- 参考项目:[MockGPS](https://github.com/Hilaver/MockGPS)、[together-go](https://github.com/bxxfighting/together-go)、[Mocklation](https://github.com/P72B/Mocklation)

## 许可证

[GPL-3.0-only](./LICENSE) © ZCShou,本分支沿用 GPL-3.0 开源。
