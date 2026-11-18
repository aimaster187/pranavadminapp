package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.components.Pranav3DCard
import com.example.ui.screens.*
import com.example.ui.theme.LocalPranav3D

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PranavAdminApp(state: AdminUiState, vm: AdminViewModel) {
  val tokens = LocalPranav3D.current
  val snackbarHostState = remember { SnackbarHostState() }
  var showModuleMatrix by remember { mutableStateOf(false) }

  LaunchedEffect(state.bannerMessage, state.errorMessage) {
    val msg = state.errorMessage ?: state.bannerMessage
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      vm.clearMessages()
    }
  }

  if (state.authState !is AuthState.Authenticated) {
    EnrollmentShieldScreen(
      authState = state.authState,
      isLoading = state.isLoading,
      errorMessage = state.errorMessage,
      onEnroll = { url, tok, label -> vm.enrollDevice(url, tok, label) },
      onRetrySilentAuth = { vm.runSilentAuth() },
      modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
    )
    return
  }

  // Handle system back inside secondary modules
  BackHandler(enabled = state.activeModule != AdminModule.DASHBOARD) {
    vm.selectModule(AdminModule.DASHBOARD)
  }

  if (showModuleMatrix) {
    ModalBottomSheet(onDismissRequest = { showModuleMatrix = false }) {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item {
          Text("All 24 PRANAV Modules", style = MaterialTheme.typography.titleLarge)
          Spacer(modifier = Modifier.height(6.dp))
        }
        items(AdminModule.entries) { mod ->
          Pranav3DCard(
            onClick = {
              vm.selectModule(mod)
              showModuleMatrix = false
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("${mod.number}. ${mod.title}", style = MaterialTheme.typography.titleMedium)
              Text(mod.hub.title, style = MaterialTheme.typography.labelSmall, color = tokens.accentPurple)
            }
          }
        }
      }
    }
  }

  val hubDefaultModule: (AdminHub) -> AdminModule = { hub ->
    when (hub) {
      AdminHub.OVERVIEW -> AdminModule.DASHBOARD
      AdminHub.APPS -> AdminModule.APP_MANAGER
      AdminHub.LICENSES -> AdminModule.LICENSE_MANAGER
      AdminHub.DELIVERY -> AdminModule.UPDATE_MANAGER
      AdminHub.OPERATIONS -> AdminModule.TELEGRAM_BOTS
    }
  }

  val hubIcon: (AdminHub) -> androidx.compose.ui.graphics.vector.ImageVector = { hub ->
    when (hub) {
      AdminHub.OVERVIEW -> Icons.Filled.Dashboard
      AdminHub.APPS -> Icons.Filled.Apps
      AdminHub.LICENSES -> Icons.Filled.Key
      AdminHub.DELIVERY -> Icons.Filled.Tune
      AdminHub.OPERATIONS -> Icons.Filled.Settings
    }
  }

  BoxWithConstraints(
    modifier =
      Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(listOf(tokens.canvasGradientTop, tokens.canvasGradientBottom))
        )
  ) {
    val isExpanded = maxWidth >= 840.dp

    Scaffold(
      containerColor = Color.Transparent,
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(snackbarHostState) },
      topBar = {
        TopAppBar(
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
          navigationIcon = {
            if (state.activeModule != AdminModule.DASHBOARD) {
              IconButton(
                onClick = { vm.selectModule(AdminModule.DASHBOARD) },
                modifier = Modifier.testTag("back_to_dashboard_button"),
              ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Dashboard")
              }
            }
          },
          title = {
            Column {
              Text(state.activeModule.title, style = MaterialTheme.typography.titleLarge)
              Text(
                "Module ${state.activeModule.number}/24 • ${state.activeModule.hub.title}",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.accentPurple,
              )
            }
          },
          actions = {
            IconButton(
              onClick = { showModuleMatrix = true },
              modifier = Modifier.testTag("open_24_modules_button"),
            ) {
              Icon(Icons.Filled.GridView, contentDescription = "Open 24 Modules Matrix")
            }
            IconButton(
              onClick = { vm.toggleDarkTheme() },
              modifier = Modifier.testTag("toggle_theme_button"),
            ) {
              Icon(
                if (state.darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                contentDescription = "Toggle Dark or Light Theme",
              )
            }
          },
        )
      },
      bottomBar = {
        if (!isExpanded) {
          NavigationBar(
            containerColor = tokens.cardBackground.copy(alpha = 0.94f),
            windowInsets = WindowInsets.navigationBars,
          ) {
            AdminHub.entries.forEach { hub ->
              NavigationBarItem(
                selected = state.activeModule.hub == hub,
                onClick = { vm.selectModule(hubDefaultModule(hub)) },
                icon = { Icon(hubIcon(hub), contentDescription = hub.title) },
                label = { Text(hub.title, maxLines = 1) },
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (isExpanded) {
          NavigationRail(containerColor = Color.Transparent) {
            AdminHub.entries.forEach { hub ->
              NavigationRailItem(
                selected = state.activeModule.hub == hub,
                onClick = { vm.selectModule(hubDefaultModule(hub)) },
                icon = { Icon(hubIcon(hub), contentDescription = hub.title) },
                label = { Text(hub.title) },
              )
            }
          }
        }

        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(horizontal = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
          }
          Box(modifier = Modifier.widthIn(max = 900.dp).fillMaxSize()) {
            AnimatedContent(
              targetState = state.activeModule,
              transitionSpec = { fadeIn() togetherWith fadeOut() },
              label = "module_switch",
            ) { mod ->
              when (mod) {
                AdminModule.DASHBOARD ->
                  DashboardOverviewScreen(
                    state = state,
                    onSelectModule = { vm.selectModule(it) },
                    onRefresh = { vm.loadModuleData(AdminModule.DASHBOARD) },
                  )
                AdminModule.APP_MANAGER -> AppManagerScreen(state, vm)
                AdminModule.USER_MANAGER -> UserInstallationManagerScreen(state, vm)
                AdminModule.DEVICE_MANAGER -> DeviceManagerScreen(state, vm)
                AdminModule.LICENSE_MANAGER -> LicenseManagerScreen(state, vm)
                AdminModule.VIP_MANAGER -> VipManagerScreen(state, vm)
                AdminModule.UPDATE_MANAGER -> UpdateManagerScreen(state, vm)
                AdminModule.DIALOG_MANAGER -> UniversalDialogManagerScreen(state, vm)
                AdminModule.ANNOUNCEMENT_MANAGER -> AnnouncementManagerScreen(state, vm)
                AdminModule.REMOTE_CONFIG -> RemoteConfigScreen(state, vm)
                AdminModule.DYNAMIC_MODULES -> DynamicModuleRegistryScreen(state, vm)
                AdminModule.MAINTENANCE -> MaintenanceLockdownScreen(false, state, vm)
                AdminModule.EMERGENCY_LOCKDOWN -> MaintenanceLockdownScreen(true, state, vm)
                AdminModule.TELEGRAM_BOTS -> TelegramBotsScreen(state, vm)
                AdminModule.DEX_RELEASES -> DexHookReleasesScreen(true, state, vm)
                AdminModule.HOOK_RELEASES -> DexHookReleasesScreen(false, state, vm)
                AdminModule.DATABASE_EXPLORER -> DatabaseAuditSecurityScreen("db", state, vm)
                AdminModule.AUDIT_LOGS -> DatabaseAuditSecurityScreen("audit", state, vm)
                AdminModule.SECURITY_CENTER -> DatabaseAuditSecurityScreen("security", state, vm)
                AdminModule.BACKUP_RESTORE -> BackupHealthServerSchedulerSettingsScreen("backup", state, vm)
                AdminModule.BACKEND_HEALTH -> BackupHealthServerSchedulerSettingsScreen("health", state, vm)
                AdminModule.SERVER_MONITORING -> BackupHealthServerSchedulerSettingsScreen("server", state, vm)
                AdminModule.SCHEDULER -> BackupHealthServerSchedulerSettingsScreen("scheduler", state, vm)
                AdminModule.SYSTEM_SETTINGS -> BackupHealthServerSchedulerSettingsScreen("settings", state, vm)
              }
            }
          }
        }
      }
    }
  }
}
