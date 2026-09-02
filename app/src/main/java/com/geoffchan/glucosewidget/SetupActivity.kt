package com.geoffchan.glucosewidget

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val existing = Store.credentials(ctx)
        val settings = runBlocking { Store.settings(ctx) }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                androidx.compose.material3.Scaffold(
                    topBar = {
                        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                        androidx.compose.material3.TopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                androidx.compose.material3.IconButton(onClick = { finish() }) {
                                    androidx.compose.material3.Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Back",
                                    )
                                }
                            },
                        )
                    },
                ) { insets ->
                    var username by remember { mutableStateOf(existing?.first ?: "") }
                    var password by remember { mutableStateOf(existing?.second ?: "") }
                    var low by remember { mutableStateOf(settings.lowMmol.toString()) }
                    var high by remember { mutableStateOf(settings.highMmol.toString()) }
                    var status by remember { mutableStateOf("") }
                    val scope = rememberCoroutineScope()

                    Column(
                        Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Uses the wearer's Dexcom account (the login the G7 app uses). " +
                                "Credentials stay on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(username, { username = it }, label = { Text("Dexcom username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(low, { low = it }, label = { Text("Low (mmol/L)") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(high, { high = it }, label = { Text("High (mmol/L)") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Button(
                            onClick = {
                                status = "Testing…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { ShareClient.fetchLatest(username.trim(), password, null) }
                                    }
                                    result.fold(
                                        onSuccess = { (reading, session) ->
                                            Store.saveCredentials(ctx, username.trim(), password)
                                            Store.saveSession(ctx, session)
                                            Store.saveReading(ctx, reading)
                                            val s = Settings(
                                                low.toDoubleOrNull() ?: Store.DEFAULT_LOW,
                                                high.toDoubleOrNull() ?: Store.DEFAULT_HIGH,
                                            )
                                            Store.saveSettings(ctx, s)
                                            GlucoseWidget().updateAll(ctx)
                                            Refresh.scheduleNext(ctx)
                                            status = "OK — latest: ${mmolText(reading.mgdl)} " +
                                                trendArrow(reading.trend) + " (" +
                                                ageText(reading.timestampMs, System.currentTimeMillis()) + " ago). Saved."
                                        },
                                        onFailure = { status = "Failed: ${it.message}" },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Test connection & save") }
                        Text(status, style = MaterialTheme.typography.bodyMedium)

                        Text("Reliability", style = MaterialTheme.typography.titleMedium)
                        val am = getSystemService(AlarmManager::class.java)
                        val pm = getSystemService(PowerManager::class.java)
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (am.canScheduleExactAlarms()) "Exact alarms: allowed ✓"
                                else "Allow exact alarms (needed for 5-min updates)",
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (pm.isIgnoringBatteryOptimizations(packageName)) "Battery: unrestricted ✓"
                                else "Disable battery optimization",
                            )
                        }
                    }
                }
            }
        }
    }
}
