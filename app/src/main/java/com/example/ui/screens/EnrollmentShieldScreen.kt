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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.ui.AuthState
import com.example.ui.components.Pranav3DCard
import com.example.ui.components.PranavStatusPill
import com.example.ui.theme.LocalPranav3D

@Composable
fun EnrollmentShieldScreen(
  authState: AuthState,
  isLoading: Boolean,
  errorMessage: String?,
  onEnroll: (apiUrl: String, token: String, deviceLabel: String) -> Unit,
  onRetrySilentAuth: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tokens = LocalPranav3D.current
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(listOf(tokens.canvasGradientTop, tokens.canvasGradientBottom))
        )
        .padding(16.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (authState is AuthState.Checking) {
      Pranav3DCard(modifier = Modifier.widthIn(max = 460.dp)) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          CircularProgressIndicator(color = tokens.accentIce)
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Verifying Owner Keystore Identity",
            style = MaterialTheme.typography.titleLarge,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Executing silent ECDSA P-256 hardware challenge...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      return
    }

    val unverified = authState as AuthState.Unverified
    var apiUrl by remember(unverified.apiBaseUrl) { mutableStateOf(unverified.apiBaseUrl) }
    var enrollmentToken by remember { mutableStateOf("") }
    var deviceLabel by remember(unverified.hardwareLabel) {
      mutableStateOf(unverified.hardwareLabel.ifBlank { "Owner Phone" })
    }

    Column(
      modifier =
        Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Pranav3DCard(accentColor = tokens.accentPurple) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(120.dp)
              .clip(RoundedCornerShape(16.dp))
        ) {
          Image(
            painter = painterResource(id = R.drawable.img_pranav_hero_3d_1795014711533),
            contentDescription = "PRANAV 3D Security Shield Banner",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
          Row(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column {
              Text(
                text = "PRANAV CRYPTOGRAPHIC SHIELD",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.accentPurple,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = "Owner Device Verification Required",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            PranavStatusPill(label = "LOCKED", isHealthy = false)
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text =
            "Admin data and controls remain sealed until this phone's Android Keystore keypair is verified by your PRANAV backend. Zero credentials are embedded in this APK.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Filled.Key, contentDescription = "Key fingerprint", tint = tokens.accentIce, modifier = Modifier.size(18.dp))
          Text(
            text = "Keystore SHA-256: ${unverified.keyFingerprint.take(24)}...",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.accentIce,
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Status: ${unverified.reason}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }

      Pranav3DCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Filled.Security, contentDescription = "Enroll", tint = tokens.accentPurple)
          Text(
            text = "One-Time Enrollment / Recovery",
            style = MaterialTheme.typography.titleLarge,
          )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
          value = apiUrl,
          onValueChange = { apiUrl = it },
          label = { Text("PRANAV Backend HTTPS URL") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("enroll_api_url_input"),
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
          value = enrollmentToken,
          onValueChange = { enrollmentToken = it },
          label = { Text("Single-Use Bootstrap or RANGIN /recovery Token") },
          placeholder = { Text("PRN-BOOTSTRAP-... or PRN-RECOVERY-...") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("enroll_token_input"),
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
          value = deviceLabel,
          onValueChange = { deviceLabel = it },
          label = { Text("Owner Device Label") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("enroll_device_label_input"),
        )

        if (!errorMessage.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Button(
            onClick = { onEnroll(apiUrl, enrollmentToken, deviceLabel) },
            enabled = !isLoading && enrollmentToken.isNotBlank() && apiUrl.isNotBlank(),
            modifier = Modifier.weight(1f).height(48.dp).testTag("enroll_submit_button"),
          ) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(if (isLoading) "Enrolling..." else "Enroll Owner Device")
          }
          OutlinedButton(
            onClick = onRetrySilentAuth,
            enabled = !isLoading,
            modifier = Modifier.height(48.dp).testTag("retry_silent_auth_button"),
          ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Retry silent verification")
          }
        }
      }
    }
  }
}
