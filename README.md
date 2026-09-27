# 機捷時刻（AirportTime）

查桃園機場捷運兩站之間、今天某個時間以後班次的 Android App。做法同 [TraTime](https://github.com/agan0617/TraTime)。

## 功能

- 預設 **台北車站 → 機場第一航廈、現在**，打開就列出今天這個時間之後的班次
- 起站／終站點站名挑選（清單標出直達車有停的站），⇄ 對調；點時間改、長按回到現在
- 每班顯示：出發 → 抵達、行駛幾分、往哪、**直達車（紫）／普通車（藍）**；直達車不停的站自動只列普通車
- 平日／假日時刻自動切換（只看星期：週六日＝假日；國定假日手機不知道，會用平日時刻）

## 資料來源

交通部 [TDX](https://tdx.transportdata.tw/)：

- 發車時刻：`/v2/Rail/Metro/StationTimeTable/TYMC`，依起站抓，同一天快取 6 小時
- 抵達時間：起站發車時間＋站間行駛時間。車站順序與站間行駛時間（依車種）打包在 `app/src/main/assets/tymc.json`，來自 `/v2/Rail/Metro/StationOfRoute/TYMC` 與 `/S2STravelTime/TYMC`；機捷改站或改速度時要重新產生

## 建置

同 TraTime：Android Studio 內建 JDK＋SDK 34，`gradlew assembleRelease`，產出 `app/build/outputs/apk/release/app-release.apk`（debug 金鑰簽章）。
