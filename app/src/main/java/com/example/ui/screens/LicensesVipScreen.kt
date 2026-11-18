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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.AdminUiState
import com.example.ui.AdminViewModel
import com.example.ui.components.Pranav3DCard
import com.example.ui.components.PranavStatusPill
import com.example.ui.theme.LocalPranav3D

@Composable
fun LicenseManagerScreen(state: AdminUiState, vm: AdminViewModel) {
  val tokens = LocalPranav3D.current
  var countStr by remember { mutableStateOf("1") }
  var daysStr by remember { mutableStateOf("30") }
  var appId by remember { mutableStateOf("") }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("license_manager_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard(accentColor = tokens.accentPurple) {
        Text("Generate One-Device-Bound Licenses", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = countStr,
            onValueChange = { countStr = it },
            label = { Text("Count") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("license_count_input"),
          )
          OutlinedTextField(
            value = daysStr,
            onValueChange = { daysStr = it },
            label = { Text("Valid Days") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("license_days_input"),
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = appId,
          onValueChange = { appId = it },
          label = { Text("Optional App ID (blank = Global License)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = {
            val scope = if (appId.isBlank()) "global" else "app"
            vm.generateLicenses(
              count = countStr.toIntOrNull()?.coerceIn(1, 50) ?: 1,
              scope = scope,
              appId = appId.ifBlank { null },
              durationDays = daysStr.toIntOrNull() ?: 30,
            )
          },
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("generate_license_button"),
        ) {
          Text("Generate License Keys")
        }
      }
    }

    items(state.licenses, key = { it.id }) { lic ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text(lic.licenseKey, style = MaterialTheme.typography.titleMedium, color = tokens.accentIce)
            Text(
              "Scope: ${lic.scope} • Transfers: ${lic.transferCount}/${lic.maxTransfers} • Bound: ${lic.boundDeviceId ?: "Unbound"}",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          PranavStatusPill(label = lic.status.uppercase(), isHealthy = lic.status == "active" || lic.status == "unused")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { vm.mutateLicense(lic.id, "renew", 30) }, modifier = Modifier.height(44.dp)) {
            Text("Renew +30d")
          }
          OutlinedButton(onClick = { vm.mutateLicense(lic.id, "revoke") }, modifier = Modifier.height(44.dp)) {
            Text("Revoke")
          }
        }
      }
    }
  }
}

@Composable
fun VipManagerScreen(state: AdminUiState, vm: AdminViewModel) {
  val tokens = LocalPranav3D.current
  var code by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var days by remember { mutableStateOf("30") }
  var maxDev by remember { mutableStateOf("2") }
  var price by remember { mutableStateOf("$9.99") }

  var actPlanId by remember { mutableStateOf("") }
  var actInstallId by remember { mutableStateOf("") }
  var paymentRef by remember { mutableStateOf("") }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("vip_manager_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard(accentColor = tokens.accentPurple) {
        Text("Create VIP Plan", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code") }, modifier = Modifier.weight(1f))
          OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Plan Name") }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = days, onValueChange = { days = it }, label = { Text("Days") }, modifier = Modifier.weight(1f))
          OutlinedTextField(value = maxDev, onValueChange = { maxDev = it }, label = { Text("Max Devices") }, modifier = Modifier.weight(1f))
          OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Price") }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = {
            vm.createVipPlan(code.trim(), name.trim(), days.toIntOrNull() ?: 30, maxDev.toIntOrNull() ?: 1, price)
            code = ""
            name = ""
          },
          enabled = code.isNotBlank() && name.isNotBlank(),
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text("Create VIP Plan")
        }
      }
    }

    item {
      Pranav3DCard {
        Text("Server-Verified VIP Activation", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = actPlanId, onValueChange = { actPlanId = it }, label = { Text("VIP Plan ID") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = actInstallId, onValueChange = { actInstallId = it }, label = { Text("Installation ID") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = paymentRef, onValueChange = { paymentRef = it }, label = { Text("Verified Payment Reference") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = { vm.activateVip(actPlanId.trim(), actInstallId.trim(), paymentRef.trim()) },
          enabled = actPlanId.isNotBlank() && actInstallId.isNotBlank() && paymentRef.isNotBlank(),
          modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text("Verify Payment & Activate VIP")
        }
      }
    }

    items(state.vipPlans, key = { it.id }) { plan ->
      Pranav3DCard {
        Text("${plan.name} (${plan.code}) • ${plan.priceLabel}", style = MaterialTheme.typography.titleMedium)
        Text("ID: ${plan.id} • ${plan.durationDays}d • Max ${plan.maxDevices} devices", style = MaterialTheme.typography.labelSmall)
      }
    }

    items(state.vipSubscriptions, key = { it.id }) { sub ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${sub.planName} -> ${sub.installationId}", style = MaterialTheme.typography.titleMedium)
            Text("Ref: ${sub.paymentReference} • Exp: ${sub.expiresAt.take(10)}", style = MaterialTheme.typography.labelSmall)
          }
          PranavStatusPill(label = sub.status.uppercase(), isHealthy = sub.status == "active")
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(onClick = { vm.revokeVip(sub.id) }, modifier = Modifier.height(44.dp)) {
          Text("Revoke VIP")
        }
      }
    }
  }
}
