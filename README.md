# 一漫

一漫是 Android 漫畫閱讀器，基於 [Mihon](https://github.com/mihonapp/mihon) 開發。

## 功能

- 本機書架、閱讀進度與歷史記錄。
- 全域搜尋已啟用的漫畫來源。
- 手動下載章節及管理已下載內容，以便離線閱讀。
- 支援橫向翻頁、雙頁和上下捲動等閱讀方式。
- 更新書架以檢查漫畫章節。

支援 Android 8.0（API 26）及以上版本。來源的可用性取決於各來源網站。

## 建置

使用 Android Studio 開啟本目錄，安裝專案所需的 Android SDK 和 JDK，再執行：

```sh
./gradlew :app:assembleDebug
```

測試版 APK 位於 `app/build/outputs/apk/debug/`。在 App 的「關於」頁按「檢查更新」，可手動前往 [一漫的 GitHub Releases](https://github.com/freezingyip/yiman/releases) 查看發行版本。

## 開源與授權

一漫使用 [Apache License 2.0](LICENSE)。本專案基於 Mihon，感謝 Mihon 及其上游專案與所有貢獻者。

上游版權：Copyright © 2015 Javier Tomás；Copyright © 2024 Mihon Open Source Project。第三方開源授權可在應用程式的「關於」頁面查看。
