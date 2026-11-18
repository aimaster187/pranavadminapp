package com.pranav.sdk

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun PranavUniversalDialogHost(
  snapshot: SdkBootstrapSnapshot,
  onDismissDialog: (String) -> Unit,
  onUpdateAction: (UpdatePayload, String) -> Unit,
) {
  val context = LocalContext.current

  if (snapshot.maintenanceActive) {
    AlertDialog(
      onDismissRequest = {},
      title = { Text(snapshot.maintenanceTitle.ifBlank { "Scheduled Maintenance" }) },
      text = { Text(snapshot.maintenanceMessage.ifBlank { "Service temporarily unavailable." }) },
      confirmButton = {
        Button(onClick = { /* Maintenance blocks interaction cleanly */ }) { Text("Acknowledged") }
      },
      modifier = Modifier.testTag("sdk_maintenance_dialog"),
    )
    return
  }

  val upd = snapshot.update
  if (upd != null) {
    AlertDialog(
      onDismissRequest = { if (!upd.isForce && upd.showLaterButton) onUpdateAction(upd, "later") },
      title = { Text("Update Available v${upd.latestVersionName}") },
      text = { Text(upd.whatsNew.ifBlank { "A new version is available." }) },
      confirmButton = {
        Button(
          onClick = {
            onUpdateAction(upd, "accepted")
            runCatching {
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(upd.downloadUrl)))
            }
          },
          modifier = Modifier.testTag("sdk_update_accept_button"),
        ) {
          Text("Update Now")
        }
      },
      dismissButton = {
        if (!upd.isForce && upd.showLaterButton) {
          OutlinedButton(
            onClick = { onUpdateAction(upd, "later") },
            modifier = Modifier.testTag("sdk_update_later_button"),
          ) {
            Text("Later")
          }
        }
      },
      modifier = Modifier.testTag("sdk_update_dialog"),
    )
    return
  }

  val topDialog = snapshot.dialogs.firstOrNull() ?: return

  if (topDialog.dialogType == "custom_toast") {
    LaunchedEffect(topDialog.id) {
      Toast.makeText(context, "${topDialog.title}: ${topDialog.description}", Toast.LENGTH_LONG).show()
      onDismissDialog(topDialog.id)
    }
    return
  }

  val isForce = topDialog.dialogType == "force"
  AlertDialog(
    onDismissRequest = { if (!isForce) onDismissDialog(topDialog.id) },
    title = { Text(topDialog.title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (topDialog.imageUrl.isNotBlank()) {
          AsyncImage(
            model = topDialog.imageUrl,
            contentDescription = topDialog.title,
            modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
          )
        }
        Text(topDialog.description, style = MaterialTheme.typography.bodyMedium)
      }
    },
    confirmButton = {
      val primary = topDialog.buttons.firstOrNull()
      Button(
        onClick = {
          if (primary != null && (primary.actionType == "url" || primary.actionType == "deeplink") && primary.actionValue.isNotBlank()) {
            runCatching {
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(primary.actionValue)))
            }
          }
          if (!isForce) onDismissDialog(topDialog.id)
        },
        modifier = Modifier.testTag("sdk_dialog_primary_button"),
      ) {
        Text(primary?.label ?: "Continue")
      }
    },
    dismissButton = {
      if (!isForce) {
        OutlinedButton(
          onClick = { onDismissDialog(topDialog.id) },
          modifier = Modifier.testTag("sdk_dialog_dismiss_button"),
        ) {
          Text("Dismiss")
        }
      }
    },
    modifier = Modifier.testTag("sdk_universal_dialog"),
  )
}
