package com.appricodes.lens_whisper.ui.components

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appricodes.lens_whisper.ModelManagementActivity
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.network.CreditsSyncClient
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.purchaseWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Model name + remaining-credits bar shared by Advanced Camera, the Video/Image describer, and
 * Settings. Tapping the model name opens Manage Advanced AI Models; tapping the credits count
 * opens the purchase dialog (backed by RevenueCat — see REVENUECAT_SETUP.md).
 */
@Composable
fun ModelCreditsHeader(prefs: PreferencesManager, modifier: Modifier = Modifier, backgroundColor: Color = Color.Black) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineModelsRepository = remember { OfflineModelsRepository(context) }

    var selectedModelName by remember { mutableStateOf("") }
    var totalCredits by remember { mutableStateOf(0) }
    var showCreditsDialog by remember { mutableStateOf(false) }

    fun refresh() {
        selectedModelName = if (prefs.selectedModelId == "online") {
            "Online Describer"
        } else {
            offlineModelsRepository.loadModels().find { it.id == prefs.selectedModelId }?.displayName ?: "No Model Selected"
        }
        totalCredits = prefs.freeCredits + prefs.purchasedCredits
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = selectedModelName,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .clickable { context.startActivity(Intent(context, ModelManagementActivity::class.java)) }
                .clearAndSetSemantics { contentDescription = "Selected model: $selectedModelName. Double tap to change." }
        )
        Button(onClick = { showCreditsDialog = true }) {
            Text("$totalCredits Credits")
        }
    }

    if (showCreditsDialog) {
        CreditsPurchaseDialog(
            prefs = prefs,
            totalCredits = totalCredits,
            onDismiss = { showCreditsDialog = false },
            onCreditsChanged = { refresh() }
        )
    }
}

@Composable
private fun CreditsPurchaseDialog(
    prefs: PreferencesManager,
    totalCredits: Int,
    onDismiss: () -> Unit,
    onCreditsChanged: () -> Unit
) {
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
                onDismiss()
                // Crediting happens server-side (backend/revenuecat_webhook.php) once RevenueCat
                // notifies it, which can lag a few seconds behind this callback — poll briefly
                // rather than trusting the client-side purchase result to know the new total.
                coroutineScope.launch {
                    repeat(5) {
                        delay(2000)
                        if (CreditsSyncClient.syncCredits(prefs)) {
                            onCreditsChanged()
                            return@launch
                        }
                    }
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Purchase Credits") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("You have $totalCredits credits remaining.", fontSize = 15.sp, color = Color.Gray)
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
                HorizontalDivider()
                Text(
                    "Purchases are processed by Google Play. Credits are added automatically once the purchase is confirmed.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
