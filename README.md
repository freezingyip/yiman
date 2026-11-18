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

測試版 APK 位於 `app/build/outputs/apk/debug/`。已簽署的公開版本可在 [GitHub Releases](https://github.com/freezingyip/yiman/releases) 下載。App 的「關於 → 檢查更新」也會開啟該頁。

公開版可與原有的測試版並存。若要轉移書架和閱讀記錄，先在測試版的「資料與儲存空間」建立備份，再在公開版還原。備份檔不包含已下載的漫畫圖片。

## 開源與授權

一漫使用 [Apache License 2.0](LICENSE)。本專案基於 Mihon，感謝 Mihon 及其上游專案與所有貢獻者。

上游版權：Copyright © 2015 Javier Tomás；Copyright © 2024 Mihon Open Source Project。第三方開源授權可在應用程式的「關於」頁面查看。
