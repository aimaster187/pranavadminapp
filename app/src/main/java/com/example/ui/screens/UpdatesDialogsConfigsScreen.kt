package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.example.ui.components.PranavStatusPill
import com.example.ui.theme.LocalPranav3D

@Composable
fun UpdateManagerScreen(state: AdminUiState, vm: AdminViewModel) {
  val tokens = LocalPranav3D.current
  var appId by remember { mutableStateOf(state.apps.firstOrNull()?.id ?: "") }
  var verName by remember { mutableStateOf("1.1.0") }
  var verCode by remember { mutableStateOf("2") }
  var minCode by remember { mutableStateOf("1") }
  var url by remember { mutableStateOf("https://cdn.pranav.example.com/app-release.apk") }
  var notes by remember { mutableStateOf("Performance and security enhancements.") }
  var forceUpdate by remember { mutableStateOf(false) }
  var showLater by remember { mutableStateOf(true) }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("update_manager_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard(accentColor = tokens.accentIce) {
        Text("Publish App Update", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = appId, onValueChange = { appId = it }, label = { Text("Target App ID") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = verName, onValueChange = { verName = it }, label = { Text("Latest Ver") }, modifier = Modifier.weight(1f))
          OutlinedTextField(value = verCode, onValueChange = { verCode = it }, label = { Text("Code") }, modifier = Modifier.weight(1f))
          OutlinedTextField(value = minCode, onValueChange = { minCode = it }, label = { Text("Min Code") }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Download HTTPS URL") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("What's New") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Force Update")
          Switch(checked = forceUpdate, onCheckedChange = { forceUpdate = it })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Show 'Later' Button")
          Switch(checked = showLater, onCheckedChange = { showLater = it })
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
          onClick = {
            vm.createUpdate(
              appId.trim(),
              verName.trim(),
              verCode.toIntOrNull() ?: 2,
              minCode.toIntOrNull() ?: 1,
              url.trim(),
              notes.trim(),
              forceUpdate,
              showLater,
            )
          },
          enabled = appId.isNotBlank() && url.startsWith("http"),
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text("Publish Update Configuration")
        }
      }
    }

    items(state.updates, key = { it.id }) { u ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text("v${u.latestVersionName} (${u.latestVersionCode}) • App ${u.appId}", style = MaterialTheme.typography.titleMedium)
            Text("Min Code: ${u.minVersionCode} • Rollout: ${u.rolloutPercentage}% • Later: ${u.showLaterButton == 1}", style = MaterialTheme.typography.bodyMedium)
          }
          PranavStatusPill(label = u.updateMode.uppercase(), isHealthy = u.updateMode == "normal", isWarning = u.updateMode == "force")
        }
      }
    }
  }
}

@Composable
fun UniversalDialogManagerScreen(state: AdminUiState, vm: AdminViewModel) {
  val tokens = LocalPranav3D.current
  val dialogTypes = listOf("custom_popup", "optional", "force", "maintenance", "update", "channel_join", "custom_toast", "announcement")
  var selectedType by remember { mutableStateOf("custom_popup") }
  var title by remember { mutableStateOf("") }
  var desc by remember { mutableStateOf("") }
  var btnLabel by remember { mutableStateOf("Continue") }
  var btnUrl by remember { mutableStateOf("") }
  var priority by remember { mutableStateOf("80") }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("dialog_manager_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard(accentColor = tokens.accentPurple) {
        Text("Universal Dialog Engine", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          items(dialogTypes) { t ->
            FilterChip(selected = selectedType == t, onClick = { selectedType = t }, label = { Text(t) })
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Dialog Title") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = btnLabel, onValueChange = { btnLabel = it }, label = { Text("Button Label") }, modifier = Modifier.weight(1f))
          OutlinedTextField(value = priority, onValueChange = { priority = it }, label = { Text("Priority") }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = btnUrl, onValueChange = { btnUrl = it }, label = { Text("Optional Action URL / Channel Link") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = {
            vm.createDialog(selectedType, title.trim(), desc.trim(), btnLabel.trim(), btnUrl.trim(), priority.toIntOrNull() ?: 50)
            title = ""
            desc = ""
          },
          enabled = title.isNotBlank() && desc.isNotBlank(),
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text("Deploy Universal Dialog")
        }
      }
    }

    items(state.dialogs, key = { it.id }) { dlg ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text(dlg.title, style = MaterialTheme.typography.titleMedium)
            Text("${dlg.dialogType} • Priority ${dlg.priority}", style = MaterialTheme.typography.labelSmall, color = tokens.accentIce)
            Text(dlg.description, style = MaterialTheme.typography.bodyMedium)
          }
          PranavStatusPill(label = if (dlg.isEnabled == 1) "ON" else "OFF", isHealthy = dlg.isEnabled == 1)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.toggleDialog(dlg.id, dlg.isEnabled != 1) }, modifier = Modifier.height(44.dp)) {
          Text(if (dlg.isEnabled == 1) "Disable Dialog" else "Enable Dialog")
        }
      }
    }
  }
}

@Composable
fun AnnouncementManagerScreen(state: AdminUiState, vm: AdminViewModel) {
  var title by remember { mutableStateOf("") }
  var body by remember { mutableStateOf("") }
  var pinned by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("announcement_manager_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard {
        Text("Publish Announcement", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Headline") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Announcement Body") }, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Pin to Top")
          Switch(checked = pinned, onCheckedChange = { pinned = it })
        }
        Button(
          onClick = {
            vm.createAnnouncement(title.trim(), body.trim(), pinned)
            title = ""
            body = ""
          },
          enabled = title.isNotBlank() && body.isNotBlank(),
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text("Broadcast Announcement")
        }
      }
    }
    items(state.announcements, key = { it.id }) { ann ->
      Pranav3DCard {
        Text(ann.title, style = MaterialTheme.typography.titleMedium)
        Text(ann.body, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
fun RemoteConfigScreen(state: AdminUiState, vm: AdminViewModel) {
  val tokens = LocalPranav3D.current
  var scope by remember { mutableStateOf("global") }
  var appId by remember { mutableStateOf("") }
  var flagKey by remember { mutableStateOf("feature_3d_overlay") }
  var flagVal by remember { mutableStateOf(true) }
  var banner by remember { mutableStateOf("") }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("remote_config_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard(accentColor = tokens.accentIce) {
        Text("Remote Configuration & Rollback", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("global", "app").forEach { sc ->
            FilterChip(selected = scope == sc, onClick = { scope = sc }, label = { Text(sc.uppercase()) })
          }
        }
        if (scope == "app") {
          OutlinedTextField(value = appId, onValueChange = { appId = it }, label = { Text("App ID") }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = flagKey, onValueChange = { flagKey = it }, label = { Text("Feature Flag Key") }, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Flag Enabled")
          Switch(checked = flagVal, onCheckedChange = { flagVal = it })
        }
        OutlinedTextField(value = banner, onValueChange = { banner = it }, label = { Text("Remote Text Value") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = { vm.publishRemoteConfig(scope, appId.ifBlank { null }, flagKey.trim(), flagVal, banner.trim()) },
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text("Publish New Config Version")
        }
      }
    }
    items(state.remoteConfigs, key = { it.id }) { cfg ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${cfg.scope.uppercase()} v${cfg.version}", style = MaterialTheme.typography.titleMedium)
            Text("Flags: ${cfg.featureFlagsJson}", style = MaterialTheme.typography.labelSmall)
          }
          PranavStatusPill(label = if (cfg.isActive == 1) "ACTIVE" else "ARCHIVED", isHealthy = cfg.isActive == 1)
        }
        if (cfg.isActive != 1) {
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedButton(onClick = { vm.rollbackRemoteConfig(cfg.id) }, modifier = Modifier.height(44.dp)) {
            Text("Rollback to v${cfg.version}")
          }
        }
      }
    }
  }
}

@Composable
fun DynamicModuleRegistryScreen(state: AdminUiState, vm: AdminViewModel) {
  val capabilities = listOf("feature_gate", "ui_banner", "dialog_trigger", "link_router", "telemetry_profile")
  var moduleKey by remember { mutableStateOf("") }
  var capability by remember { mutableStateOf("feature_gate") }
  var version by remember { mutableStateOf("1.0.0") }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("dynamic_modules_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard {
        Text("Declarative Dynamic Module Registry", style = MaterialTheme.typography.titleLarge)
        Text("Strictly scoped to built-in SDK capabilities. Zero arbitrary code download.", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          items(capabilities) { c ->
            FilterChip(selected = capability == c, onClick = { capability = c }, label = { Text(c) })
          }
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = moduleKey, onValueChange = { moduleKey = it }, label = { Text("Module Key") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Button(
          onClick = {
            vm.upsertDynamicModule(moduleKey.trim(), capability, version.trim())
            moduleKey = ""
          },
          enabled = moduleKey.isNotBlank(),
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text("Register Declarative Module")
        }
      }
    }
    items(state.dynamicModules, key = { it.id }) { mod ->
      Pranav3DCard {
        Text("${mod.moduleKey} (${mod.capabilityType})", style = MaterialTheme.typography.titleMedium)
        Text("v${mod.version} • Enabled: ${mod.isEnabled == 1}", style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}
