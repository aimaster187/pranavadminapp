package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.LocalPranav3D

@Composable
fun MaintenanceLockdownScreen(isLockdownFocus: Boolean, state: AdminUiState, vm: AdminViewModel) {
  val global = state.maintenance.firstOrNull { it.scope == "global" }
  var maintOn by remember(global) { mutableStateOf(global?.isEnabled == 1) }
  var lockdownOn by remember(global) { mutableStateOf(global?.emergencyLockdown == 1) }
  var title by remember(global) { mutableStateOf(global?.title ?: "System Maintenance") }
  var msg by remember(global) { mutableStateOf(global?.message ?: "Services temporarily paused.") }
  var confirmLockdown by remember { mutableStateOf(false) }

  if (confirmLockdown) {
    PranavConfirmDialog(
      title = if (!lockdownOn) "Enable Emergency Lockdown?" else "Disable Emergency Lockdown?",
      message = "Global Emergency Lockdown immediately severs active SDK sessions and blocks all client traffic.",
      confirmLabel = if (!lockdownOn) "Activate Lockdown" else "Lift Lockdown",
      onConfirm = {
        val next = !lockdownOn
        lockdownOn = next
        vm.setMaintenance(maintOn || next, next, title, msg)
        confirmLockdown = false
      },
      onDismiss = { confirmLockdown = false },
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("maintenance_lockdown_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard(accentColor = if (isLockdownFocus || lockdownOn) CrimsonAlert else LocalPranav3D.current.accentIce) {
        Text(
          if (isLockdownFocus) "Emergency Lockdown Kill-Switch" else "Maintenance & Lockdown Control",
          style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        PranavStatusPill(
          label = if (lockdownOn) "LOCKDOWN ACTIVE" else if (maintOn) "MAINTENANCE ON" else "OPERATIONAL",
          isHealthy = !lockdownOn && !maintOn,
          isWarning = maintOn && !lockdownOn,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Notice Title") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = msg, onValueChange = { msg = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Maintenance Mode")
          Switch(checked = maintOn, onCheckedChange = { maintOn = it })
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
          onClick = { vm.setMaintenance(maintOn, lockdownOn, title, msg) },
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text("Save Maintenance Policy")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = { confirmLockdown = true },
          colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert),
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("emergency_lockdown_button"),
        ) {
          Text(if (lockdownOn) "Lift Global Emergency Lockdown" else "Trigger Global Emergency Lockdown")
        }
      }
    }
  }
}

@Composable
fun DexHookReleasesScreen(isDex: Boolean, state: AdminUiState, vm: AdminViewModel) {
  var verName by remember { mutableStateOf("1.0.0") }
  var verCode by remember { mutableStateOf("1") }
  var sha by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf("Standalone PRANAV.dex validated for MT Manager inspection") }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag(if (isDex) "dex_releases_screen" else "hook_releases_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard {
        Text(
          if (isDex) "PRANAV.dex Release Manager (MT Manager Ready)" else "PRANAV Hook Release Manager (Separate Package)",
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          if (isDex) "Standalone PRANAV.dex deliverable (never a DEX APK). Register verified D8 SHA-256 metadata."
          else "Separate lifecycle integration package (pranav-hook). Never combined with PRANAV.dex.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = verName, onValueChange = { verName = it }, label = { Text("Version") }, modifier = Modifier.weight(1f))
          OutlinedTextField(value = verCode, onValueChange = { verCode = it }, label = { Text("Code") }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = sha, onValueChange = { sha = it }, label = { Text("SHA-256 (64 hex chars)") }, modifier = Modifier.fillMaxWidth())
        if (isDex) {
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("MT Manager Compatibility Notes") }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = {
            if (isDex) vm.registerDexRelease(verName, verCode.toIntOrNull() ?: 1, sha.trim(), 16384L, notes)
            else vm.registerHookRelease(verName, verCode.toIntOrNull() ?: 1, sha.trim())
            sha = ""
          },
          enabled = sha.trim().length == 64,
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text(if (isDex) "Register PRANAV.dex Release" else "Register Hook Release")
        }
      }
    }

    if (isDex) {
      items(state.dexReleases, key = { it.id }) { rel ->
        Pranav3DCard {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
              Text("${rel.fileName} v${rel.versionName} (${rel.versionCode})", style = MaterialTheme.typography.titleMedium)
              Text("SHA-256: ${rel.sha256.take(24)}... • SDK ${rel.minSdk}-${rel.targetSdk}", style = MaterialTheme.typography.labelSmall)
              Text(rel.mtManagerNotes, style = MaterialTheme.typography.bodyMedium)
            }
            PranavStatusPill(label = if (rel.isActive == 1) "ACTIVE" else "STAGED", isHealthy = rel.isActive == 1)
          }
          if (rel.isActive != 1) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.activateDexRelease(rel.id) }, modifier = Modifier.height(44.dp)) {
              Text("Activate Release")
            }
          }
        }
      }
    } else {
      items(state.hookReleases, key = { it.id }) { rel ->
        Pranav3DCard {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
              Text("pranav-hook v${rel.versionName} (${rel.versionCode})", style = MaterialTheme.typography.titleMedium)
              Text("SHA-256: ${rel.sha256.take(24)}...", style = MaterialTheme.typography.labelSmall)
              Text("Hooks: ${rel.lifecycleHooksJson}", style = MaterialTheme.typography.bodyMedium)
            }
            PranavStatusPill(label = if (rel.isActive == 1) "ACTIVE" else "STAGED", isHealthy = rel.isActive == 1)
          }
          if (rel.isActive != 1) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.activateHookRelease(rel.id) }, modifier = Modifier.height(44.dp)) {
              Text("Activate Release")
            }
          }
        }
      }
    }
  }
}

@Composable
fun DatabaseAuditSecurityScreen(mode: String, state: AdminUiState, vm: AdminViewModel) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("ops_${mode}_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when (mode) {
      "db" -> {
        item {
          Pranav3DCard {
            Text("SQLite WAL Database Explorer", style = MaterialTheme.typography.titleLarge)
            Text(
              "Integrity: ${state.dbTables.integrity.integrity} • Journal: ${state.dbTables.integrity.walMode} • Tables: ${state.dbTables.integrity.tableCount}",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        state.inspectedTable?.let { tbl ->
          item {
            Pranav3DCard(accentColor = LocalPranav3D.current.accentPurple) {
              Text("Inspecting: ${tbl.tableName} (${tbl.total} rows)", style = MaterialTheme.typography.titleMedium)
              tbl.rows.take(8).forEach { row ->
                Text(row.toString(), style = MaterialTheme.typography.labelSmall, maxLines = 2)
              }
            }
          }
        }
        items(state.dbTables.tables, key = { it.table }) { t ->
          Pranav3DCard(onClick = { vm.inspectTable(t.table) }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(t.table, style = MaterialTheme.typography.titleMedium)
              Text("${t.rowCount} rows", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
      "audit" -> {
        items(state.auditLogs, key = { it.id }) { log ->
          Pranav3DCard {
            Text("${log.module.uppercase()} • ${log.action}", style = MaterialTheme.typography.titleMedium)
            Text("Actor: ${log.actorType} (${log.actorId}) • ${log.createdAt}", style = MaterialTheme.typography.labelSmall)
            Text(log.detailsJson, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
          }
        }
      }
      "security" -> {
        item {
          Pranav3DCard {
            Text("Security Center & Device Recovery", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { vm.issueRecoveryToken() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
              Text("Issue 10-Min Owner Recovery Token")
            }
            state.recoveryToken?.let { tok ->
              Spacer(modifier = Modifier.height(8.dp))
              Text("Recovery Token: ${tok.token}", style = MaterialTheme.typography.labelSmall, color = LocalPranav3D.current.accentPurple)
              Text("Expires: ${tok.expiresAt}", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
        items(state.securityOverview.ownerDevices, key = { it.id }) { dev ->
          Pranav3DCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Column(modifier = Modifier.weight(1f)) {
                Text(dev.deviceLabel, style = MaterialTheme.typography.titleMedium)
                Text("FP: ${dev.keyFingerprint.take(24)}...", style = MaterialTheme.typography.labelSmall)
              }
              PranavStatusPill(label = dev.status.uppercase(), isHealthy = dev.status == "active")
            }
            if (dev.status == "active") {
              Spacer(modifier = Modifier.height(6.dp))
              OutlinedButton(onClick = { vm.revokeOwnerDevice(dev.id) }, modifier = Modifier.height(44.dp)) {
                Text("Revoke Device")
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun BackupHealthServerSchedulerSettingsScreen(mode: String, state: AdminUiState, vm: AdminViewModel) {
  var confirmRestoreName by remember { mutableStateOf<String?>(null) }
  var backupLabel by remember { mutableStateOf("manual_admin") }

  confirmRestoreName?.let { fname ->
    PranavConfirmDialog(
      title = "Restore Database Snapshot?",
      message = "Restore live database from $fname? A pre-restore safety backup will be created automatically.",
      confirmLabel = "Confirm Restore",
      onConfirm = {
        vm.restoreBackup(fname)
        confirmRestoreName = null
      },
      onDismiss = { confirmRestoreName = null },
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("ops_${mode}_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when (mode) {
      "backup" -> {
        item {
          Pranav3DCard {
            Text("Verified Backup & Restore", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = backupLabel, onValueChange = { backupLabel = it }, label = { Text("Backup Label") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { vm.triggerBackup(backupLabel) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
              Text("Create Hot VACUUM Backup")
            }
          }
        }
        items(state.backups, key = { it.fileName }) { bk ->
          Pranav3DCard {
            Text(bk.fileName, style = MaterialTheme.typography.titleMedium)
            Text("${bk.sizeBytes} bytes • SHA: ${bk.sha256.take(18)}...", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { vm.verifyBackup(bk.fileName) }, modifier = Modifier.height(44.dp)) { Text("Verify") }
              OutlinedButton(onClick = { confirmRestoreName = bk.fileName }, modifier = Modifier.height(44.dp)) { Text("Restore") }
            }
          }
        }
      }
      "health" -> {
        item {
          Pranav3DCard {
            Text("Backend Health", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            PranavStatusPill(label = state.backendHealth.status.uppercase(), isHealthy = state.backendHealth.status == "healthy")
            Spacer(modifier = Modifier.height(8.dp))
            Text("Uptime: ${state.backendHealth.uptimeSeconds}s • Node ${state.backendHealth.nodeVersion}", style = MaterialTheme.typography.bodyMedium)
            Text("Memory: ${state.backendHealth.memory}", style = MaterialTheme.typography.labelSmall)
            Text("Database: ${state.backendHealth.database}", style = MaterialTheme.typography.labelSmall)
          }
        }
      }
      "server" -> {
        item {
          Pranav3DCard {
            Text("VPS Server Monitoring", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Host: ${state.serverMonitoring.hostname} (${state.serverMonitoring.platform})", style = MaterialTheme.typography.bodyMedium)
            Text("CPU: ${state.serverMonitoring.cpuCores} cores • Load: ${state.serverMonitoring.loadAverage}", style = MaterialTheme.typography.labelSmall)
            Text("Memory: ${state.serverMonitoring.memory}", style = MaterialTheme.typography.labelSmall)
            Text("Disk: ${state.serverMonitoring.disk}", style = MaterialTheme.typography.labelSmall)
            Text("Services: ${state.serverMonitoring.services}", style = MaterialTheme.typography.labelSmall)
          }
        }
      }
      "scheduler" -> {
        item {
          var targetId by remember { mutableStateOf("nightly") }
          var runAt by remember { mutableStateOf("2026-12-01T00:00:00Z") }
          Pranav3DCard {
            Text("Configuration Scheduler", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = targetId, onValueChange = { targetId = it }, label = { Text("Target ID / Label") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(value = runAt, onValueChange = { runAt = it }, label = { Text("Run At (ISO-8601 UTC)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { vm.createSchedule("backup", targetId, runAt) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
              Text("Schedule Automated Backup Job")
            }
          }
        }
        items(state.schedules, key = { it.id }) { sch ->
          Pranav3DCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Column(modifier = Modifier.weight(1f)) {
                Text("${sch.jobType} -> ${sch.targetId}", style = MaterialTheme.typography.titleMedium)
                Text("Run At: ${sch.runAt}", style = MaterialTheme.typography.labelSmall)
              }
              PranavStatusPill(label = sch.status.uppercase(), isHealthy = sch.status == "executed", isWarning = sch.status == "pending")
            }
            if (sch.status == "pending") {
              Spacer(modifier = Modifier.height(6.dp))
              OutlinedButton(onClick = { vm.cancelSchedule(sch.id) }, modifier = Modifier.height(44.dp)) { Text("Cancel") }
            }
          }
        }
      }
      "settings" -> {
        item {
          Pranav3DCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Column {
                Text("Admin App Dark Theme", style = MaterialTheme.typography.titleMedium)
                Text("Toggle Obsidian Royal / Glacial Ice Light", style = MaterialTheme.typography.bodyMedium)
              }
              Switch(checked = state.darkTheme, onCheckedChange = { vm.toggleDarkTheme() })
            }
          }
        }
        items(state.settings, key = { it.key }) { setting ->
          var editVal by remember(setting.valueJson) { mutableStateOf(setting.valueJson.trim('"')) }
          Pranav3DCard {
            Text(setting.key, style = MaterialTheme.typography.titleMedium)
            Text(setting.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              OutlinedTextField(value = editVal, onValueChange = { editVal = it }, modifier = Modifier.weight(1f), singleLine = true)
              Button(onClick = { vm.updateSetting(setting.key, editVal) }, modifier = Modifier.height(48.dp)) { Text("Save") }
            }
          }
        }
      }
    }
  }
}
