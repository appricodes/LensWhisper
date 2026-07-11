package com.appricodes.lens_whisper

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class LoginActivity : ComponentActivity() {
	private lateinit var prefs: PreferencesManager
	private val client = OkHttpClient()

	private val dialogMessageState = mutableStateOf<String?>(null)
	// Hoist the loading state to the Activity so the network call can control it
	private val isLoadingState = mutableStateOf(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		prefs = PreferencesManager(this)

		setContent {
			LensWhisperTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					LoginScreen(
						isLoading = isLoadingState.value,
						onSetLoading = { isLoadingState.value = it },
						onOfflineSelected = {
							AccessibilityUtils.announceToTalkBack(this, "Continuing offline.")
							finish()
						},
						onGoogleSignIn = { token ->
							verifyLogin(token)
						}
					)
				}

				val msg = dialogMessageState.value
				if (msg != null) {
					AlertDialog(
						onDismissRequest = { dialogMessageState.value = null },
						title = { Text("Login Error") },
						text = { Text(msg, fontSize = 18.sp) },
						confirmButton = {
							Button(onClick = { dialogMessageState.value = null }) { Text("OK") }
						}
					)
				}
			}
		}
	}

	private fun verifyLogin(idToken: String) {
		CoroutineScope(Dispatchers.IO).launch {
			try {
				val requestBody = FormBody.Builder()
					.add("id_token", idToken)
					.build()

				val request = Request.Builder()
					.url("https://www.wikiponder.com/lense_whisper/login_verification.php")
					.post(requestBody)
					.build()

				client.newCall(request).execute().use { response ->
					val responseBody = response.body?.string()

					// LOG THE RAW RESPONSE FROM THE SERVER HERE
					Log.d("LoginActivity", "Server Response: $responseBody")

					if (response.isSuccessful && responseBody != null) {
						try {
							val json = JSONObject(responseBody)
							val success = json.optBoolean("success", false)
							val email = json.optString("email", "")
							val googleId = json.optString("google_id", "")
							val freeCredits = json.optInt("free_credits", 0)
							val purchasedCredits = json.optInt("purchased_credits", 0)

							if (success) {
								withContext(Dispatchers.Main) {
									isLoadingState.value = false // Stop progress bar
									prefs.isLoggedIn = true
									prefs.userEmail = email
									prefs.googleId = googleId
									prefs.freeCredits = freeCredits
									prefs.purchasedCredits = purchasedCredits
									AccessibilityUtils.announceToTalkBack(this@LoginActivity, "Login Successful.")
									Toast.makeText(this@LoginActivity, "Login Successful! Credits updated.", Toast.LENGTH_SHORT).show()
									finish()
								}
								return@launch
							} else {
								val errorMsg = json.optString("message", "Verification rejected by server.")
								withContext(Dispatchers.Main) {
									isLoadingState.value = false // Stop progress bar
									AccessibilityUtils.announceToTalkBack(this@LoginActivity, "Login Failed.")
									dialogMessageState.value = errorMsg
								}
								return@launch
							}
						} catch (_: Exception) {
							// JSON parsing failed - fall through to error
						}
					}

					// Handles !response.isSuccessful or invalid JSON
					val errJson = responseBody?.let { runCatching { JSONObject(it) }.getOrNull() }
					val errorMsg = errJson?.optString("message") ?: "Verification failed. HTTP ${response.code}"

					withContext(Dispatchers.Main) {
						isLoadingState.value = false // Stop progress bar
						AccessibilityUtils.announceToTalkBack(this@LoginActivity, "Verification failed.")
						dialogMessageState.value = errorMsg
					}
				}
			} catch (e: Exception) {
				Log.e("LoginActivity", "Network error during login", e) // Also log the exception
				withContext(Dispatchers.Main) {
					isLoadingState.value = false // Stop progress bar
					AccessibilityUtils.announceToTalkBack(this@LoginActivity, "Network error.")
					dialogMessageState.value = "Network error: ${e.localizedMessage ?: "Connection failed"}"
				}
			}
		}
	}
}

@Composable
fun LoginScreen(
	isLoading: Boolean,
	onSetLoading: (Boolean) -> Unit,
	onOfflineSelected: () -> Unit,
	onGoogleSignIn: (String) -> Unit
) {
	val context = LocalContext.current

	val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
		.requestIdToken("201517037362-v0t9j5q235dlhekkhcqmhjevtkakgp02.apps.googleusercontent.com")
		.requestEmail()
		.build()

	val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
			try {
				val account = task.getResult(ApiException::class.java)
				account?.idToken?.let { token ->
					onGoogleSignIn(token)
				} ?: run { onSetLoading(false) } // Stop loading if token is null
			} catch (e: ApiException) {
				onSetLoading(false) // Stop loading on error
				Toast.makeText(context, "Google Sign In Interface Failed", Toast.LENGTH_SHORT).show()
			}
		} else {
			onSetLoading(false) // Stop loading if user cancels the dialog

			val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
			try {
				task.getResult(com.google.android.gms.common.api.ApiException::class.java)
			} catch (e: com.google.android.gms.common.api.ApiException) {
				Log.e("AuthError", "Google Sign-In failed. Status code: ${e.statusCode}")
			}
		}
	}

	Column(
		modifier = Modifier.fillMaxSize().padding(32.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Text(text = "Unlock Advanced Features!", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
		Spacer(modifier = Modifier.height(16.dp))
		Text(text = "Login to access our most advanced online AI models, unlock faster processing speeds, and get free daily credits to power your experience.", fontSize = 16.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
		Spacer(modifier = Modifier.height(48.dp))

		if (isLoading) {
			CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
		} else {
			Button(
				onClick = { onSetLoading(true); launcher.launch(googleSignInClient.signInIntent) },
				modifier = Modifier.fillMaxWidth().height(56.dp),
				colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
			) { Text("Login with Google", fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimary) }

			Spacer(modifier = Modifier.height(16.dp))
			OutlinedButton(onClick = onOfflineSelected, modifier = Modifier.fillMaxWidth().height(56.dp)) {
				Text("Continue with Offline", fontSize = 18.sp)
			}
		}
	}
}