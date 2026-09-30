@file:OptIn(ExperimentalMaterial3Api::class)

package com.tinyggrok.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinyggrok.app.AppDefaults
import com.tinyggrok.app.data.repository.AuthMode
import com.tinyggrok.app.ui.theme.AppTheme
import com.tinyggrok.app.ui.viewmodel.ApiKeyCheckUi
import com.tinyggrok.app.ui.viewmodel.SettingsUiState
import com.tinyggrok.app.ui.viewmodel.SettingsViewModel
import com.tinyggrok.app.ui.viewmodel.UpdateUiState
import com.tinyggrok.app.ui.viewmodel.UpdateViewModel
import kotlin.math.abs

internal enum class SettingsDestination(val title: String) {
    Home("Settings"),
    Account("Account"),
    Chat("Model & replies"),
    Voice("Voice"),
    Location("Location"),
    Appearance("Theme & text"),
    Management("Management key"),
    Updates("Updates");

    companion object {
        fun fromSection(section: String?): SettingsDestination = when (section) {
            "management" -> Management
            else -> Home
        }
    }
}

@Composable
internal fun SettingsHome(
    uiState: SettingsUiState,
    update: UpdateUiState,
    onOpen: (SettingsDestination) -> Unit,
    onOpenUsage: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLogs: () -> Unit,
    onDebugChange: (Boolean) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SettingsGroup(title = "Sign in") {
            SettingsNavRow(
                title = "Account",
                subtitle = accountSummary(uiState),
                icon = Icons.Default.VpnKey,
                onClick = { onOpen(SettingsDestination.Account) }
            )
        }
        SettingsGroup(title = "Chat") {
            SettingsNavRow(
                title = "Model & replies",
                subtitle = chatSummary(uiState),
                icon = Icons.Default.TextFields,
                onClick = { onOpen(SettingsDestination.Chat) }
            )
            SettingsInsetDivider()
            SettingsNavRow(
                title = "Voice",
                subtitle = voiceSummary(uiState),
                icon = Icons.Default.Mic,
                onClick = { onOpen(SettingsDestination.Voice) }
            )
            SettingsInsetDivider()
            SettingsNavRow(
                title = "Location",
                subtitle = locationSummary(uiState),
                icon = Icons.Default.LocationOn,
                onClick = { onOpen(SettingsDestination.Location) }
            )
        }
        SettingsGroup(title = "Appearance") {
            SettingsNavRow(
                title = "Theme & text",
                subtitle = appearanceSummary(uiState),
                icon = Icons.Default.Palette,
                onClick = { onOpen(SettingsDestination.Appearance) }
            )
        }
        SettingsGroup(title = "Billing") {
            SettingsNavRow(
                title = "Credits & usage",
                subtitle = "Balance, quotas, and recent spend",
                icon = Icons.Default.AttachMoney,
                onClick = onOpenUsage
            )
            SettingsInsetDivider()
            SettingsNavRow(
                title = "Management key",
                subtitle = if (uiState.managementKey.isBlank()) "Not set" else "Key saved",
                icon = Icons.Default.Key,
                onClick = { onOpen(SettingsDestination.Management) }
            )
        }
        SettingsGroup(title = "App") {
            SettingsNavRow(
                title = "Updates",
                subtitle = updateSummary(update),
                icon = Icons.Default.SystemUpdate,
                onClick = { onOpen(SettingsDestination.Updates) }
            )
            SettingsInsetDivider()
            SettingsNavRow(
                title = "About",
                subtitle = "Version, licenses, and credits",
                icon = Icons.Default.Info,
                onClick = onOpenAbout
            )
            SettingsInsetDivider()
            SettingsNavRow(
                title = "Logs",
                subtitle = if (uiState.debugMode) {
                    "How long each prompt took, and API traffic"
                } else {
                    "How long each prompt took"
                },
                icon = Icons.Default.Timer,
                onClick = onOpenLogs
            )
            SettingsInsetDivider()
            SettingsSwitchRow(
                title = "Debug mode",
                subtitle = "Also log full API requests and responses",
                icon = Icons.Default.BugReport,
                checked = uiState.debugMode,
                onCheckedChange = onDebugChange
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun SettingsPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun ColumnScope.AccountSettings(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val showKey = remember { mutableStateOf(false) }
    val checkingKey = uiState.apiKeyCheck is ApiKeyCheckUi.Checking
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    SettingsSection(title = "How chat signs in", icon = Icons.Default.VerifiedUser) {
        Text(
            text = "Chat uses the option you pick here. The API key and SuperGrok sign-in are both kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        SectionLabel("Mode")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(
                "API key" to AuthMode.API_KEY,
                "SuperGrok" to AuthMode.SUPERGROK_OAUTH
            ).forEachIndexed { index, (label, mode) ->
                SegmentedButton(
                    selected = uiState.authMode == mode,
                    onClick = { viewModel.updateAuthMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    enabled = !uiState.oauthLoginInProgress
                ) { Text(label, maxLines = 1) }
            }
        }
    }

    SettingsSection(title = "API key", icon = Icons.Default.VpnKey) {
        Text(
            "Used when mode is API key. Create keys at console.x.ai (prepaid credits).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = viewModel::updateApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("xAI API key") },
            singleLine = true,
            enabled = !checkingKey,
            leadingIcon = {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            visualTransformation = if (showKey.value) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showKey.value = !showKey.value }) {
                    Icon(
                        imageVector = if (showKey.value) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
                        contentDescription = if (showKey.value) "Hide key" else "Show key"
                    )
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = viewModel::saveApiKey,
            modifier = Modifier.fillMaxWidth(),
            enabled = !checkingKey
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save")
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::checkApiKey,
                modifier = Modifier.weight(1f),
                enabled = !checkingKey && uiState.apiKey.isNotBlank()
            ) {
                if (checkingKey && uiState.authMode == AuthMode.API_KEY) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (checkingKey && uiState.authMode == AuthMode.API_KEY) "Checking…" else "Check")
            }
            OutlinedButton(
                onClick = viewModel::clearApiKey,
                modifier = Modifier.weight(1f),
                enabled = !checkingKey
            ) {
                Text("Clear")
            }
        }
    }

    SettingsSection(title = "SuperGrok", icon = Icons.AutoMirrored.Filled.Login) {
        Text(
            "Device-code login via auth.x.ai (same OIDC family as Grok Build). " +
                "Experimental — not an official Tiny Grok entitlement.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        if (uiState.oauthSignedIn) {
            Text(
                text = "Signed in" + (uiState.oauthEmail?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::signOutSuperGrok,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sign out")
                }
                if (uiState.authMode == AuthMode.SUPERGROK_OAUTH) {
                    OutlinedButton(
                        onClick = viewModel::checkApiKey,
                        modifier = Modifier.weight(1f),
                        enabled = !checkingKey
                    ) {
                        if (checkingKey) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (checkingKey) "Checking…" else "Check")
                    }
                }
            }
        } else if (uiState.oauthLoginInProgress) {
            val verifyUri = uiState.oauthVerificationUri
            uiState.oauthUserCode?.let { code ->
                Text(
                    text = code,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
            }
            uiState.oauthLoginMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!verifyUri.isNullOrBlank()) {
                            runCatching { uriHandler.openUri(verifyUri) }
                                .onFailure {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(verifyUri))
                                    )
                                }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !verifyUri.isNullOrBlank()
                ) {
                    Icon(
                        Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Open browser")
                }
                OutlinedButton(
                    onClick = viewModel::cancelSuperGrokLogin,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
            }
        } else {
            Button(
                onClick = viewModel::startSuperGrokLogin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sign in with SuperGrok")
            }
            uiState.oauthLoginMessage?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    CredentialStatus(uiState)
}

@Composable
internal fun ColumnScope.ChatSettings(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(title = "Chat", icon = Icons.Default.TextFields, showHeader = false) {
        SectionLabel("Grok model")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppDefaults.CHAT_MODELS.forEachIndexed { index, (label, id) ->
                SegmentedButton(
                    selected = uiState.chatModel == id,
                    onClick = { viewModel.updateChatModel(id) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppDefaults.CHAT_MODELS.size
                    )
                ) { Text(label, maxLines = 1) }
            }
        }
        Text(
            "Grok 4.7 by default, xAI's most capable model. 4.6 is the backup if " +
                "your choice is unavailable. (4.7 Fast is not on the public API.)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        SectionLabel("Response format")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("HTML" to "html", "Markdown" to "markdown")
                .forEachIndexed { index, (label, value) ->
                    SegmentedButton(
                        selected = uiState.responseFormat == value,
                        onClick = { viewModel.updateResponseFormat(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                    ) { Text(label) }
                }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        SettingsToggleRow(
            title = "Show cost per query",
            subtitle = "Display token cost below each response",
            icon = Icons.Default.AttachMoney,
            checked = uiState.showCost,
            onCheckedChange = viewModel::updateShowCost
        )
    }
}

@Composable
internal fun ColumnScope.VoiceSettings(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(title = "Voice Translator", icon = Icons.Default.Mic, showHeader = false) {
        SettingsToggleRow(
            title = "Enable Voice Translator",
            subtitle = "Real-time translation via Grok Voice API · \$0.05 / min",
            icon = Icons.Default.RecordVoiceOver,
            checked = uiState.voiceEnabled,
            onCheckedChange = viewModel::updateVoiceEnabled
        )
        if (uiState.voiceEnabled) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "Speaking Voice",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            VoicePicker(
                selected = uiState.voiceOption,
                previewingVoice = uiState.previewingVoice,
                onSelect = viewModel::updateVoiceOption,
                onPreview = viewModel::previewVoice
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Personality Mode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            PersonalityPicker(
                selected = uiState.personalityMode,
                previewingPersonality = uiState.previewingPersonality,
                onSelect = viewModel::updatePersonalityMode,
                onPreview = viewModel::previewPersonality
            )
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            MicrophoneSensitivity(
                threshold = uiState.vadThreshold,
                onChange = viewModel::updateVadThreshold
            )
        }
    }
}

@Composable
internal fun ColumnScope.LocationSettings(
    uiState: SettingsUiState,
    locationPermissionGranted: Boolean,
    onLocationEnabledChange: (Boolean) -> Unit,
    onCacheTimeoutChange: (Int) -> Unit
) {
    val locationSubtitle = when {
        !uiState.locationEnabled -> "Off — answers won't use your location"
        locationPermissionGranted -> "On — approximate location used for local / transit answers"
        else -> "On — location permission needed (tap switch again or grant in system settings)"
    }
    SettingsSection(title = "Location", icon = Icons.Default.LocationOn, showHeader = false) {
        SettingsToggleRow(
            title = "Use GPS location",
            subtitle = locationSubtitle,
            icon = Icons.Default.LocationOn,
            checked = uiState.locationEnabled,
            onCheckedChange = onLocationEnabledChange
        )
        if (uiState.locationEnabled) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            GpsCacheTimeoutPicker(
                minutes = uiState.locationCacheTimeoutMinutes,
                onMinutesChange = onCacheTimeoutChange
            )
        }
    }
}

@Composable
internal fun ColumnScope.AppearanceSettings(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(title = "Appearance", icon = Icons.Default.Palette, showHeader = false) {
        SectionLabel("Theme")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(
                "Light" to AppTheme.LIGHT,
                "Dark" to AppTheme.DARK,
                "Tokyo Night" to AppTheme.TOKYO_NIGHT
            ).forEachIndexed { index, (label, theme) ->
                SegmentedButton(
                    selected = uiState.theme == theme,
                    onClick = { viewModel.updateTheme(theme) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                ) { Text(label, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        FontSizeSlider(
            value = uiState.fontSize,
            onValueChange = viewModel::updateFontSize
        )
    }
}

@Composable
internal fun ColumnScope.ManagementSettings(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenUsage: () -> Unit
) {
    val showMgmtKey = remember { mutableStateOf(false) }
    SettingsSection(title = "Management key", icon = Icons.Default.Key, showHeader = false) {
        Text(
            "Optional. Used only by Credits & usage to load prepaid balance and rate quotas. " +
                "Create a key at console.x.ai → Settings → Management Keys " +
                "(not the same as your chat API key).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = uiState.managementKey,
            onValueChange = viewModel::updateManagementKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Management key") },
            singleLine = true,
            visualTransformation = if (showMgmtKey.value) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showMgmtKey.value = !showMgmtKey.value }) {
                    Icon(
                        imageVector = if (showMgmtKey.value) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
                        contentDescription = if (showMgmtKey.value) "Hide key" else "Show key"
                    )
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = uiState.teamId,
            onValueChange = viewModel::updateTeamId,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Team ID (optional)") },
            singleLine = true,
            supportingText = {
                Text("Leave blank to auto-detect from the management key.")
            }
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = viewModel::saveManagementCredentials,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
            OutlinedButton(
                onClick = viewModel::clearManagementCredentials,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear")
            }
        }
        uiState.savedMessage?.let { msg ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    OutlinedButton(
        onClick = onOpenUsage,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.AttachMoney, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Credits & usage")
    }
}

@Composable
internal fun ColumnScope.UpdatesSettings(
    update: UpdateUiState,
    updateViewModel: UpdateViewModel
) {
    SettingsSection(title = "App updates", icon = Icons.Default.SystemUpdate, showHeader = false) {
        Text(
            "Version ${update.currentVersion}. New builds are published as GitHub releases.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsToggleRow(
            title = "Check for updates",
            subtitle = "Look for a newer release when the app opens, once a day",
            icon = Icons.Default.SystemUpdate,
            checked = update.autoCheck,
            onCheckedChange = updateViewModel::setAutoCheck
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = updateViewModel::checkNow,
                enabled = !update.checking && update.downloadProgress == null
            ) {
                Text(if (update.checking) "Checking…" else "Check now")
            }
            update.available?.let { available ->
                Button(
                    onClick = updateViewModel::downloadAndInstall,
                    enabled = update.downloadProgress == null
                ) { Text("Install ${available.versionName}") }
            }
        }
        update.downloadProgress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
        update.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun CredentialStatus(uiState: SettingsUiState) {
    when (val check = uiState.apiKeyCheck) {
        is ApiKeyCheckUi.Success -> Text(
            text = check.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        is ApiKeyCheckUi.Failure -> Text(
            text = check.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        else -> Unit
    }
    uiState.savedMessage?.let { msg ->
        Text(
            text = msg,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun MicrophoneSensitivity(
    threshold: Float,
    onChange: (Float) -> Unit
) {
    val micTileData = listOf(
        Triple("Max", 0.10f, 5),
        Triple("High", 0.30f, 4),
        Triple("Mid", 0.50f, 3),
        Triple("Low", 0.70f, 2),
        Triple("Min", 0.90f, 1)
    )
    val micTileDescs = listOf(
        "Picks up whispers",
        "Quiet speech",
        "Balanced",
        "Loud speech only",
        "Filters noise"
    )
    val micTileBarHeights = listOf(8, 11, 16, 20, 24)
    val selMicIdx = micTileData
        .indexOfFirst { abs(it.second - threshold) < 0.06f }
        .coerceAtLeast(0)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Microphone Sensitivity",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            micTileDescs[selMicIdx],
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        micTileData.forEachIndexed { idx, (label, value, activeBars) ->
            val isSel = idx == selMicIdx
            val tileBg = if (isSel) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
            val activeBarColor = if (isSel) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            }
            val inactiveBarColor = activeBarColor.copy(alpha = 0.12f)
            val labelColor = if (isSel) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tileBg)
                    .clickable { onChange(value) }
                    .padding(vertical = 10.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.height(26.dp)
                    ) {
                        micTileBarHeights.forEachIndexed { barIdx, h ->
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(h.dp)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 2.dp,
                                            topEnd = 2.dp,
                                            bottomStart = 1.dp,
                                            bottomEnd = 1.dp
                                        )
                                    )
                                    .background(
                                        if (barIdx < activeBars) activeBarColor else inactiveBarColor
                                    )
                            )
                        }
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "← more sensitive",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            "less sensitive →",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconWell(icon = icon, highlighted = true)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconWell(icon = icon, highlighted = checked)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsIconWell(
    icon: ImageVector,
    highlighted: Boolean
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsInsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

private fun accountSummary(uiState: SettingsUiState): String {
    if (uiState.oauthLoginInProgress) {
        return uiState.oauthUserCode?.let { "Signing in · $it" } ?: "Signing in…"
    }
    return when (uiState.authMode) {
        AuthMode.API_KEY ->
            if (uiState.apiKey.isBlank()) "API key not set" else "API key saved"
        AuthMode.SUPERGROK_OAUTH -> {
            val message = uiState.oauthLoginMessage
            when {
                uiState.oauthSignedIn ->
                    uiState.oauthEmail?.let { "SuperGrok · $it" } ?: "SuperGrok signed in"
                !message.isNullOrBlank() -> message
                else -> "SuperGrok · not signed in"
            }
        }
    }
}

private fun chatSummary(uiState: SettingsUiState): String {
    val model = AppDefaults.CHAT_MODELS
        .firstOrNull { it.second == uiState.chatModel }
        ?.first
        ?: uiState.chatModel
    val format = if (uiState.responseFormat == "markdown") "Markdown" else "HTML"
    val cost = if (uiState.showCost) " · cost on" else ""
    return "Grok $model · $format$cost"
}

private fun voiceSummary(uiState: SettingsUiState): String {
    if (!uiState.voiceEnabled) return "Off"
    return "${uiState.voiceOption.displayName} · ${uiState.personalityMode.displayName}"
}

private fun locationSummary(uiState: SettingsUiState): String {
    if (!uiState.locationEnabled) return "Off"
    return "On · ${formatCacheTimeoutLabel(uiState.locationCacheTimeoutMinutes)}"
}

private fun appearanceSummary(uiState: SettingsUiState): String {
    val theme = when (uiState.theme) {
        AppTheme.LIGHT -> "Light"
        AppTheme.DARK -> "Dark"
        AppTheme.TOKYO_NIGHT -> "Tokyo Night"
    }
    val steps = listOf(10f, 13f, 16f, 20f, 24f)
    val labels = listOf("XS", "S", "M", "L", "XL")
    val index = steps.indices.minByOrNull { abs(steps[it] - uiState.fontSize) } ?: 2
    return "$theme · ${labels[index]}"
}

private fun updateSummary(update: UpdateUiState): String = when {
    update.checking -> "Checking…"
    update.available != null ->
        "Version ${update.currentVersion} · ${update.available.versionName} ready"
    else -> "Version ${update.currentVersion}"
}
