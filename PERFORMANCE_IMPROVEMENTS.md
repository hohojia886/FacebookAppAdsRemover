# 效能優化指引 (Performance Improvements Guide)

## 概述 (Overview)
Feed 過濾引擎與各項模組 Hook 會在 Facebook App 的熱點路徑（例如：啟動初始化、用戶快速滑動、UI 佈局測量、背景網路處理）中被頻繁且大量地執行。因此，將「物件記憶體配置 (Object Allocation)」與「重複反射開銷 (Reflection Overhead)」降到最低，並確保熱點作業不阻塞主執行緒，是防止畫面掉幀 (Scroll Jank) 和減少垃圾回收 (Garbage Collection, GC) 停頓的關鍵。

以下彙整了專案演進過程中的四大階段優化，包含改動內容、預估效能提升與實作難易度。

---

### 第一階段：Modern MVVM + UDF 架構重構 (Architecture & State Refactoring)

#### 1. 模組設定 UI 與狀態管理架構重構
* **難易度**：高 (★★★)
* **預估效能改進**：降低 30%~50% UI 重繪開銷，顯著提升設定頁面回應順暢度。
* **優化前**：過去的設定頁面與 Hook 狀態同步缺乏統一的資料流，容易引發無謂的 View 重繪 (Recomposition) 與狀態不一致問題。
* **優化後**：全面遷移至 Modern MVVM (Model-View-ViewModel) 以及 UDF (Unidirectional Data Flow，單向資料流) 架構。徹底解耦 UI 渲染與資料狀態，讓設定變更在生命週期內更穩定、更高效地傳遞。

---

### 第二階段：主執行緒與 UI 繪製防抖/非同步優化 (UI Thread & Render Debouncing)

#### 2. 消除 `RecyclerView.onLayout` 的 UI Handler 隊列阻塞與防抖 (Debounce & Early Exit)
* **難易度**：中 (★★☆)
* **預估效能改進**：滑動幀率提升 15~30 FPS，徹底消除動態過濾時的畫面微卡頓。
* **優化前**：`NewsfeedFilterHook` 中的 `AiUiFallbackHook` 攔截了 `RecyclerView.onLayout`（滑動時每秒觸發 60-120 次）。舊程式碼即使在未開啟 AI 過濾的情況下，仍會在每次 layout 時建立一個 650ms 延遲的 `Runnable` 排入 Handler，導致滑動時佇列堆積了數百個任務。
* **優化後**：
  1. 在 `intercept` 入口處加入關閉狀態的即時返回，未開啟時 **零開銷**。
  2. 導入 **Debounce (防抖機制)**：當滑動過程中連續觸發 layout 時，自動取消前一次未執行的掃描 Runnable，確保只在滑動停止/穩定後的最後一格執行單次 UI 掃描。

#### 3. 將啟動探測與 Class 尋找移至背景執行緒 (Background Thread Discovery)
* **難易度**：中 (★★☆)
* **預估效能改進**：冷啟動時主執行緒凍結時間減少 ~1,000ms，解決開 App 時的白屏與無反應感。
* **優化前**：主程式在冷啟動時，會在 UI 主執行緒（Main Looper）上執行 `probeAndMaybeDiscover`，包含了大量的 Class 尋找與快取載入，造成開 App 時明顯的 UI 凍結。
* **優化後**：改用 `Executors.newSingleThreadScheduledExecutor()` 在背景執行緒進行 Class 探測與 Hook 安裝；並加入智慧完成判斷，當所有 Hook 均安裝完畢後，後續的定時器會立即退出。

#### 4. 避免通用 `LinearLayout.onMeasure` 中的全局 Regex 匹配
* **難易度**：低 (★☆☆)
* **預估效能改進**：減少 99.9% 無關 UI 測量時的 CPU 正規表示式運算與字串配置。
* **優化前**：開啟 Tab 隱藏時，App 中每一個 3~8 個子 View 的 `LinearLayout` 在測量（`onMeasure`）時都會呼叫 `reconcile`，並對每個子 View 的說明執行正規表示式比對。
* **優化後**：在執行清單轉換與 Regex 之前，先進行極速字串快速檢查 `contains("tab", ignoreCase = true)`，迅速過濾掉非導覽列的通用 LinearLayout。

#### 5. 快取 `FeedFilterEngine` 避免元件渲染時的物件配置
* **難易度**：低 (★☆☆)
* **預估效能改進**：CPU 渲染過濾耗時減少 ~40%，消除 Litho 繪製迴圈中的物件生成。
* **優化前**：Litho 在渲染每個 Feed 項目時，每秒可能觸發數百次 `ComponentGuardHooker.intercept`。每次攔截都會呼叫 `engine(FeedPipeline.LITHO_RENDER)`，導致每次渲染都重新建立 `ArrayList`、`FeedFilterEngine` 以及匿名規則物件 `object : FeedFilterRule`。
* **優化後**：在 `FeedContentRules` 中透過比對 `FeedFilterConfig`，為每個 Pipeline 快取專屬的 `FeedFilterEngine`，設定未改變時直接重用引擎實例。

---

### 第三階段：反射查詢極速化與記憶體快取 (Reflection & Memoization Optimization)

#### 6. 消除 `SponsoredCheck.isSponsored` 的 `Class.getDeclaredFields()` 陣列分配
* **難易度**：中 (★★☆)
* **預估效能改進**：反射效能提升 80%+，完全消除廣告判斷時的 `Field[]` 陣列重新配置，顯著降低 GC 頻率。
* **優化前**：`AdFilterHook.kt` 判斷元件是否為廣告時，會沿著類別繼承鏈呼叫 `cls.declaredFields`。Java 原生 API 的 `getDeclaredFields()` 每次呼叫都會重新建立並回傳全新的 `Field[]` 陣列與 `Field` 物件。
* **優化後**：使用 `ConcurrentHashMap`（`declaredFieldsCache`）快取每一個類別解析後的可存取非靜態屬性列表 `List<Field>`，將 `getDeclaredFields()` 呼叫次數降為 0 次。

#### 7. 在 `FeedItemInspector` 中導入貼文級別反射結果快取 (Memoization)
* **難易度**：中 (★★☆)
* **預估效能改進**：重複反射查詢耗時減少 50%~70%。
* **優化前**：不同的過濾規則（例如判斷 Category 和判斷 Sponsored），會獨立地針對同一個物件重複進行相同的反射查詢（例如 `edgeFrom(value)` 或 `invokeItemModelAccessor`）。
* **優化後**：在 `FeedGuardHook` 中引入了 `FeedItemContext`，並搭配 `ThreadLocal` 快取當前正在處理的貼文，確保中間繁重的反射結果只會計算一次並共享給其他規則使用。

#### 8. 快取經典 Feed 的 `findHolderField` 反射解析
* **難易度**：低 (★☆☆)
* **預估效能改進**：貼文批次到達時的 Holder 搜尋耗時從 $O(N)$ 降至 $O(1)$ 常數時間。
* **優化前**：`NewsfeedFilterHook` 在每次收到新聞聯播數據時，都會重新遍歷 `processNewStories` Runnable 的所有屬性來找尋集合 Holder，過去沒有對此進行快取。
* **優化後**：加入 `holderFieldCache`，第一次解析成功後便予以快取。

#### 9. 快取 React Native `requestBodyOf` 的反射方法
* **難易度**：低 (★☆☆)
* **預估效能改進**：網路攔截處理開銷降低 60%，消除每次 RN 請求的 `Method[]` 陣列生成。
* **優化前**：`MarketplaceAdsHook` 在攔截 React Native 網路請求時，每次都會呼叫 `data.javaClass.methods` 兩次來尋找 `hasKey` 和 `getString`。
* **優化後**：加入 `readableMapMethodsCache`（搭配 `java.util.Optional`），使 RN 網路請求攔截不再產生反射陣列。

#### 10. 快取 UI 樹 View 繼承關係檢查 (`inheritsFrom`)
* **難易度**：低 (★☆☆)
* **預估效能改進**：消除 View 樹層級檢測中的重複 Class 名稱字串比對與繼承鏈遞迴。
* **優化前**：`NewsfeedFilterHook` 尋找主頁 Feed 容器時，會在多層 View 樹上重複沿著 `cls.superclass` 遞迴檢查 Class 名稱。
* **優化後**：加入 `inheritsFromCache`，快取 `Class<*>` 對應目標類別名稱的判斷結果。

---

### 第四階段：記憶體配置與低階型別優化 (Memory Churn & Value Type Optimization)

#### 11. 移除 `FeedItemFacts` 中的 `Lazy` 委派物件分配
* **難易度**：中 (★★☆)
* **預估效能改進**：每 100 篇貼文過濾直接減少 400 個 `Lazy` 委派物件，記憶體垃圾生成量降低 80%。
* **優化前**：`FeedItemFacts` 使用了 Kotlin 的 `by lazy(LazyThreadSafetyMode.NONE)`。當一整個批次的 Feed 被評估時，每一個項目都會產生 4 個 `Lazy` 委派物件。
* **優化後**：移除了 `lazy` 委派，改用純手工的整數 `stateMask` (位元遮罩) 來追蹤狀態，徹底消除了數以千計的暫存物件。

#### 12. 減少 `FeedFilterEngine` 中的 Auto-Boxing (自動裝箱) 與 Map 操作
* **難易度**：低 (★☆☆)
* **預估效能改進**：熱點迴圈過濾時零 Auto-Boxing，記憶體計數操作速度提升 2~3 倍。
* **優化前**：`partition()` 方法在過濾每一篇貼文時，都會使用 `LinkedHashMap<String, Int>` 來累加各個規則的觸發次數，頻繁地將 `Int` 自動裝箱為 `Integer`。
* **優化後**：改用與規則數量相同大小的 `IntArray` 進行計數，直到整個批次過濾結束前才封裝回 Map。同時預先分配 `kept` 列表容量。

#### 13. 消除 AMOLED 顏色判斷中的基本型別 Auto-Boxing
* **難易度**：低 (★☆☆)
* **預估效能改進**：主題顏色轉換耗時降低 30%，完全消除 `Integer` 裝箱。
* **優化前**：`color in KNOWN_LITERAL_BACKGROUNDS` 中 `KNOWN_LITERAL_BACKGROUNDS` 是 `Set<Int>`，導致原始型別 `Int` 的 `color` 被自動裝箱為 `java.lang.Integer`。
* **優化後**：改用原生的 `when (color)` 分支匹配，直接進行 primitive 值比對。

#### 14. 在 `FeedContentRules` 快取全域關鍵字設定
* **難易度**：低 (★☆☆)
* **預估效能改進**：避免每次批次過濾重複執行 `split` / `lowercase` 字串切割，節省記憶體配置。
* **優化前**：每次 Feed pipeline 進行評估時，`config()` 都會重新讀取關鍵字字串並呼叫 `split()`、`trim()` 以及 `lowercase()`。
* **優化後**：解析後的 List 永久快取，僅在底層原始字串變更時才重新解析。

#### 15. 清除全域設定與 AI 檢查中的臨時陣列/Set 配置
* **難易度**：低 (★☆☆)
* **預估效能改進**：常數級（O(1)）零記憶體配置存取。
* **優化前**：`Settings.getBoolean` 每次被呼叫都會執行 `setOf(...)`；`AiTransparencyInspector.isPrimaryStoryAi` 每次檢查都會執行 `arrayOf(...)`。
* **優化後**：將兩處抽離為單例/靜態常數（`MARKETPLACE_GAME_KEYS` 與 `AI_HOLDER_HASH_PAIRS`）。

#### 16. 避免在文字比對中產生字串配置 (String Allocation)
* **難易度**：低 (★☆☆)
* **預估效能改進**：比對文字不生成新小寫字串，避免 `CharSequence.toString()` 的記憶體配置。
* **優化前**：`isAdSignalText()` 呼叫 `.lowercase()` 產生新小寫字串；`isAiLabel()` 對 `CharSequence` 呼叫 `.toString()`。
* **優化後**：`isAdSignalText` 改用 `contains(token, ignoreCase = true)`；`isAiLabel` 針對 `CharSequence` 的長度與 `startsWith()` 進行直接匹配。