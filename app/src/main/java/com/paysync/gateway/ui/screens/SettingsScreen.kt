package com.paysync.gateway.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paysync.gateway.R
import com.paysync.gateway.ui.MainViewModel
import com.paysync.gateway.ui.theme.StatusGreen

private val SettingsCardShape = RoundedCornerShape(18.dp)
private val FieldShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val botUrl by vm.botUrl.collectAsStateWithLifecycle()
    val secret by vm.secret.collectAsStateWithLifecycle()
    val pollSeconds by vm.pollSeconds.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val newProvider by vm.newProvider.collectAsStateWithLifecycle()
    val isSaving by vm.isSaving.collectAsStateWithLifecycle()
    val saveConfirmed by vm.saveConfirmed.collectAsStateWithLifecycle()
    val amountFallback by vm.amountFallback.collectAsStateWithLifecycle()
    val maxAutoAmount by vm.maxAutoAmount.collectAsStateWithLifecycle()
    val updateCheckEnabled by vm.updateCheckEnabled.collectAsStateWithLifecycle()
    var secretVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Card 1: Backend Connection ───────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = SettingsCardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    stringResource(R.string.bot_backend),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = botUrl,
                    onValueChange = { vm.botUrl.value = it },
                    label = { Text(stringResource(R.string.bot_url)) },
                    placeholder = { Text(stringResource(R.string.bot_url_hint)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    shape = FieldShape,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = secret,
                    onValueChange = { vm.secret.value = it },
                    label = { Text(stringResource(R.string.webhook_secret)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    shape = FieldShape,
                    singleLine = true,
                    visualTransformation = if (secretVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                if (secretVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (secretVisible) R.string.hide_secret
                                    else R.string.show_secret
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pollSeconds,
                    onValueChange = { vm.pollSeconds.value = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.poll_interval)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    shape = FieldShape,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = vm::saveConfig,
                    enabled = !isSaving,
                    shape = FieldShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.saving),
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            stringResource(R.string.save_config),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                AnimatedVisibility(
                    visible = saveConfirmed,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = StatusGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            stringResource(R.string.saved),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = StatusGreen
                        )
                    }
                }
            }
        }

        // ── Card 2: Matching Safety ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = SettingsCardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    stringResource(R.string.safety_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.amount_fallback_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.amount_fallback_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = amountFallback,
                        onCheckedChange = { vm.amountFallback.value = it }
                    )
                }

                OutlinedTextField(
                    value = maxAutoAmount,
                    onValueChange = { vm.maxAutoAmount.value = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(stringResource(R.string.max_amount_label)) },
                    placeholder = { Text(stringResource(R.string.max_amount_hint)) },
                    shape = FieldShape,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.max_amount_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.update_check_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.update_check_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = updateCheckEnabled,
                        onCheckedChange = { vm.setUpdateCheckEnabled(it) }
                    )
                }
            }
        }

        // ── Card 3: Allowed Senders ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = SettingsCardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.allowed_senders),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.senders_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newProvider,
                        onValueChange = { vm.newProvider.value = it },
                        label = { Text(stringResource(R.string.new_sender)) },
                        shape = FieldShape,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = vm::addProvider,
                        shape = FieldShape,
                        modifier = Modifier.height(54.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.add),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    providers.forEach { name ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = {
                                Text(name, fontWeight = FontWeight.SemiBold)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = InputChipDefaults.inputChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                                    .copy(alpha = 0.5f),
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = { vm.removeProvider(name) },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(
                                            R.string.remove_sender, name
                                        ),
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // Bottom spacer for nav bar clearance
        Spacer(Modifier.height(88.dp))
    }
}
