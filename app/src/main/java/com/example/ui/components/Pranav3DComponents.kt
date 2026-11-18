package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.LocalPranav3D

@Composable
fun Pranav3DCard(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  accentColor: Color? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val tokens = LocalPranav3D.current
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val scale by
    animateFloatAsState(
      targetValue = if (pressed && onClick != null) 0.98f else 1f,
      animationSpec = spring(dampingRatio = 0.7f, stiffness = 450f),
      label = "card_press_scale",
    )

  val borderBrush =
    Brush.linearGradient(
      colors =
        listOf(
          (accentColor ?: tokens.accentIce).copy(alpha = 0.45f),
          tokens.cardHighlightRim,
          tokens.accentPurple.copy(alpha = 0.35f),
        )
    )

  Surface(
    modifier =
      modifier
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .shadow(
          elevation = if (tokens.isDark) 14.dp else 10.dp,
          shape = RoundedCornerShape(22.dp),
          ambientColor = tokens.cardShadowTint,
          spotColor = accentColor ?: tokens.accentPurple.copy(alpha = 0.25f),
        )
        .clip(RoundedCornerShape(22.dp))
        .then(
          if (onClick != null) {
            Modifier.clickable(
              interactionSource = interactionSource,
              indication = androidx.compose.material3.ripple(),
              onClick = onClick,
            )
          } else {
            Modifier
          }
        ),
    shape = RoundedCornerShape(22.dp),
    color = tokens.cardBackground,
    tonalElevation = 4.dp,
    border = BorderStroke(1.2.dp, borderBrush),
  ) {
    Column(modifier = Modifier.padding(16.dp), content = content)
  }
}

@Composable
fun Pranav3DKpiTile(
  title: String,
  value: String,
  subtitle: String,
  icon: ImageVector,
  accentColor: Color,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
) {
  Pranav3DCard(modifier = modifier, onClick = onClick, accentColor = accentColor) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
              Brush.linearGradient(
                listOf(accentColor.copy(alpha = 0.22f), accentColor.copy(alpha = 0.08f))
              )
            ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = title,
          tint = accentColor,
          modifier = Modifier.size(22.dp),
        )
      }
      Box(
        modifier =
          Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(accentColor)
      )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = subtitle,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun PranavStatusPill(label: String, isHealthy: Boolean, isWarning: Boolean = false) {
  val pillColor =
    when {
      isWarning -> Color(0xFFF59E0B)
      isHealthy -> EmeraldOnline
      else -> CrimsonAlert
    }
  Surface(
    shape = RoundedCornerShape(50),
    color = pillColor.copy(alpha = 0.14f),
    border = BorderStroke(1.dp, pillColor.copy(alpha = 0.45f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(pillColor))
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = pillColor,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
fun PranavTelemetryChart(
  points: List<Pair<String, Int>>,
  modifier: Modifier = Modifier,
) {
  val tokens = LocalPranav3D.current
  val lineColor = tokens.accentIce
  val fillTop = tokens.accentPurple.copy(alpha = 0.35f)
  val fillBottom = tokens.accentIce.copy(alpha = 0.02f)

  if (points.isEmpty()) {
    Box(
      modifier = modifier.fillMaxWidth().height(150.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "Awaiting telemetry samples from client SDKs",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Canvas(modifier = Modifier.fillMaxWidth().height(148.dp)) {
      val w = size.width
      val h = size.height
      val maxVal = (points.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1).toFloat()
      val padX = 18f
      val padY = 20f
      val chartW = (w - padX * 2f).coerceAtLeast(1f)
      val chartH = (h - padY * 2f).coerceAtLeast(1f)

      // Horizontal subtle depth grid lines
      for (i in 0..3) {
        val y = padY + (chartH / 3f) * i
        drawLine(
          color = tokens.cardHighlightRim.copy(alpha = 0.55f),
          start = Offset(padX, y),
          end = Offset(w - padX, y),
          strokeWidth = 1.5f,
        )
      }

      val coords =
        points.mapIndexed { idx, (_, value) ->
          val x =
            if (points.size == 1) padX + chartW / 2f
            else padX + (idx.toFloat() / (points.size - 1)) * chartW
          val y = padY + chartH * (1f - value.toFloat() / maxVal)
          Offset(x, y)
        }

      val linePath = Path()
      val fillPath = Path()
      coords.forEachIndexed { idx, pt ->
        if (idx == 0) {
          linePath.moveTo(pt.x, pt.y)
          fillPath.moveTo(pt.x, h - padY)
          fillPath.lineTo(pt.x, pt.y)
        } else {
          linePath.lineTo(pt.x, pt.y)
          fillPath.lineTo(pt.x, pt.y)
        }
      }
      fillPath.lineTo(coords.last().x, h - padY)
      fillPath.close()

      drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(listOf(fillTop, fillBottom), startY = 0f, endY = h),
      )
      drawPath(
        path = linePath,
        color = lineColor,
        style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
      coords.forEach { pt ->
        drawCircle(color = Color.White, radius = 9f, center = pt)
        drawCircle(color = tokens.accentPurple, radius = 5.5f, center = pt)
      }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      points.take(5).forEach { (label, count) ->
        Text(
          text = "${label.takeLast(5)} ($count)",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
fun PranavConfirmDialog(
  title: String,
  message: String,
  confirmLabel: String,
  isDestructive: Boolean = true,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
    text = { Text(text = message, style = MaterialTheme.typography.bodyLarge) },
    confirmButton = {
      Button(
        onClick = onConfirm,
        modifier = Modifier.testTag("confirm_destructive_button"),
        colors =
          ButtonDefaults.buttonColors(
            containerColor =
              if (isDestructive) CrimsonAlert else MaterialTheme.colorScheme.primary
          ),
      ) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_dialog_button")) {
        Text("Cancel")
      }
    },
  )
}
