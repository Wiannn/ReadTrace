# ReadTrace

ReadTrace 是一个生成读书壁纸的工具。通过接入微信读书 API，获得读书记录，生成小票设计类型壁纸。

壁纸内容主要包括：阅读书目、书籍作者、阅读进度、阅读时长、阅读日期、备注、装饰贴纸。

### 适用设备

测试设备为 BOOX 4。理论上所有安卓设备均可使用。

内置阅读器尺寸预设包括 Poke、P6、Leaf、Note、Tab、T10、T13 等常见型号。首次打开时会尝试根据设备型号自动匹配，匹配不到时默认使用 Leaf 5。

### 安装方式

- 浏览器下载.apk资源安装。
   ```text
   app-arm64-v8a-release.apk
   ```
tip: 如果系统提示“不允许安装未知来源应用”，请按系统提示为文件管理器或浏览器开启安装权限。

## 首次使用

1. 进入“设置”页面，拉到底，填写“微信读书API Key”。 
微信读书API Key获取：微信读书app-我-右上角设置-微信读书Skill-获取API Key-复制Key。
2. 点击“保存Key”，点击“测试连接”，确认能读到书架信息。
3. 设置阅读器尺寸。
4. 选择统计周期：”最近“或”本周“或”本月“。
5. 选择”预览“页面，点击”刷新预览“，等待壁纸生成，确认效果是否满意。
6. 点击”生成壁纸“，图片自动保存到本地设备。
7. 到文石系统的屏保设置里选择生成的图片。
生成的壁纸会覆盖保存为同一张图片，路径通常是：
```text
/storage/emulated/0/Pictures/NeoReader/neoreader_wallpaper.png
```
这样后续自动刷新时，不需要反复重新选择屏保图片。

## 页面说明

顶部有四个主要入口：

- 设置：调整数据来源、排版、字体、自动刷新等参数。
- 预览：在 App 内查看当前设置生成的效果，不会保存真实壁纸文件。
- 刷新预览：重新读取数据并更新预览。
- 生成壁纸：把当前预览对应的壁纸保存到图片目录。

### 设置页面
1. 可选择显示的书籍数量，数量可选范围1-5本，默认5本。
2. 可修改标题内容、字体大小、字体。
3. 可修改正文字体，即除标题以外的文字视作正文。
4. 自定义字体：
   - “系统字体”：SERIF_BOLD、SANS、MONO。
   - “自定义字体”：用户从本地导入。
   - “CevicheOne-Regular.ttf”：默认的标题字体，适用于英文。
   - “迫真打字油印体.ttf”：默认的正文字体，适用于中文。
5. 可修改学厨名称，旨在增加个性化定制，默认为“开卷有益”。
6. 可选择贴纸，旨在增加个性化定制，默认为“无”，位于壁纸右上方。
   - “自定义贴纸”：从本地导入。
   - 内置演示贴纸：”drink“、”pudding“两款。
7. 可修改备注内容，旨在增加个性化定制，默认为”*祝你用餐愉快！*“，以适配小票壁纸风格。

### 其他
- 最小时长阈值默认5分钟，小于该分钟数的阅读数目会被忽略。
- 没有识别到作者，会显示未知。

## 权限说明

应用可能需要以下权限或系统设置：
- 文件和媒体访问：用于保存生成的壁纸图片，以及写入调试日志。
- 所有文件访问权限：部分 BOOX 系统上，读取本地书籍封面或写入固定图片路径需要开启该权限。
- 字体目录授权：用于读取 `存储/Fonts` 中的 `ttf` / `otf` 字体。这个权限通过系统文件选择器授予，只需要选择 Fonts 文件夹。
- 网络权限：用于在启用微信读书或混合来源时请求微信读书 API。
- 后台运行/解除冻结：用于保证熄屏触发、内容变化监听和每日定时能执行。
- 前台服务：用于在开启熄屏触发时维持必要的监听服务。

## 自动刷新

自动刷新会覆盖保存同一张壁纸图片，便于文石屏保继续使用原路径。

可选模式：

- 每日定时一次：省电，适合稳定更新。
- 熄屏触发：更及时，但会增加唤醒次数和耗电。

熄屏触发受“熄屏最小间隔”限制。间隔越短越及时，也越容易增加耗电。NeoReader 常在退出当前书籍或会话结束后才写入最新元数据，所以可能出现本次锁屏仍是旧图、下一次锁屏才生效的情况。

微信读书包含联网请求，不会在熄屏瞬间请求网络。自动模式下会在解锁后等待网络恢复并重试；选择每日定时模式时，也会在设定时间执行联网同步。成功后覆盖保存同一张图片，因此常见体验是：解锁或定时刷新成功后，下一次锁屏看到新图。

如果希望自动刷新稳定工作，请在 BOOX 系统中确认app没有被冻结，并允许后台运行。

## 调试日志

如果遇到问题，可以在设备上导出以下日志并反馈：

```text
/storage/emulated/0/Download/neoreader_debug_log.txt
/storage/emulated/0/Download/neoreader_auto_refresh_log.txt
```

日志中会记录元数据字段、封面命中来源、自动刷新触发原因等信息。

## 开发构建

本项目当前核心功能在 Android 原生 Kotlin 代码中。

Debug 构建：

```sh
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
GRADLE_USER_HOME="/Users/dmer/Dev/NeoReaderReaderRecords/.gradle-home" \
./gradlew assembleDebug --no-daemon
```

Release 构建：

```sh
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
GRADLE_USER_HOME="/Users/dmer/Dev/NeoReaderReaderRecords/.gradle-home" \
./gradlew assembleRelease --no-daemon
```

Release APK 输出位置：

   ```text
   android/app/build/outputs/apk/release/
   ```


