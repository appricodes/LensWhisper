# Login & purchases: removed from the app (2026-08-23)

Google Sign-In and RevenueCat/Play-Billing credit purchases were disconnected from the app on the
user's explicit request, to simplify the credit system down to a single path: watch a rewarded ad,
earn credits, identified anonymously per install (no account, no login screen). This file exists so
either can be restored later without re-deriving how they worked.

**Almost nothing was deleted** — only the navigation/call sites that *reached* the login/purchase
flows were removed or disabled; re-wiring those is mostly "put the removed lines back." The one
exception is `network/CreditsSyncClient.kt` / `backend/sync_credits.php`, which genuinely were
deleted — see "Later: merged into get_credits.php" below, since by the time that happened they had
already become 100% dead code (no callers left) rather than "disabled but restorable."

## What still exists, untouched, just unreachable from the UI

- `LoginActivity.kt` — full Google Sign-In + backend verification flow, still compiles standalone.
- `backend/login_verification.php` — the server side of the above.
- `backend/revenuecat_webhook.php` — purchase crediting (still calls into `users`, unchanged).
- `PreferencesManager.isLoggedIn`, `.userEmail`, `.googleId` — fields still exist, just never
  written to by anything reachable anymore (`LoginActivity.kt` still writes them, it's just never
  launched).
- RevenueCat's Gradle dependency (`app/build.gradle.kts`) and `REVENUECAT_PUBLIC_SDK_KEY` constant
  (`LensWhisperApplication.kt`) — left in place.

## Later: merged into get_credits.php (not a login/purchase-removal change, a cleanup pass after)

`backend/sync_credits.php` (account-only balance lookup, used to be polled after a RevenueCat
purchase) and `backend/get_credits.php` (anon-only balance lookup, added for the ad-credit system)
turned out to be near-duplicate files differing only in which table they queried. They were merged
into one `get_credits.php` that branches on identity exactly like `process.php` already did —
`sync_credits.php` no longer exists. Same on the Android side: `network/CreditsSyncClient.kt` (its
only caller, the RevenueCat purchase-success handler, had already been removed above, so it was
100% dead code by this point) was deleted; `network/AdCreditsClient.kt` now sends email+google_id
*and* install_id in every request the way `process.php`'s payload already did, so restoring login
later needs no changes here — the account path already works, it's just never populated with
real values while `email`/`google_id` stay blank.

## What was actually changed

| File | Change |
|---|---|
| `LensWhisperApplication.kt` | `Purchases.configure(...)` + the RevenueCat `logIn(email)` call are commented out (not deleted) in `onCreate`. Added `AdCreditsSession.startWarmup(...)`. |
| `MediaDescriberActivity.kt` | Removed the `if (!prefs.isLoggedIn) { ... LoginActivity ... }` gate that used to block the whole screen without login — it now opens directly, same as `CameraActivity`. |
| `ui/components/MoreMenu.kt` | "Open File" used to branch to `LoginActivity` with a `PENDING_INTENT` when logged out; now always opens `MediaDescriberActivity` directly. |
| `ui/components/ModelPickerPopup.kt` | `onOpenLogin: () -> Unit` param renamed to `onNeedCredits: () -> Unit`; selecting "Online" while ineligible now opens the credits dialog instead of `LoginActivity`. Eligibility check dropped `prefs.isLoggedIn ||`. |
| `ui/components/ModelTopBar.kt` | Wires `ModelPickerPopup`'s `onNeedCredits` to its own `showCreditsDialog` state instead of launching `LoginActivity`. |
| `ui/analysis/AnalysisSessionController.kt` | `OnlineGateDialog(onLogin, onGetCredits)` → `OnlineGateDialog(onGetCredits)`, dropped the "Login" button entirely. `checkOnlineEligibleOrGate` dropped `prefs.isLoggedIn ||`. |
| `CameraActivity.kt` / `ui/screens/MediaDescriberScreen.kt` | Dropped the `onLogin = { ...LoginActivity... }` argument at their `controller.OnlineGateDialog(...)` call sites. |
| `ModelManagementActivity.kt` | `OnlineDescriberCard` dropped its `onLogin`/"Login" button entirely, keeps only "Get Credits". `eligibleForOnline` dropped `prefs.isLoggedIn ||`. |
| `ui/components/CreditsDialog.kt` | Fully rewritten — the whole RevenueCat purchase-packages section (offerings fetch, `buyPackage`, package list UI) is gone. Now shows only the ad-watch flow (no loading gate — installId is always available synchronously, see below) and a manual "Update Credits" button. If you're restoring purchases, the old version is in git history (this file, before this date) — the ad-watch section can stay as-is, purchasing is fully additive. |
| `ads/RewardedAdManager.kt` | Removed the daily watch cap (`MAX_ADS_PER_DAY`, `adsWatchedToday`, `canWatchAd`) entirely — this was a separate, later request ("remove the daily limit"), not part of the login/purchase removal. Added `ServerSideVerificationOptions` wiring — unrelated to login removal, see the new anonymous-credit design below. |
| `data/PreferencesManager.kt` | Removed `adWatchDate`/`adsWatchedToday` (daily-cap fields, now unused). Added `installId`. `isLoggedIn` field itself was left in place. |

## The new anonymous credit system (why login isn't strictly needed to restore)

This is genuinely new, not a restoration of anything — see `backend/get_credits.php`,
`backend/admob_ssv_callback.php`, `backend/process.php` (now branches on `install_id` when
`email`/`google_id` are blank), `ads/AdCreditsSession.kt`, `network/AdCreditsClient.kt`. It
identifies a device anonymously via a random UUID (`PreferencesManager.installId`, generated once
and stored permanently — no server round trip needed to obtain or confirm it) instead of a Google
account, and is what credits/spends the Online Describer's balance today. That same installId is
what's handed to AdMob directly as `ServerSideVerificationOptions.customData` and what
`admob_ssv_callback.php`/`process.php` match against `install_credits.install_id` — there's no
separate server-assigned numeric id in this design (an earlier version of this feature had one;
simplified away since installId already serves the same purpose without an extra fetch).

If login comes back, the natural integration point is `process.php`'s existing `$identity ===
'account'` branch (untouched, still there) — a returning logged-in user would go through that path
exactly as before RevenueCat/login were disconnected. The two identity systems (`users` table vs.
`install_credits` table) are independent and don't need to be merged for both to work side by side.

## To restore login

1. Uncomment the RevenueCat block in `LensWhisperApplication.kt`.
2. Re-add navigation to `LoginActivity` at the removed call sites above (each row names exactly
   what was replaced).
3. Re-add `prefs.isLoggedIn ||` to the three eligibility checks (`ModelPickerPopup.kt`,
   `AnalysisSessionController.kt`, `ModelManagementActivity.kt`).
4. Decide whether to bring back the RevenueCat purchase-packages UI in `CreditsDialog.kt` (check
   git history for this file's pre-removal version) — independent of login itself.
