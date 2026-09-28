package com.paysync.gateway.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paysync.gateway.R
import com.paysync.gateway.ui.MainViewModel
import com.paysync.gateway.ui.theme.StatusGreen

private val PermCardShape = RoundedCornerShape(18.dp)

/**
 * First-launch permission onboarding screen.
 * Instant reactive updates via Lifecycle ON_RESUME observation in MainActivity.
 */
@Composable
fun PermissionsScreen(
    vm: MainViewModel,
    onRequestSms: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val smsGranted by vm.smsGranted.collectAsStateWithLifecycle()
    val notifGranted by vm.notifGranted.collectAsStateWithLifecycle()
    val batteryExempt by vm.batteryExempt.collectAsStateWithLifecycle()
    val allGranted = smsGranted && notifGranted && batteryExempt

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        // Title block
        Text(
            stringResource(R.string.perm_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(R.string.perm_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ── SMS Permission ───────────────────────────────────────────
        PermissionCard(
            icon = Icons.AutoMirrored.Filled.Message,
            title = stringResource(R.string.perm_sms_title),
            description = stringResource(R.string.perm_sms_body),
            granted = smsGranted,
            onRequest = onRequestSms
        )

        // ── Notification Permission ──────────────────────────────────
        PermissionCard(
            icon = Icons.Filled.Notifications,
            title = stringResource(R.string.perm_notif_title),
            description = stringResource(R.string.perm_notif_body),
            granted = notifGranted,
            onRequest = onRequestNotifications
        )

        // ── Battery Optimization ─────────────────────────────────────
        PermissionCard(
            icon = Icons.Filled.BatteryAlert,
            title = stringResource(R.string.perm_battery_title),
            description = stringResource(R.string.perm_battery_body),
            granted = batteryExempt,
            onRequest = onRequestBattery
        )

        Spacer(Modifier.height(8.dp))

        // ── Continue Button ──────────────────────────────────────────
        Button(
            onClick = onContinue,
            enabled = allGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Text(
                stringResource(R.string.perm_continue),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (!allGranted) {
            Text(
                stringResource(R.string.perm_all_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Permission Card — explains WHY and shows grant status
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    val accentColor = if (granted) StatusGreen else MaterialTheme.colorScheme.primary
    val containerBg = if (granted) {
        StatusGreen.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = PermCardShape,
        colors = CardDefaults.cardColors(containerColor = containerBg),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (granted) 0.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon badge
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (granted) Icons.Filled.CheckCircle else icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (granted) {
                        Text(
                            stringResource(R.string.perm_granted),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusGreen,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!granted) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onRequest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.perm_grant),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
