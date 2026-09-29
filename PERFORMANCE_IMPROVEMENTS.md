# 效能優化指引 (Performance Improvements)

## 概述 (Overview)
Feed 過濾引擎會在 Facebook App 的熱點路徑（例如：用戶快速滑動、UI 佈局、背景處理）中被頻繁且大量地執行。因此，將「物件記憶體分配 (Object Allocation)」降到最低，並盡可能提高執行速度，是防止畫面掉幀 (Scroll Jank) 和減少垃圾回收 (Garbage Collection, GC) 停頓的關鍵。

在專案演進中，我們依序進行了架構與效能上的三個階段重點優化：

---

### 第一階段：Modern MVVM + UDF 架構重構 (Architecture Refactoring)
* **優化重點**：將整體模組設定 UI 與狀態管理遷移至現代化的 Modern MVVM (Model-View-ViewModel) 以及 UDF (Unidirectional Data Flow，單向資料流) 架構。
* **效果**：解耦 UI 渲染與資料狀態，讓設定變更與狀態更新在生命週期內更穩定、更高效地傳遞，同時避免無謂的 Recomposition 與 UI 重繪開銷。

---

### 第二階段：過濾引擎與字串處理優化 (Filtering Engine & String Optimization)

#### 1. 移除 `FeedItemFacts` 中的 `Lazy` 委派物件分配
* **優化前**：`FeedItemFacts` 使用了 Kotlin 的 `by lazy(LazyThreadSafetyMode.NONE)` 來快取 `category`、`sponsored`、`aiContent` 和 `searchableText` 的結果。當一整個批次的 Feed (可能包含上百個項目) 被評估時，每一個項目都會產生 4 個 `Lazy` 委派物件，造成極大的 GC 壓力。
* **優化後**：我們移除了 `lazy` 委派，改用純手工的整數 `stateMask` (位元遮罩) 來追蹤狀態。這徹底消除了過濾大量貼文時產生數以千計的暫存物件，並且依舊保留了「需要時才計算」的延遲載入特性。

#### 2. 減少 `FeedFilterEngine` 中的 Auto-Boxing (自動裝箱) 與 Map 操作
* **優化前**：`partition()` 方法在過濾每一篇貼文時，都會使用 `LinkedHashMap<String, Int>` 來累加各個規則的觸發次數。這會頻繁地將 `Int` 轉換（Boxing）為 `Integer`，並且在 Map 尋找、寫入時產生額外開銷。
* **優化後**：改用一個與規則數量相同大小的 `IntArray` 來進行計數。陣列操作極快且沒有 Boxing 問題；直到整個批次過濾結束前，我們才將最終陣列的結果封裝回 Map，這保留了原有的對外 API 介面。同時，在 `kept` 列表初始化時加入了預先分配容量（Pre-allocation），避免動態擴展陣列。

#### 3. 在 `FeedItemInspector` 中導入反射結果快取 (Memoization)
* **優化前**：不同的過濾規則（例如判斷 Category 和判斷 Sponsored），會獨立地針對同一個物件重複進行相同的反射查詢（例如 `edgeFrom(value)` 或 `invokeItemModelAccessor`）。
* **優化後**：在 `FeedGuardHook` 中引入了 `FeedItemContext`，並搭配 `ThreadLocal` 快取當前正在處理的貼文。確保同一篇貼文在被多個規則檢查時，中間繁重的反射結果只會計算一次並共享給其他規則使用，省下了大量重複的反射成本。

#### 4. 在 `FeedContentRules` 快取全域關鍵字設定
* **優化前**：每次 Feed pipeline 進行評估時，`FeedContentRules.config()` 都會重新讀取設定中的關鍵字字串，並呼叫 `split()`、`trim()` 以及 `lowercase()`。這會不斷創造新的 `List<String>` 與字串物件。
* **優化後**：解析後的 List 現在會被永久快取起來，只有當底層的原始字串設定發生變化時，才會重新切割與轉換。

#### 5. 避免在文字比對中產生字串配置 (String Allocation)
* **優化前**：
  1. `FeedGuardHook.isAdSignalText()` 為了進行不分大小寫比對，呼叫了 `.lowercase()`，這會憑空產生一個新的小寫字串。
  2. 在 `NewsfeedFilterHook.isAiLabel()` 中，為了比對 Android UI 層傳來的 `CharSequence` (通常是 `SpannedString` 等結構)，直接呼叫了 `.toString()`，這在 UI 佈局的繪製迴圈中是非常昂貴的。
* **優化後**：
  1. `isAdSignalText` 改用 `contains(token, ignoreCase = true)`，直接進行字元比對，不需產生任何新字串。
  2. `isAiLabel` 現在完全針對 `CharSequence` 的長度與 `startsWith()` 進行字元序列匹配檢查，避開了所有非必要的字串轉型與物件創建。

---

### 第三階段：UI 佈局防抖與反射存取極速化 (深層卡頓解決)

#### 6. 消除 `RecyclerView.onLayout` 的 UI Handler 隊列阻塞 (Debounce & Early Exit)
* **問題**：`NewsfeedFilterHook` 中的 `AiUiFallbackHook` 攔截了 `RecyclerView.onLayout`。在用戶滑動 Feed 時，此方法每秒會觸發 60-120 次。舊程式碼即使在未開啟 AI 過濾的情況下，仍然會在每次 layout 時建立一個 650ms 延遲的 `Runnable` 排入 Handler，導致滑動時佇列堆積了幾百個任務。
* **優化後**：
  1. 在 `intercept` 入口處加入關閉狀態的即時返回 (`!Settings.getBoolean(...) return result`)，未開啟時零開銷。
  2. 導入 **Debounce (防抖機制)**：當滑動過程中連續觸發 layout 時，自動取消前一次未執行的掃描 Runnable，確保只在滑動停止/穩定後的最後一格執行單次 UI 掃描，徹底解決滑動過程中的微卡頓。

#### 7. 消除 `SponsoredCheck.isSponsored` 的 `Class.getDeclaredFields()` 陣列分配
* **問題**：`AdFilterHook.kt` 判斷元件是否為廣告時，會沿著類別繼承鏈呼叫 `cls.declaredFields`。Java 原生 API 的 `getDeclaredFields()` **每次呼叫都會重新建立並回傳一個全新的 `Field[]` 陣列與 `Field` 物件**，在動態渲染與過濾時產生大量的記憶體垃圾。
* **優化後**：使用 `ConcurrentHashMap`（`declaredFieldsCache`）快取每一個類別解析後的可存取非靜態屬性列表 `List<Field>`。後續所有廣告檢查皆直接讀取快取清單，將 `getDeclaredFields()` 的呼叫次數降為 0 次。

#### 8. 快取經典 Feed 的 `findHolderField` 反射解析
* **問題**：`NewsfeedFilterHook` 在每次收到新聞聯播數據時，都會重新遍歷 `processNewStories` Runnable 的所有屬性來找尋集合 Holder，且過去沒有對此進行快取。
* **優化後**：加入 `holderFieldCache`，第一次解析成功後便予以快取，後續資料批次到達時即可以 $O(1)$ 時間常數取得 Field。

#### 9. 快取 React Native `requestBodyOf` 的反射方法
* **問題**：`MarketplaceAdsHook` 在攔截 React Native 網路請求時，每次都會呼叫 `data.javaClass.methods` 兩次來尋找 `hasKey` 和 `getString`，每次都生成新的 `Method[]` 陣列。
* **優化後**：加入 `readableMapMethodsCache`（使用 `java.util.Optional` 處理 null 安全），使 RN 網路請求攔截不再產生反射陣列。

#### 10. 清除全域設定與 AI 檢查中的臨時陣列/Set 配置
* **問題**：
  1. `Settings.getBoolean` 每次被呼叫都會執行一次 `setOf(ADS_MARKETPLACE, ADS_GAME_ADS)` 創建 Set 物件。
  2. `AiTransparencyInspector.isPrimaryStoryAi` 每次檢查都會執行 `arrayOf(...)` 建立包含 2 個 Pair 的陣列。
* **優化後**：將這兩處抽離為單例/靜態常數（`MARKETPLACE_GAME_KEYS` 與 `AI_HOLDER_HASH_PAIRS`），避免在熱點迴圈中頻繁分配集合物件。

#### 11. 快取 UI 樹 View 繼承關係檢查 (`inheritsFrom`)
* **問題**：`NewsfeedFilterHook` 尋找主頁 Feed 容器時，會在多層 View 樹上重複沿著 `cls.superclass` 遞迴檢查 class 名稱。
* **優化後**：加入 `inheritsFromCache`，快取 `Class<*>` 對應目標類別名稱的判斷結果，消除重複的 View 類別繼承樹巡檢。