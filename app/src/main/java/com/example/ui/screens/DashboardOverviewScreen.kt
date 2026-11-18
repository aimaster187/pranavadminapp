package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.AdminModule
import com.example.ui.AdminUiState
import com.example.ui.components.Pranav3DCard
import com.example.ui.components.Pranav3DKpiTile
import com.example.ui.components.PranavStatusPill
import com.example.ui.components.PranavTelemetryChart
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.LocalPranav3D

@Composable
fun DashboardOverviewScreen(
  state: AdminUiState,
  onSelectModule: (AdminModule) -> Unit,
  onRefresh: () -> Unit,
) {
  val tokens = LocalPranav3D.current
  val kpis = state.dashboard.kpis

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("dashboard_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      Pranav3DCard(accentColor = tokens.accentIce) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(124.dp)
              .clip(RoundedCornerShape(16.dp))
        ) {
          Image(
            painter = painterResource(id = R.drawable.img_pranav_hero_3d_1795014711533),
            contentDescription = "PRANAV 3D Telemetry Backdrop",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(
                  Brush.horizontalGradient(
                    listOf(
                      tokens.cardBackground.copy(alpha = 0.88f),
                      tokens.cardBackground.copy(alpha = 0.28f),
                    )
                  )
                )
          )
          Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              PranavStatusPill(
                label =
                  when {
                    kpis.emergencyLockdown -> "LOCKDOWN ACTIVE"
                    kpis.maintenanceEnabled -> "MAINTENANCE"
                    else -> "SYSTEM NOMINAL"
                  },
                isHealthy = !kpis.emergencyLockdown && !kpis.maintenanceEnabled,
                isWarning = kpis.maintenanceEnabled && !kpis.emergencyLockdown,
              )
              OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.height(36.dp).testTag("refresh_dashboard_button"),
              ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh dashboard", modifier = Modifier.size(16.dp))
              }
            }
            Column {
              Text(
                text = "PRANAV SUPER ADMIN",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Single-Owner Keystore Verified • RANGIN Synchronized",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }

    item {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pranav3DKpiTile(
          title = "Authorized Apps",
          value = "${kpis.activeApps}/${kpis.totalApps}",
          subtitle = "${kpis.totalInstallations} installs",
          icon = Icons.Filled.Apps,
          accentColor = tokens.accentIce,
          modifier = Modifier.weight(1f),
          onClick = { onSelectModule(AdminModule.APP_MANAGER) },
        )
        Pranav3DKpiTile(
          title = "Active Licenses",
          value = "${kpis.activeLicenses}/${kpis.totalLicenses}",
          subtitle = "${kpis.activeVip} VIP active",
          icon = Icons.Filled.Key,
          accentColor = tokens.accentPurple,
          modifier = Modifier.weight(1f),
          onClick = { onSelectModule(AdminModule.LICENSE_MANAGER) },
        )
      }
    }

    item {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pranav3DKpiTile(
          title = "Telegram Bots",
          value = "${kpis.activeBots}/${kpis.totalBots}",
          subtitle = "RANGIN multi-bot",
          icon = Icons.Filled.SmartToy,
          accentColor = EmeraldOnline,
          modifier = Modifier.weight(1f),
          onClick = { onSelectModule(AdminModule.TELEGRAM_BOTS) },
        )
        Pranav3DKpiTile(
          title = "Trusted Devices",
          value = "${kpis.activeDevices}",
          subtitle = "${kpis.activeDialogs} active dialogs",
          icon = Icons.Filled.Devices,
          accentColor = tokens.accentIce,
          modifier = Modifier.weight(1f),
          onClick = { onSelectModule(AdminModule.DEVICE_MANAGER) },
        )
      }
    }

    item {
      Pranav3DCard(accentColor = tokens.accentIce) {
        Text(
          text = "SDK Telemetry & Event Throughput",
          style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(10.dp))
        PranavTelemetryChart(
          points = state.dashboard.activitySeries.map { it.day to it.eventsCount }
        )
      }
    }

    if (state.dashboard.licenseBreakdown.isNotEmpty()) {
      item {
        Pranav3DCard(accentColor = tokens.accentPurple) {
          Text(text = "License Pool Distribution", style = MaterialTheme.typography.titleLarge)
          Spacer(modifier = Modifier.height(10.dp))
          val total = state.dashboard.licenseBreakdown.sumOf { it.total }.coerceAtLeast(1)
          state.dashboard.licenseBreakdown.forEach { slice ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(slice.status.uppercase(), style = MaterialTheme.typography.labelMedium)
              Text("${slice.total}", style = MaterialTheme.typography.labelSmall)
            }
            LinearProgressIndicator(
              progress = { slice.total.toFloat() / total.toFloat() },
              modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
              color = if (slice.status == "revoked") CrimsonAlert else tokens.accentPurple,
            )
          }
        }
      }
    }

    item {
      Text(
        text = "24-Module Command Matrix",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 4.dp),
      )
    }

    val chunked = AdminModule.entries.chunked(2)
    items(chunked) { pair ->
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        pair.forEach { mod ->
          Pranav3DCard(
            modifier = Modifier.weight(1f).testTag("module_tile_${mod.number}"),
            onClick = { onSelectModule(mod) },
          ) {
            Text(
              text = "MODULE ${mod.number.toString().padStart(2, '0')}",
              style = MaterialTheme.typography.labelSmall,
              color = tokens.accentPurple,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = mod.title,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 2,
            )
          }
        }
        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
      }
    }

    if (state.dashboard.recentAudit.isNotEmpty()) {
      item {
        Pranav3DCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Recent Audit Trail", style = MaterialTheme.typography.titleLarge)
            Button(
              onClick = { onSelectModule(AdminModule.AUDIT_LOGS) },
              modifier = Modifier.height(36.dp),
            ) {
              Text("View All")
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
          state.dashboard.recentAudit.take(5).forEach { log ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("${log.module} • ${log.action}", style = MaterialTheme.typography.titleMedium)
                Text("${log.actorType} (${log.actorId.take(12)})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Icon(Icons.Filled.Security, contentDescription = null, tint = tokens.accentIce, modifier = Modifier.size(16.dp))
            }
          }
        }
      }
    }

    item { Spacer(modifier = Modifier.height(16.dp)) }
  }
}
