package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.AdminUiState
import com.example.ui.AdminViewModel
import com.example.ui.components.Pranav3DCard
import com.example.ui.components.PranavConfirmDialog
import com.example.ui.components.PranavStatusPill
import com.example.ui.theme.LocalPranav3D

@Composable
fun AppManagerScreen(state: AdminUiState, vm: AdminViewModel) {
  val tokens = LocalPranav3D.current
  var name by remember { mutableStateOf("") }
  var pkg by remember { mutableStateOf("") }
  var manualApproval by remember { mutableStateOf(false) }
  var pendingDeleteId by remember { mutableStateOf<String?>(null) }

  pendingDeleteId?.let { id ->
    PranavConfirmDialog(
      title = "Delete Authorized App?",
      message = "Deleting app $id removes its installations, devices, and configurations.",
      confirmLabel = "Delete App",
      onConfirm = {
        vm.deleteApp(id)
        pendingDeleteId = null
      },
      onDismiss = { pendingDeleteId = null },
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("app_manager_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard(accentColor = tokens.accentIce) {
        Text("Register Authorized Host App", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("App Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("app_name_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = pkg,
          onValueChange = { pkg = it },
          label = { Text("Android Package Name (e.g. com.company.app)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("app_package_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Text("Require Manual Device Approval", style = MaterialTheme.typography.bodyMedium)
          Switch(checked = manualApproval, onCheckedChange = { manualApproval = it })
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = {
            vm.createApp(name.trim(), pkg.trim(), if (manualApproval) "manual" else "auto")
            name = ""
            pkg = ""
          },
          enabled = name.isNotBlank() && pkg.contains("."),
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("create_app_button"),
        ) {
          Text("Create App & Issue SDK Key")
        }
        state.lastIssuedAppKey?.let { key ->
          Spacer(modifier = Modifier.height(8.dp))
          Text("Issued App Secret Key (save in host config): $key", style = MaterialTheme.typography.labelSmall, color = tokens.accentPurple)
        }
      }
    }

    items(state.apps, key = { it.id }) { app ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text(app.name, style = MaterialTheme.typography.titleLarge)
            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = tokens.accentIce)
            Text("ID: ${app.id} • Installs: ${app.installationCount} • Licenses: ${app.activeLicenseCount}", style = MaterialTheme.typography.bodyMedium)
          }
          PranavStatusPill(label = app.status.uppercase(), isHealthy = app.status == "active")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { vm.setAppStatus(app.id, if (app.status == "active") "suspended" else "active") },
            modifier = Modifier.height(44.dp),
          ) {
            Text(if (app.status == "active") "Suspend" else "Activate")
          }
          OutlinedButton(
            onClick = { pendingDeleteId = app.id },
            modifier = Modifier.height(44.dp),
          ) {
            Text("Delete")
          }
        }
      }
    }
  }
}

@Composable
fun UserInstallationManagerScreen(state: AdminUiState, vm: AdminViewModel) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("user_manager_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard {
        Text("Userless Installations (${state.installations.size})", style = MaterialTheme.typography.titleLarge)
        Text("Host apps operate with zero login screens using cryptographic installation IDs.", style = MaterialTheme.typography.bodyMedium)
      }
    }
    items(state.installations, key = { it.id }) { inst ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${inst.appName} v${inst.appVersion}", style = MaterialTheme.typography.titleMedium)
            Text("Install ID: ${inst.id}", style = MaterialTheme.typography.labelSmall)
            Text("Last seen: ${inst.lastSeenAt}", style = MaterialTheme.typography.bodyMedium)
          }
          PranavStatusPill(label = inst.status.uppercase(), isHealthy = inst.status == "active")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = { vm.setInstallationStatus(inst.id, if (inst.status == "active") "blocked" else "active") },
          modifier = Modifier.height(44.dp),
        ) {
          Text(if (inst.status == "active") "Block Installation" else "Unblock Installation")
        }
      }
    }
  }
}

@Composable
fun DeviceManagerScreen(state: AdminUiState, vm: AdminViewModel) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("device_manager_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard {
        Text("Cryptographic Client Devices (${state.devices.size})", style = MaterialTheme.typography.titleLarge)
        Text("Every device authenticates via ECDSA keypair challenge-response.", style = MaterialTheme.typography.bodyMedium)
      }
    }
    items(state.devices, key = { it.id }) { dev ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${dev.hardwareModel} (Android ${dev.osVersion})", style = MaterialTheme.typography.titleMedium)
            Text("Key FP: ${dev.fingerprint.take(24)}...", style = MaterialTheme.typography.labelSmall)
            Text("Device ID: ${dev.id}", style = MaterialTheme.typography.labelSmall)
          }
          PranavStatusPill(label = dev.approvalStatus.uppercase(), isHealthy = dev.approvalStatus == "approved", isWarning = dev.approvalStatus == "pending")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { vm.setDeviceApproval(dev.id, "approved") }, modifier = Modifier.height(44.dp)) {
            Text("Approve")
          }
          OutlinedButton(onClick = { vm.setDeviceApproval(dev.id, "revoked") }, modifier = Modifier.height(44.dp)) {
            Text("Revoke")
          }
        }
      }
    }
  }
}
