# Facebook App Ads Remover - Optimization Roadmap (ToDo.md)

This document outlines planned performance, memory, reflection, and build optimizations for the codebase. All tasks must preserve 100% feature and parameter compatibility without altering business logic.

---

## Phase 1: Hot-Path & Allocation Optimizations

Target: Reduce GC pressure during high-frequency feed/reels scrolling.

- [ ] **1.1 Fast-Path Ad Signal Check in List Filtering**
  - **Target File**: `FeedHooks.kt` (`filterAdItems`)
  - **Task**: Check if the list contains any items before allocating temporary filtering data structures. If no items match ad signals, return early without creating new list instances.

- [ ] **1.2 Optimize String Matching in Token Lookups**
  - **Target Files**: `FeedHooks.kt`, `ReelsStoryHooks.kt`, `MarketplaceHooks.kt`
  - **Task**: Replace regex and repeated string operations in high-frequency UI marker lookups with direct `CharSequence.contains` or `indexOf` checks where applicable.

- [ ] **1.3 Lazy Evaluation for Diagnostic String Formatting**
  - **Target Files**: `FeedHooks.kt`, `GameAdsHooks.kt`, `ReelsStoryHooks.kt`
  - **Task**: Ensure heavy object tree string formatting (such as `describe(item)` or object graph traces) is strictly wrapped in `BuildConfig.DEBUG` checks or lazy lambdas so no intermediate strings are created in production builds.

---

## Phase 2: Reflection & Fast Accessor Caching Enhancements

Target: Minimize reflection lookup overhead during Litho rendering and Reels classification.

- [ ] **2.1 One-Time Method/Field Accessibility Pre-Setting**
  - **Target Files**: `FeedInspectors.kt`, `HookResolution.kt`, `PatchShared.kt`
  - **Task**: Guarantee `isAccessible = true` is invoked immediately upon reflection resolution before caching methods/fields into `ConcurrentHashMap`, removing redundant accessibility checks during execution.

- [ ] **2.2 Fast-Path Return for Unmatched Reels Items**
  - **Target File**: `PatchShared.kt` (`ReelsAdClassifier.classificationOf`)
  - **Task**: Ensure classes without accessor chains hit `accessorChainsCache` with `emptyList()` and return immediately without traversing candidate accessor hierarchies.

---

## Phase 3: Asynchronous Cache I/O & Persistence Optimizations

Target: Eliminate any potential UI-thread disk I/O when saving component or network guard caches.

- [ ] **3.1 Background Asynchronous Cache Serialization**
  - **Target Files**: `FeedHooks.kt`, `ReelsStoryHooks.kt`, `MarketplaceHooks.kt`
  - **Task**: Verify that all cache write routines (`saveFeedGuardCandidateCache`, `saveReelsGuardCache`, `saveMarketplaceNetGuardCache`) run asynchronously on background threads.

- [ ] **3.2 Redundant Cache Write Suppression**
  - **Target Files**: `FeedHooks.kt`, `ReelsStoryHooks.kt`, `MarketplaceHooks.kt`
  - **Task**: Skip atomic file writes if the cache contents have not changed since the last load or save operation.

---

## Phase 4: Build, R8/Proguard, and Method-Inlining Enhancements

Target: Reduce APK method count and optimize execution speed for utility helpers.

- [ ] **4.1 Utility Function Inlining**
  - **Target File**: `PatchShared.kt`
  - **Task**: Mark small, stateless utility functions (such as bitwise/hex conversions and simple string helpers) with the `inline` keyword.

- [ ] **4.2 Proguard / R8 Keep Rule Precision**
  - **Target File**: `app/proguard-rules.pro`
  - **Task**: Refine keep rules to target only essential entry points (`Module`, Xposed callbacks, DexKit JNI interfaces) while allowing R8 to dead-code eliminate and inline unused helper methods in release builds.

---

## Execution Plan & Guidelines

1. **Safety First**: Each phase must be executed independently with build verification (`app:assembleDebug` and `app:assembleRelease`).
2. **Zero Behavior Shift**: No function signatures, public/internal parameters, or filtering logic may be changed.
3. **Verification**: After completing each phase, verify that no `NullPointerException` or regression occurs in runtime logs.
