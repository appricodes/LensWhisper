package com.appricodes.lens_whisper.ui.components

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.LoginActivity
import com.appricodes.lens_whisper.ads.RewardedAdManager
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.network.AdCreditsClient
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.purchaseWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Get more credits" dialog. Shared by [ModelTopBar]'s credits badge,
 * `AnalysisSessionController.OnlineGateDialog`'s "Get Credits" button, and
 * `ModelManagementActivity`'s Online card. Two independent ways to add credits:
 *   - Watch a rewarded ad ([RewardedAdManager.CREDITS_PER_AD] each, no daily cap, no account
 *     needed) — anonymous, keyed by [PreferencesManager.installId].
 *   - Sign in with Google (optional — nothing else in the app gates on it) to buy a credit pack via
 *     RevenueCat, or just to claim the one-time 100-credit trial bonus a brand-new account gets
 *     (see backend/login_verification.php). See LOGIN_AND_PURCHASES_REMOVED.md for how this was
 *     disconnected and reconnected.
 *
 * [PreferencesManager.installId] is generated once and stored permanently on-device, so there's no
 * server round trip needed just to open this dialog or to enable "Watch an Ad" — the balance shown
 * is whatever was last synced (by `LensWhisperApplication`'s startup/connectivity warmup, or an
 * earlier manual refresh); "Update Credits" here always hits the network fresh. The ad reward
 * itself is only actually granted once `backend/admob_ssv_callback.php` hears back from Google
 * (see [RewardedAdManager]'s doc) — asynchronously, hence the delayed re-check after watching.
 */
@Composable
fun CreditsDialog(prefs: PreferencesManager, onDismiss: () -> Unit, onCreditsChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    var totalCredits by remember { mutableStateOf(prefs.freeCredits + prefs.purchasedCredits) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isShowingAd by remember { mutableStateOf(false) }
    // LoginActivity writes straight to the same SharedPreferences before returning, so re-reading
    // prefs.isLoggedIn on the way back is enough — no result data needs passing through the intent.
    var isLoggedIn by remember { mutableStateOf(prefs.isLoggedIn) }

    suspend fun refresh() {
        isRefreshing = true
        if (AdCreditsClient.fetchCredits(prefs) != null) {
            totalCredits = prefs.freeCredits + prefs.purchasedCredits
            onCreditsChanged()
        }
        isRefreshing = false
    }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isLoggedIn = prefs.isLoggedIn
        if (isLoggedIn) coroutineScope.launch { refresh() }
    }

    LaunchedEffect(Unit) { RewardedAdManager.preload(context) }

    fun watchAd() {
        if (activity == null) return
        isShowingAd = true
        RewardedAdManager.showAd(
            activity = activity,
            installId = prefs.installId,
            onEarned = {
                isShowingAd = false
                Toast.makeText(
                    context,
                    "Thanks for watching! Your ${RewardedAdManager.CREDITS_PER_AD} credits will show up here in a few seconds.",
                    Toast.LENGTH_LONG
                ).show()
                // The actual grant happens async, server-side, once Google's SSV callback reaches
                // backend/admob_ssv_callback.php — this just re-polls after a plausible delay
                // rather than claiming it's already applied.
                coroutineScope.launch {
                    delay(10_000)
                    refresh()
                }
            },
            onUnavailable = {
                isShowingAd = false
                Toast.makeText(context, "No ad is ready right now — try again in a moment.", Toast.LENGTH_SHORT).show()
            },
            onDismissedWithoutReward = { isShowingAd = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get More Credits") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("You have $totalCredits credits remaining.", fontSize = 15.sp, color = Color.Gray)

                HorizontalDivider()

                Text("Watch a short ad to earn ${RewardedAdManager.CREDITS_PER_AD} free credits.", fontSize = 14.sp)
                Button(
                    onClick = { watchAd() },
                    enabled = activity != null && !isShowingAd,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isShowingAd) "Loading ad…" else "Watch an Ad (+${RewardedAdManager.CREDITS_PER_AD} credits)")
                }

                TextButton(
                    onClick = { coroutineScope.launch { refresh() } },
                    enabled = !isRefreshing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isRefreshing) "Updating…" else "Update Credits")
                }

                HorizontalDivider()

                if (isLoggedIn) {
                    Text("Signed in as ${prefs.userEmail}", fontSize = 13.sp, color = Color.Gray)
                    PurchasePackagesSection(prefs = prefs, onCreditsChanged = {
                        totalCredits = prefs.freeCredits + prefs.purchasedCredits
                        onCreditsChanged()
                    })
                } else {
                    Text(
                        "Sign in with Google to buy a credit pack, or just to claim a one-time 100-credit trial bonus.",
                        fontSize = 14.sp
                    )
                    OutlinedButton(
                        onClick = { loginLauncher.launch(Intent(context, LoginActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign in with Google")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Purchase-packages list, only ever shown once signed in — RevenueCat's `app_user_id` is the
 *  account's email (see [LoginActivity]/[com.appricodes.lens_whisper.LensWhisperApplication]), so a
 *  purchase made here is always tied to an account, never to an anonymous install. Adapted from the
 *  pre-removal `ModelCreditsHeader.CreditsPurchaseDialog` — see git history / LOGIN_AND_PURCHASES_REMOVED.md. */
@Composable
private fun PurchasePackagesSection(prefs: PreferencesManager, onCreditsChanged: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    var packages by remember { mutableStateOf<List<Package>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var purchasingPackageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        Purchases.sharedInstance.getOfferingsWith(
            onError = { error -> loadError = error.message },
            onSuccess = { offerings -> packages = offerings.current?.availablePackages ?: emptyList() }
        )
    }

    fun buyPackage(pkg: Package) {
        if (activity == null) return
        purchasingPackageId = pkg.identifier
        Purchases.sharedInstance.purchaseWith(
            PurchaseParams.Builder(activity, pkg).build(),
            onError = { error, userCancelled ->
                purchasingPackageId = null
                if (!userCancelled) Toast.makeText(context, "Purchase failed: ${error.message}", Toast.LENGTH_SHORT).show()
            },
            onSuccess = { _, _ ->
                purchasingPackageId = null
                Toast.makeText(context, "Purchase successful! Crediting your account…", Toast.LENGTH_LONG).show()
                // Crediting happens server-side (backend/revenuecat_webhook.php) once RevenueCat
                // notifies it, which can lag a few seconds behind this callback — poll briefly
                // rather than trusting the client-side purchase result to know the new total.
                coroutineScope.launch {
                    repeat(5) {
                        delay(2000)
                        if (AdCreditsClient.fetchCredits(prefs) != null) {
                            onCreditsChanged()
                            return@launch
                        }
                    }
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            loadError != null -> Text(
                "Couldn't load packages: $loadError",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error
            )
            packages == null -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            packages!!.isEmpty() -> Text("No credit packages are available right now.", fontSize = 14.sp, color = Color.Gray)
            else -> packages!!.forEach { pkg ->
                OutlinedButton(
                    onClick = { buyPackage(pkg) },
                    enabled = purchasingPackageId == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(pkg.product.title, fontWeight = FontWeight.Bold)
                            Text(pkg.product.price.formatted)
                        }
                        if (pkg.product.description.isNotBlank()) {
                            Text(pkg.product.description, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}
