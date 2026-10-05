package com.paysync.gateway.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paysync.gateway.R
import com.paysync.gateway.data.DispatchLog
import com.paysync.gateway.ui.MainViewModel
import com.paysync.gateway.ui.theme.CardGlowRunning
import com.paysync.gateway.ui.theme.CardGlowStopped
import com.paysync.gateway.ui.theme.GatewayRunningGradient
import com.paysync.gateway.ui.theme.GatewayStoppedGradient
import com.paysync.gateway.ui.theme.StatusAmber
import com.paysync.gateway.ui.theme.StatusGreen
import com.paysync.gateway.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Shape Tokens ─────────────────────────────────────────────────────
private val MissionCardShape = RoundedCornerShape(22.dp)
private val HealthCardShape  = RoundedCornerShape(18.dp)
private val MetricCardShape  = RoundedCornerShape(16.dp)
private val LogCardShape     = RoundedCornerShape(16.dp)

// ═══════════════════════════════════════════════════════════════════════
//  Mission Control Dashboard
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun DashboardScreen(
    vm: MainViewModel,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val updateAvailable by vm.updateAvailable.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ── Section 0 : Update notice (never auto-installs; link only) ──
        updateAvailable?.let { update ->
            item(key = "update-notice") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MetricCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource(R.string.update_card_title, update.latestVersion),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            stringResource(R.string.update_card_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        TextButton(onClick = {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(update.releasesUrl)
                                    )
                                )
                            }
                        }) {
                            Text(
                                stringResource(R.string.update_card_button),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }

        // ── Section 1 : System Health ────────────────────────────────
        item(key = "section-health") {
            SectionHeader(stringResource(R.string.section_health))
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                NetworkHealthCard(
                    isOnline = ui.isOnline,
                    transportLabel = ui.networkLabel,
                    modifier = Modifier.weight(1f)
                )
                DeviceHealthCard(
                    batteryPct = ui.batteryPct,
                    isCharging = ui.isCharging,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── Section 2 : Gateway Control ──────────────────────────────
        item(key = "section-control") {
            SectionHeader(stringResource(R.string.section_control))
            Spacer(Modifier.height(8.dp))
            GatewayControlCard(
                running = ui.isRunning,
                serviceAlive = ui.serviceAlive,
                onToggle = onToggle
            )
        }

        // ── Section 3 : Sync Metrics (2×2 Grid) ─────────────────────
        item(key = "section-metrics") {
            SectionHeader(stringResource(R.string.section_metrics))
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Row 1: Pending & Queue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        icon = Icons.Filled.HourglassEmpty,
                        iconTint = StatusAmber,
                        value = ui.pendingCount.toString(),
                        label = stringResource(R.string.metric_pending),
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        icon = Icons.Filled.Queue,
                        iconTint = MaterialTheme.colorScheme.primary,
                        value = ui.queueDepth.toString(),
                        label = stringResource(R.string.metric_queue),
                        modifier = Modifier.weight(1f)
                    )
                }
                // Row 2: Heartbeat & Poll
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        icon = Icons.Filled.Favorite,
                        iconTint = StatusRed,
                        value = if (ui.heartbeatMs == 0L) "—"
                        else timeFmt.format(Date(ui.heartbeatMs)),
                        label = stringResource(R.string.metric_last_heartbeat),
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        icon = Icons.Filled.Sync,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        value = if (ui.lastPollMs == 0L) "—"
                        else timeFmt.format(Date(ui.lastPollMs)),
                        label = stringResource(R.string.metric_last_poll),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Poll Now Button ──────────────────────────────────────────
        item(key = "action-poll") {
            OutlinedButton(
                onClick = vm::pollNow,
                enabled = !ui.isPolling,
                modifier = Modifier.fillMaxWidth(),
                shape = MetricCardShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (ui.isPolling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ui.isPolling) stringResource(R.string.polling)
                    else stringResource(R.string.poll_now),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // ── Section 4 : Live Log ─────────────────────────────────────
        item(key = "section-log-header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.section_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                if (ui.logs.isNotEmpty()) {
                    TextButton(onClick = vm::clearLogs) {
                        Text(
                            stringResource(R.string.clear),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (ui.logs.isEmpty()) {
            item(key = "log-empty") {
                EmptyLogPlaceholder()
            }
        } else {
            items(ui.logs, key = { it.id }) { log ->
                DispatchTransactionCard(log = log, dateFmt = dateFmt)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Section 1: System Health Cards
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun NetworkHealthCard(
    isOnline: Boolean,
    transportLabel: String,
    modifier: Modifier = Modifier
) {
    val accentColor = if (isOnline) StatusGreen else StatusRed
    val containerBg = if (isOnline) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
    }

    val icon = when {
        !isOnline                   -> Icons.Filled.WifiOff
        transportLabel == "Cellular" -> Icons.Filled.CellTower
        else                        -> Icons.Filled.Wifi
    }

    Card(
        modifier = modifier,
        shape = HealthCardShape,
        colors = CardDefaults.cardColors(containerColor = containerBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // Live status beacon dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // AnimatedContent for smooth Online/Offline transition
                AnimatedContent(
                    targetState = isOnline,
                    transitionSpec = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                    },
                    label = "networkStatus"
                ) { online ->
                    Text(
                        if (online) stringResource(R.string.network_online)
                        else stringResource(R.string.network_offline),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (online) StatusGreen else StatusRed
                    )
                }
                Text(
                    if (isOnline) transportLabel
                    else stringResource(R.string.dispatches_queueing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DeviceHealthCard(
    batteryPct: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    val batteryIcon = when {
        isCharging         -> Icons.Filled.BatteryChargingFull
        batteryPct in 0..15 -> Icons.Filled.BatteryAlert
        else               -> Icons.Filled.BatteryFull
    }
    val batteryTint = when {
        isCharging          -> StatusGreen
        batteryPct in 0..15  -> StatusRed
        batteryPct in 16..30 -> StatusAmber
        else                -> MaterialTheme.colorScheme.primary
    }

    val batteryText = when {
        batteryPct < 0 -> "—"
        isCharging     -> "$batteryPct% · ${stringResource(R.string.battery_charging)}"
        else           -> "$batteryPct%"
    }

    Card(
        modifier = modifier,
        shape = HealthCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(batteryTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    batteryIcon,
                    contentDescription = null,
                    tint = batteryTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    batteryText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.battery_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Section 2: Mission Control Gateway Card
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun GatewayControlCard(
    running: Boolean,
    serviceAlive: Boolean,
    onToggle: (Boolean) -> Unit
) {
    // Three honest states: off, genuinely running, and "toggle ON but the
    // service is dead" (e.g. killed by an app update) — which must never
    // present itself as Running.
    val stale = running && !serviceAlive
    val backgroundBrush = if (running && serviceAlive) GatewayRunningGradient else GatewayStoppedGradient
    val glowShadow = if (running && serviceAlive) CardGlowRunning else CardGlowStopped

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = MissionCardShape,
                ambientColor = glowShadow,
                spotColor = glowShadow
            ),
        shape = MissionCardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundBrush)
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.gateway_status)
                                .uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                        Spacer(Modifier.height(4.dp))

                        AnimatedContent(
                            targetState = stale to (running && serviceAlive),
                            transitionSpec = {
                                fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                            },
                            label = "gatewayStatus"
                        ) { state ->
                            Text(
                                when {
                                    state.second -> stringResource(R.string.gateway_running)
                                    state.first -> stringResource(R.string.gateway_stale)
                                    else -> stringResource(R.string.gateway_stopped)
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    BeaconPulsing(running = running && serviceAlive)
                }

                // Tactile Action Button
                Button(
                    onClick = { onToggle(!(running && serviceAlive)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            running && serviceAlive -> Color(0xFFE11D48)
                            running -> Color(0xFFD97706)
                            else -> Color(0xFF4F46E5)
                        },
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            running && serviceAlive -> stringResource(R.string.stop_gateway)
                            running -> stringResource(R.string.restart_gateway)
                            else -> stringResource(R.string.start_gateway)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (running && !serviceAlive) {
                    Text(
                        stringResource(R.string.gateway_stale_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BeaconPulsing(running: Boolean) {
    if (running) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
        val alphaGlow by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        val scaleGlow by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
            // Expanding radiant glow halo
            Box(
                modifier = Modifier
                    .size((28 * scaleGlow).dp)
                    .alpha(alphaGlow)
                    .clip(CircleShape)
                    .background(Color(0xFF34D399))
            )
            // Vibrant core beacon
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    } else {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE11D48).copy(alpha = 0.25f))
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE11D48))
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Section 3: Sync Metric Tiles
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun MetricTile(
    icon: ImageVector,
    iconTint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MetricCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Section 4: Live Log
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyLogPlaceholder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = LogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 44.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Receipt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(34.dp)
                )
            }
            Text(
                stringResource(R.string.empty_log),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DispatchTransactionCard(log: DispatchLog, dateFmt: SimpleDateFormat) {
    val isFailed = log.status.contains("failed", ignoreCase = true)
    val isConfirmed = log.isConfirmed && !isFailed
    // Over-cap, amount-mismatch and ambiguous SMS: nothing was sent, a
    // person must look — never shown as a "Timeout".
    val isReview = log.status == "review" || log.status == "ambiguous"

    val statusIcon = when {
        isFailed    -> Icons.Filled.Error
        isConfirmed -> Icons.Filled.CheckCircle
        isReview    -> Icons.Filled.Warning
        else        -> Icons.Filled.AccessTime
    }

    val statusColor = when {
        isFailed    -> StatusRed
        isConfirmed -> StatusGreen
        else        -> StatusAmber
    }

    val statusTextRes = when {
        isFailed    -> R.string.status_failed
        isConfirmed -> R.string.status_confirmed
        isReview    -> R.string.status_review
        else        -> R.string.status_timeout
    }

    val parsedAmount = remember(log.detail) { extractAmount(log.detail) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = LogCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Provider icon badge
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (parsedAmount != null) {
                    Text(
                        parsedAmount,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    log.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (parsedAmount == null) FontWeight.SemiBold
                    else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    "${stringResource(R.string.log_verify, log.verifyId)} · ${
                        dateFmt.format(Date(log.timeMs))
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                statusIcon,
                contentDescription = stringResource(statusTextRes),
                tint = statusColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** Extracts a leading amount like "500 EGP" from the log detail if present. */
private fun extractAmount(detail: String): String? {
    val regex = """^([0-9.,]+\s*(?:EGP|LE|L\.E|ج\.م|\$|USD))"""
        .toRegex(RegexOption.IGNORE_CASE)
    return regex.find(detail)?.value
}

// ═══════════════════════════════════════════════════════════════════════
//  Shared Composables
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}
