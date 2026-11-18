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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.TelegramBotItem
import com.example.ui.AdminUiState
import com.example.ui.AdminViewModel
import com.example.ui.components.Pranav3DCard
import com.example.ui.components.PranavConfirmDialog
import com.example.ui.components.PranavStatusPill
import com.example.ui.theme.LocalPranav3D

@Composable
fun TelegramBotsScreen(state: AdminUiState, vm: AdminViewModel) {
  val tokens = LocalPranav3D.current
  var botName by remember { mutableStateOf("RANGIN") }
  var botToken by remember { mutableStateOf("") }
  var ownerNumericId by remember { mutableStateOf("") }
  var webhookBase by remember { mutableStateOf("") }
  var welcomeMsg by remember { mutableStateOf("Welcome to PRANAV RANGIN.") }

  var editingBot by remember { mutableStateOf<TelegramBotItem?>(null) }
  var editOwnerId by remember { mutableStateOf("") }
  var editWelcome by remember { mutableStateOf("") }
  var editWebhook by remember { mutableStateOf("") }
  var rotateSecret by remember { mutableStateOf(false) }

  var confirmRemoveBotId by remember { mutableStateOf<String?>(null) }

  confirmRemoveBotId?.let { id ->
    PranavConfirmDialog(
      title = "Remove Telegram Bot?",
      message = "This deletes the encrypted token, disables its webhook, and stops all RANGIN/bot commands for $id.",
      confirmLabel = "Remove Bot",
      onConfirm = {
        vm.botLifecycleAction(id, "remove")
        confirmRemoveBotId = null
      },
      onDismiss = { confirmRemoveBotId = null },
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("telegram_bots_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Pranav3DCard(accentColor = tokens.accentPurple) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Filled.SmartToy, contentDescription = "Telegram Bot Manager", tint = tokens.accentIce)
          Text("Add Telegram Bot (AES-256-GCM Encrypted)", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          "Enter your BotFather token and Owner Telegram Numeric ID. The backend verifies via Telegram getMe, encrypts the token at rest, and enforces strict welcome-only access for all non-owners.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
          value = botName,
          onValueChange = { botName = it },
          label = { Text("Bot Name / Alias (e.g. RANGIN)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("bot_name_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = botToken,
          onValueChange = { botToken = it },
          label = { Text("BotFather Token (Encrypted on Backend)") },
          placeholder = { Text("123456789:AAH...") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("bot_token_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = ownerNumericId,
          onValueChange = { ownerNumericId = it },
          label = { Text("Authorized Owner Telegram Numeric ID") },
          placeholder = { Text("e.g. 5123456789") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("bot_owner_id_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = webhookBase,
          onValueChange = { webhookBase = it },
          label = { Text("Optional Webhook HTTPS Base URL") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("bot_webhook_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = welcomeMsg,
          onValueChange = { welcomeMsg = it },
          label = { Text("Non-Owner Welcome-Only Message") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("bot_welcome_input"),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
          onClick = {
            vm.addTelegramBot(
              botName.trim(),
              botToken.trim(),
              ownerNumericId.trim(),
              webhookBase.trim(),
              welcomeMsg.trim(),
            )
            botToken = ""
          },
          enabled = botName.isNotBlank() && botToken.contains(":") && ownerNumericId.all { it.isDigit() } && ownerNumericId.length >= 4,
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("add_bot_submit_button"),
        ) {
          Text("Verify, Encrypt & Start Bot")
        }
      }
    }

    editingBot?.let { bot ->
      item {
        Pranav3DCard(accentColor = tokens.accentIce) {
          Text("Configure ${bot.name} (${bot.id})", style = MaterialTheme.typography.titleLarge)
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(value = editOwnerId, onValueChange = { editOwnerId = it }, label = { Text("Owner Numeric ID") }, modifier = Modifier.fillMaxWidth())
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(value = editWebhook, onValueChange = { editWebhook = it }, label = { Text("Webhook URL") }, modifier = Modifier.fillMaxWidth())
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(value = editWelcome, onValueChange = { editWelcome = it }, label = { Text("Non-Owner Welcome Text") }, modifier = Modifier.fillMaxWidth())
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Rotate Webhook Secret")
            Switch(checked = rotateSecret, onCheckedChange = { rotateSecret = it })
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = {
                vm.configureTelegramBot(bot.id, editOwnerId, editWelcome, editWebhook, rotateSecret)
                editingBot = null
              },
              modifier = Modifier.weight(1f).height(48.dp),
            ) {
              Text("Save Config")
            }
            OutlinedButton(onClick = { editingBot = null }, modifier = Modifier.height(48.dp)) {
              Text("Close")
            }
          }
        }
      }
    }

    items(state.telegramBots, key = { it.id }) { bot ->
      Pranav3DCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${bot.name} (@${bot.botUsername.ifBlank { "pending" }})", style = MaterialTheme.typography.titleLarge)
            Text("Owner Numeric ID: ${bot.ownerTelegramId}", style = MaterialTheme.typography.labelSmall, color = tokens.accentPurple)
            Text("Token (Encrypted): ${bot.tokenMasked}", style = MaterialTheme.typography.labelSmall)
          }
          PranavStatusPill(
            label = bot.status.uppercase(),
            isHealthy = bot.status == "running",
            isWarning = bot.status == "stopped",
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          "Processed: ${bot.updatesProcessed} • Non-Owner Blocked: ${bot.updatesRejected} • Deduped: ${bot.updatesDeduped}",
          style = MaterialTheme.typography.bodyMedium,
        )
        if (bot.lastError.isNotBlank()) {
          Text("Last Error: ${bot.lastError}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = { vm.botLifecycleAction(bot.id, if (bot.status == "running") "stop" else "start") },
            modifier = Modifier.height(44.dp).testTag("toggle_bot_${bot.id}"),
          ) {
            Icon(if (bot.status == "running") Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
            Text(if (bot.status == "running") " Stop" else " Start")
          }
          OutlinedButton(onClick = { vm.botLifecycleAction(bot.id, "restart") }, modifier = Modifier.height(44.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Restart")
          }
          OutlinedButton(
            onClick = {
              editingBot = bot
              editOwnerId = bot.ownerTelegramId
              editWelcome = bot.welcomeMessage
              editWebhook = bot.webhookUrl
              rotateSecret = false
            },
            modifier = Modifier.height(44.dp),
          ) {
            Text("Config")
          }
          OutlinedButton(onClick = { confirmRemoveBotId = bot.id }, modifier = Modifier.height(44.dp)) {
            Text("Remove")
          }
        }
      }
    }
  }
}
