package com.pranav.samplehost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pranav.hook.PranavHook
import com.pranav.sdk.PranavSdk
import com.pranav.sdk.PranavUniversalDialogHost
import kotlinx.coroutines.launch

class SampleHostActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme {
        SampleHostScreen()
      }
    }
  }
}

@Composable
fun SampleHostScreen() {
  val status by PranavSdk.statusFlow.collectAsStateWithLifecycle()
  val snapshot by PranavSdk.snapshotFlow.collectAsStateWithLifecycle()
  val hookStatus by PranavHook.statusFlow.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var licenseKey by remember { mutableStateOf("") }
  var licenseResult by remember { mutableStateOf<String?>(null) }

  Scaffold { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("PRANAV Authorized Host Demo", style = MaterialTheme.typography.headlineSmall)
      Text(
        "Zero-login host application integrated with PRANAV SDK & PRANAV Hook.",
        style = MaterialTheme.typography.bodyMedium,
      )

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("SDK State: ${status.state.name}", style = MaterialTheme.typography.titleMedium)
          Text("Hook Attached: ${hookStatus.attached} (Activities: ${hookStatus.activeActivities})")
          Text("Device ID: ${status.deviceId.ifBlank { "Pending registration" }}")
          Text("Licensed: ${status.isLicensed} | VIP: ${status.isVip}")
          status.errorMessage?.let { Text("Status Note: $it") }
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { scope.launch { PranavSdk.connect() } },
          modifier = Modifier.testTag("host_connect_button"),
        ) {
          Text("Sync PRANAV")
        }
        OutlinedButton(
          onClick = { scope.launch { PranavSdk.refreshConfig() } },
          modifier = Modifier.testTag("host_refresh_button"),
        ) {
          Text("Refresh Config")
        }
      }

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("License Activation", style = MaterialTheme.typography.titleMedium)
          OutlinedTextField(
            value = licenseKey,
            onValueChange = { licenseKey = it },
            label = { Text("License Key") },
            modifier = Modifier.fillMaxWidth().testTag("host_license_input"),
            singleLine = true,
          )
          Button(
            onClick = {
              scope.launch {
                val res = PranavSdk.activateLicense(licenseKey)
                licenseResult = res.fold(onSuccess = { "Activated: $it" }, onFailure = { it.message ?: "Failed" })
              }
            },
            enabled = licenseKey.isNotBlank(),
            modifier = Modifier.testTag("host_activate_license_button"),
          ) {
            Text("Activate")
          }
          licenseResult?.let { Text(it) }
        }
      }

      if (snapshot.announcements.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Announcements", style = MaterialTheme.typography.titleMedium)
            snapshot.announcements.forEach { ann ->
              Text("${ann.title}: ${ann.body}", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
      Spacer(modifier = Modifier.height(12.dp))
    }
  }

  PranavUniversalDialogHost(
    snapshot = snapshot,
    onDismissDialog = { PranavSdk.dismissLocalDialog(it) },
    onUpdateAction = { upd, action ->
      scope.launch { PranavSdk.confirmUpdate(upd.id, action) }
    },
  )
}
