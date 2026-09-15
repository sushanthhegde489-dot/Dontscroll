# Dontscroll — Changelog & Google Play Store Compliance Guide

This document details all the changes made to the **Dontscroll** codebase to achieve 100% Google Play Store compliance, fix logical bugs, eliminate ANR (Application Not Responding) risks, optimize performance, and clean up redundant code.

---

## 1. Executive Summary

| Category | Issue / Area | Status | Impact |
| :--- | :--- | :--- | :--- |
| **Play Store Policy** | Accessibility Service Prominent Disclosure | **Resolved** | Eliminates primary rejection reason under Play Store Accessibility Tool policy. |
| **Play Store Policy** | External Donation Link ("Buy Me a Coffee") | **Resolved** | Removed external payment mechanism violating Google Play Payments policy. |
| **Play Store Policy** | Data Extraction & Backup Rules | **Resolved** | Android 12+ backup and restore declarations added to Manifest. |
| **Play Store Policy** | Accessibility Service Configuration | **Resolved** | Added `android:settingsActivity` and clear privacy-focused description. |
| **UX & Navigation** | User Trapped on Intervention Screen | **Resolved** | Back button now returns to Home screen; added "I won't scroll · Go Home" exit option. |
| **UX & Architecture** | Task Affinity & Recents Leaks | **Resolved** | Isolated `InterventionActivity` with `taskAffinity=""` and `excludeFromRecents="true"`. |
| **Bug Fix** | Permission Refresh on Return from Settings | **Resolved** | Added `Lifecycle.Event.ON_RESUME` observer to immediately detect granted permissions. |
| **Performance / ANR** | Main-Thread Usage Statistics Queries | **Resolved** | Offloaded heavy `UsageStatsManager` queries to `Dispatchers.IO`. |
| **Performance / Battery** | 1-Second Continuous Usage Query Loop | **Resolved** | Replaced 1-second busy loop with asynchronous one-time fetch on screen entry. |
| **Code Cleanup** | Dead Utility Files & Leftover Backups | **Resolved** | Deleted 5 unused files, 3 empty directories, and test strings. |

---

## 2. Google Play Store Compliance Fixes

### A. Prominent Disclosure for Accessibility API (Critical)
* **Policy Requirement:** Google Play requires apps using Android's `AccessibilityService` API to present an in-app prominent disclosure before the user navigates to the Android Accessibility settings screen. The disclosure must explain what data is accessed, how it is used, that no personal data is collected or transmitted, and require affirmative user consent ("Agree & Enable").
* **Implementation:**
  * Added a dedicated disclosure modal in `MainActivity.kt` triggered when the user taps "Accessibility Service".
  * Users must tap **"Agree & Enable"** before being directed to Android Settings.
  * Added a clear explanation:
    * *Data Accessed:* Foreground application package names.
    * *Purpose:* Strictly used to trigger the delay waiting screen on protected applications.
    * *Privacy Guarantee:* Dontscroll does NOT collect, read, store, or transmit personal data, keystrokes, or screen contents.

### B. Accessibility Service Configuration (`accessibility_service_config.xml`)
* Added `android:settingsActivity="com.sushanth.dontscroll.MainActivity"`, allowing users to tap "Settings" directly from Android's Accessibility settings page.
* Updated `accessibility_service_description` in `strings.xml` to explicitly describe the app's function and affirm that no personal data is monitored or shared.

### C. Google Play Payments Policy Compliance
* **Violation Removed:** Removed the external Buy Me a Coffee button and URL (`https://buymeacoffee.com/idkagn`).
* **Policy Context:** Google Play Payments Policy explicitly prohibits directing users outside the application to alternate payment mechanisms (donations, tip jars, external links).
* **Replacement:** Replaced with an in-app **"Share App"** card (`ShareAppCard`) using Android's native share intent, enabling users to recommend Dontscroll to friends.

### D. Backup & Data Extraction Rules
* Updated `AndroidManifest.xml` with:
  * `android:dataExtractionRules="@xml/data_extraction_rules"`
  * `android:fullBackupContent="@xml/backup_rules"`
  Complies with modern Android 12+ (API 31+) cloud backup and restore policies.

### E. R8 Minification & ProGuard Rules
* Updated `app/src/main/keepRules/rules.keep` with keep rules for Room Database entities (`BlockedApp`), DAOs (`BlockedAppDao`), and `AppDatabase`. This prevents R8 from obfuscating or stripping Room table schemas when generating release builds.

---

## 3. Bug Fixes & Logical Improvements

### A. Intervention Screen Back Navigation Trap
* **Previous Behavior:** `InterventionActivity` registered an empty `OnBackPressedCallback(true)` that swallowed all back gestures. If users decided they did not want to wait for the distracting app, they were trapped and unable to back out without pressing the physical/system Home button.
* **Fix:**
  * Pressing Back now gracefully launches the device Home screen (`Intent.ACTION_MAIN`, `Intent.CATEGORY_HOME`) and finishes the intervention task.
  * Added an explicit **"I won't scroll · Go Home"** text button below the timer so users who make the healthy choice to abandon opening the app can exit immediately.

### B. InterventionActivity Isolation & Recents Clutter
* **Previous Behavior:** `InterventionActivity` had default task affinity and lacked `excludeFromRecents`. This meant:
  1. It appeared in the user's Android Recents / App Switcher overview.
  2. If tapped from Recents without intent extras, it closed immediately.
  3. Calling `finishAndRemoveTask()` risked closing `MainActivity` if they shared the same task stack.
* **Fix:** Added `android:taskAffinity=""`, `android:excludeFromRecents="true"`, and `android:launchMode="singleTop"` in `AndroidManifest.xml`. The intervention screen is now completely isolated in its own ephemeral task.

### C. Permission State Refresh on App Resume
* **Previous Behavior:** Permission states were only refreshed if the Activity Result launcher returned a result. If a user switched to Settings, toggled the permission, and returned via task switcher or swipe gesture, `MainActivity` remained stuck on `RequiredPermissionsScreen`.
* **Fix:** Added a `Lifecycle.Event.ON_RESUME` observer via `DisposableEffect` that automatically re-evaluates `isAccessibilityServiceEnabled()` and `hasUsageAccess()` whenever the application returns to the foreground.

### D. Copy & Tone Cleanup
* Cleaned up draft and informal strings:
  * Removed `"(unless you're aysh lolll)"` from the About section text.
  * Replaced `"Are you really sure, pakka??"` with `"Are you really sure?"` in `DoubleConfirmDialog`.
  * Changed the default custom delay in `DelayDialog` from the test value of `3` seconds to a realistic default of `15` seconds.

---

## 4. Performance Optimizations & ANR Prevention

### A. Asynchronous Usage Statistics Loading
* **Issue:** In `MainActivity.kt`, `ScreenTimeManager.getTodayUsage(context)` was previously called inside `remember(refresh)` on the Main UI thread. This queried thousands of `UsageEvents` and iterated `PackageManager` synchronously, causing frame drops, jank, and Android Vitals ANR warnings.
* **Optimization:** `usageList` is now loaded asynchronously inside a `LaunchedEffect` dispatched to `Dispatchers.IO`.

### B. Single-App Usage Query Optimization
* **Issue:** `ScreenTimeManager.getAppTodayUsage(context, packageName)` previously called `getTodayUsage(context)`, which queried and filtered every launcher application installed on the phone just to get the usage for one package.
* **Optimization:** `getAppTodayUsage` now directly extracts usage for the target package from event timestamps, skipping all unnecessary package queries.

### C. Removal of 1-Second Main-Thread Query Loop
* **Issue:** In `InterventionActivity.kt`, a `while (true)` loop was querying `getAppTodayUsage()` every single second on the main thread while the countdown was active. Because the blocked app is paused behind the intervention screen, screen time does not change during countdown.
* **Optimization:** Replaced the continuous loop with a single asynchronous calculation upon screen entry on `Dispatchers.IO`, saving battery and CPU cycles.

### D. Service-Side Calculation on Background Thread
* In `DoomGuardAccessibilityService.kt`, `getEffectiveUnlockDelaySeconds(blocked)` is now calculated on `Dispatchers.IO` before switching to `Dispatchers.Main.immediate` to launch the intervention.

---

## 5. Dead Code Removal

The following unused, redundant, and obsolete files and directories were removed:

1. `app/src/main/java/com/sushanth/dontscroll/data/InstalledApps.kt.bs` *(Obsolete backup file)*
2. `app/src/main/java/com/sushanth/dontscroll/util/BlockingManager.kt` *(Unused; all blocking logic is managed in `DoomGuardAccessibilityService`)*
3. `app/src/main/java/com/sushanth/dontscroll/util/TimerStateStore.kt` *(Unused remnant of early prototype)*
4. `app/src/main/java/com/sushanth/dontscroll/util/UnlockManager.kt` *(Unused duplicate SharedPreferences manager)*
5. `app/src/main/java/com/sushanth/dontscroll/util/UnlockSessionManager.kt` *(Unused duplicate session manager)*
6. `app/src/main/java/com/sushanth/dontscroll/blockers/` *(Empty directory)*
7. `app/src/main/java/com/sushanth/dontscroll/permissions/` *(Empty directory)*
8. `app/src/main/java/com/sushanth/dontscroll/services/` *(Empty directory)*

---

## 6. File-by-File Summary of Changes

### `app/src/main/AndroidManifest.xml`
* Added `android:dataExtractionRules="@xml/data_extraction_rules"`.
* Added `android:fullBackupContent="@xml/backup_rules"`.
* Configured `InterventionActivity` with `android:excludeFromRecents="true"`, `android:taskAffinity=""`, and `android:launchMode="singleTop"`.

### `app/src/main/res/xml/accessibility_service_config.xml`
* Added `android:settingsActivity="com.sushanth.dontscroll.MainActivity"`.

### `app/src/main/res/values/strings.xml`
* Updated `accessibility_service_description` to explicitly explain the purpose and privacy parameters.

### `app/src/main/keepRules/rules.keep`
* Added keep rules for Room Database entities, DAOs, and data models to safeguard against R8 minification stripping.

### `app/src/main/java/com/sushanth/dontscroll/MainActivity.kt`
* Added Prominent Disclosure dialog for the Accessibility Service.
* Added `DisposableEffect` observing `Lifecycle.Event.ON_RESUME` to automatically refresh permission states.
* Offloaded `ScreenTimeManager.getTodayUsage` to `Dispatchers.IO`.
* Replaced `SupportDeveloperCard` (external Buy Me a Coffee link) with compliant `ShareAppCard`.
* Cleaned up personal comments and draft strings in About and confirmation dialogs.
* Set default custom unlock delay to 15 seconds in `DelayDialog`.
* Removed unused imports.

### `app/src/main/java/com/sushanth/dontscroll/ui/InterventionActivity.kt`
* Updated `onBackPressedDispatcher` callback to navigate to Home screen and finish task.
* Added `onDismiss` callback and "I won't scroll · Go Home" exit button.
* Eliminated the 1-second busy loop on the main thread for screen time querying.

### `app/src/main/java/com/sushanth/dontscroll/service/DoomGuardAccessibilityService.kt`
* Moved `getEffectiveUnlockDelaySeconds` calculation to `Dispatchers.IO` before switching to `Dispatchers.Main.immediate`.
* Optimized `getEffectiveUnlockDelaySeconds` to use the optimized `ScreenTimeManager.getAppTodayUsage`.

### `app/src/main/java/com/sushanth/dontscroll/util/ScreenTimeManager.kt`
* Optimized `getAppTodayUsage` to calculate foreground duration directly without iterating all installed package launcher activities.

### `app/src/main/java/com/sushanth/dontscroll/data/AppDiscovery.kt`
* Modernized `queryIntentActivities` to use `PackageManager.ResolveInfoFlags.of(0)` on Android 13+ (API 33+).

---

## 7. Google Play Console Submission Checklist

When submitting the app update in Google Play Console, ensure the following form sections are completed as described:

1. **App Access / Accessibility Tool Declaration**:
   * *Question:* Does your app use the AccessibilityService API?
   * *Answer:* **Yes**.
   * *Category:* **User Wellbeing / Screen Time Management**.
   * *Declaration:* State that Dontscroll uses `AccessibilityService` solely to detect when protected apps are opened in order to display a countdown intervention delay. Emphasize that it does not monitor keystrokes, read screen contents, or collect/transmit any user data.
2. **Data Safety Form**:
   * *Data Collected:* **None**. (All screen time calculations and blocked app settings are processed and stored locally on the device using Room and SharedPreferences).
   * *Data Shared with Third Parties:* **No**.
3. **Financial / In-App Purchases**:
   * Confirm that the app has no in-app purchases and contains no external payment links (Buy Me a Coffee was removed).
4. **Privacy Policy URL**:
   * Provide an active public URL in the Play Console listing pointing to your privacy policy (matching the in-app policy terms).
