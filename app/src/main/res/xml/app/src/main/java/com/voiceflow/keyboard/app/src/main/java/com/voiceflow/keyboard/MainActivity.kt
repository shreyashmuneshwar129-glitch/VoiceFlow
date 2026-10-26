package com.voiceflow.keyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("voiceflow_prefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE53935),
                    secondary = Color(0xFFEF5350),
                    background = Color(0xFF0B0B0E),
                    surface = Color(0xFF18181F)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SetupScreen(prefs = prefs)
                }
            }
        }
    }
}

@Composable
fun SetupScreen(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var practiceText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "🎙️ VoiceFlow Keyboard",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Speak in English, Hinglish (Roman), or Marathi. VoiceFlow removes filler words, fixes mid-sentence corrections automatically, and gives you 1-tap AI writing tools.",
            fontSize = 14.sp,
            color = Color(0xFFB0B0C0)
        )

        // STEP 1: Microphone Permission
        StepCard(
            stepTitle = "Step 1: Microphone Access",
            stepDescription = if (hasMicPermission) {
                "✅ Microphone permission is granted!"
            } else {
                "VoiceFlow needs microphone access so you can speak into the keyboard."
            }
        ) {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                enabled = !hasMicPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (hasMicPermission) "Permission Granted ✓" else "Grant Microphone Permission")
            }
        }

        // STEP 2: Gemini API Key
        StepCard(
            stepTitle = "Step 2: Paste Your Free Gemini API Key",
            stepDescription = "Get your free key from aistudio.google.com, paste it below, and tap Save & Test."
        ) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Gemini API Key (starts with AIza...)") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showKey = !showKey },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (showKey) "Hide Key" else "Show Key")
                }

                Button(
                    onClick = {
                        val trimmed = apiKey.trim()
                        prefs.edit().putString("gemini_api_key", trimmed).apply()
                        isTesting = true
                        testStatus = "Testing connection to Gemini..."
                        scope.launch {
                            val result = GeminiClient.testApiKey(trimmed)
                            isTesting = false
                            testStatus = result.fold(
                                onSuccess = { "✅ Key Saved & Verified! ($it)" },
                                onFailure = { "❌ ${it.message}" }
                            )
                        }
                    },
                    enabled = !isTesting,
                    modifier = Modifier.weight(1.5f)
                ) {
                    Text("Save & Test Key")
                }
            }

            if (isTesting) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            if (testStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = testStatus,
                    fontSize = 13.sp,
                    color = if (testStatus.startsWith("✅")) Color(0xFF66BB6A) else Color(0xFFFF7043)
                )
            }
        }

        // STEP 3: Enable in Android Settings
        StepCard(
            stepTitle = "Step 3: Turn On VoiceFlow in Settings",
            stepDescription = "Tap the button below, find 'VoiceFlow' in the list, and toggle the switch ON."
        ) {
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Keyboard Settings")
            }
        }

        // STEP 4: Select VoiceFlow Keyboard
        StepCard(
            stepTitle = "Step 4: Switch Active Keyboard to VoiceFlow",
            stepDescription = "Tap below and choose 'VoiceFlow' from the popup list."
        ) {
            Button(
                onClick = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showInputMethodPicker()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3949AB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select VoiceFlow Keyboard")
            }
        }

        // STEP 5: Try it out right here
        StepCard(
            stepTitle = "Step 5: Test Your Voice Keyboard Here!",
            stepDescription = "Tap inside the box below. When VoiceFlow pops up at the bottom, tap the big Red Mic button, speak, and tap again to insert clean text!"
        ) {
            OutlinedTextField(
                value = practiceText,
                onValueChange = { practiceText = it },
                label = { Text("Tap here and try speaking or using toolbar buttons...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun StepCard(
    stepTitle: String,
    stepDescription: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181F))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stepTitle,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stepDescription,
                fontSize = 13.sp,
                color = Color(0xFFB0B0C0)
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
