@file:OptIn(ExperimentalMaterial3Api::class)

package com.tinyggrok.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.ui.viewmodel.SettingsViewModel
import com.tinyggrok.app.ui.viewmodel.UpdateViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToUsage: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    initialSection: String? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val update by updateViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val homeScroll = rememberScrollState()

    val root = remember(initialSection) { SettingsDestination.fromSection(initialSection) }
    var page by rememberSaveable { mutableStateOf(root) }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    var locationPermissionGranted by remember { mutableStateOf(hasLocationPermission()) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationPermissionGranted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    BackHandler(enabled = page != root) { page = root }

    LaunchedEffect(page) {
        if (page == SettingsDestination.Location) {
            locationPermissionGranted = hasLocationPermission()
        }
    }

    var openedVerificationUri by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.oauthVerificationUri, uiState.oauthLoginInProgress) {
        val verifyUri = uiState.oauthVerificationUri
        if (
            uiState.oauthLoginInProgress &&
            !verifyUri.isNullOrBlank() &&
            openedVerificationUri != verifyUri
        ) {
            openedVerificationUri = verifyUri
            runCatching { uriHandler.openUri(verifyUri) }
        }
    }

    uiState.previewError?.let { err ->
        LaunchedEffect(err) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearPreviewError()
        }
    }

    fun leaveSettings() {
        if (page != root) page = root else onNavigateBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = ::leaveSettings) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        val pageModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (page) {
            SettingsDestination.Home -> SettingsHome(
                uiState = uiState,
                update = update,
                onOpen = { page = it },
                onOpenUsage = onNavigateToUsage,
                onOpenAbout = onNavigateToAbout,
                onOpenLogs = onNavigateToLogs,
                onDebugChange = viewModel::updateDebugMode,
                scrollState = homeScroll,
                modifier = pageModifier
            )
            else -> SettingsPage(pageModifier) {
                when (page) {
                    SettingsDestination.Account -> AccountSettings(uiState, viewModel)
                    SettingsDestination.Chat -> ChatSettings(uiState, viewModel)
                    SettingsDestination.Voice -> VoiceSettings(uiState, viewModel)
                    SettingsDestination.Location -> LocationSettings(
                        uiState = uiState,
                        locationPermissionGranted = locationPermissionGranted,
                        onLocationEnabledChange = { enabled ->
                            viewModel.updateLocationEnabled(enabled)
                            if (enabled && !hasLocationPermission()) {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            } else {
                                locationPermissionGranted = hasLocationPermission()
                            }
                        },
                        onCacheTimeoutChange = viewModel::updateLocationCacheTimeoutMinutes
                    )
                    SettingsDestination.Appearance -> AppearanceSettings(uiState, viewModel)
                    SettingsDestination.Management -> ManagementSettings(
                        uiState = uiState,
                        viewModel = viewModel,
                        onOpenUsage = onNavigateToUsage
                    )
                    SettingsDestination.Updates -> UpdatesSettings(update, updateViewModel)
                    SettingsDestination.Home -> Unit
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Reusable composables
// ────────────────────────────────────────────────────────────────────────────

@Composable
internal fun SettingsSection(
    title: String,
    icon: ImageVector,
    showHeader: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (showHeader) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            content()
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (checked) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// Need Box at top level of this file
@Suppress("NOTHING_TO_INLINE")
@Composable
private inline fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit
) = androidx.compose.foundation.layout.Box(
    modifier = modifier,
    contentAlignment = contentAlignment,
    content = { content() }
)

// ────────────────────────────────────────────────────────────────────────────
// GPS cache timeout (presets + custom)
// ────────────────────────────────────────────────────────────────────────────

/**
 * How long a GPS fix is reused before the next chip lookup.
 * Presets: 10m, 15m, 30m, 1h, 2h, or Custom (enter minutes). Default 10m.
 */
@Composable
internal fun GpsCacheTimeoutPicker(
    minutes: Int,
    onMinutesChange: (Int) -> Unit
) {
    val active = SettingsRepository.normalizeLocationCacheTimeoutMinutes(minutes)
    val isCustom = !SettingsRepository.isLocationCacheTimeoutPreset(active)
    var showCustomDialog by remember { mutableStateOf(false) }
    var customDraft by remember(active) {
        mutableStateOf(active.toString())
    }

    // Chip options: fixed presets + Custom
    data class TimeoutOption(val label: String, val minutes: Int?) // null = custom

    val options = listOf(
        TimeoutOption("10m", 10),
        TimeoutOption("15m", 15),
        TimeoutOption("30m", 30),
        TimeoutOption("1h", 60),
        TimeoutOption("2h", 120),
        TimeoutOption("Custom", null)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GPS cache timeout",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatCacheTimeoutLabel(active),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = "Reuse the last GPS fix for this long without waking the chip again. " +
                "Default 10 minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val selected = if (option.minutes == null) {
                    isCustom
                } else {
                    active == option.minutes
                }
                val bg = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
                val fg = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .clickable {
                            if (option.minutes != null) {
                                onMinutesChange(option.minutes)
                            } else {
                                customDraft = active.toString()
                                showCustomDialog = true
                            }
                        }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = fg,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (showCustomDialog) {
        val parsed = customDraft.toIntOrNull()
        val valid = parsed != null &&
            parsed in SettingsRepository.MIN_LOCATION_CACHE_TIMEOUT_MINUTES..
            SettingsRepository.MAX_LOCATION_CACHE_TIMEOUT_MINUTES
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom GPS cache timeout") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter minutes (1–${SettingsRepository.MAX_LOCATION_CACHE_TIMEOUT_MINUTES}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customDraft,
                        onValueChange = { draft ->
                            if (draft.isEmpty() || draft.all { it.isDigit() }) {
                                customDraft = draft.take(5)
                            }
                        },
                        label = { Text("Minutes") },
                        singleLine = true,
                        isError = customDraft.isNotEmpty() && !valid,
                        supportingText = {
                            if (customDraft.isNotEmpty() && !valid) {
                                Text("Enter a number from 1 to ${SettingsRepository.MAX_LOCATION_CACHE_TIMEOUT_MINUTES}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (parsed != null && valid) {
                            onMinutesChange(parsed)
                            showCustomDialog = false
                        }
                    },
                    enabled = valid
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

internal fun formatCacheTimeoutLabel(minutes: Int): String = when {
    minutes < 60 -> if (minutes == 1) "1 minute" else "$minutes minutes"
    minutes % 60 == 0 -> {
        val h = minutes / 60
        if (h == 1) "1 hour" else "$h hours"
    }
    else -> {
        val h = minutes / 60
        val m = minutes % 60
        if (h == 0) "$m minutes"
        else if (h == 1) "1 h $m m"
        else "$h h $m m"
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Elegant font-size picker
// ────────────────────────────────────────────────────────────────────────────

/**
 * A stepped font-size picker that shows 5 labelled size presets as tappable tiles.
 * Each tile displays a progressively larger "A" glyph so the effect is immediately
 * obvious at a glance.  The selected tile gets a filled primary background; the rest
 * use a subtle surface-variant card.  Below the tiles a live preview line shows the
 * chosen size in action.
 *
 * Range 10–24 sp is mapped to 5 discrete steps:  10, 13, 16, 20, 24 sp.
 */
@Composable
internal fun FontSizeSlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val steps = listOf(10f, 13f, 16f, 20f, 24f)
    val labels = listOf("XS", "S", "M", "L", "XL")

    // Snap the incoming value to whichever step is closest
    val activeIndex = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - value) } ?: 2

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // Header row: label on the left, current size value on the right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Font Size",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${steps[activeIndex].roundToInt()} sp",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Tile row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            steps.forEachIndexed { index, size ->
                val isActive = index == activeIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .then(
                            if (!isActive) Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp)
                            ) else Modifier
                        )
                        .clickable { onValueChange(size) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "A",
                            fontSize = (10 + index * 3).sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = (10 + index * 3 + 2).sp
                        )
                        Text(
                            text = labels[index],
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Live preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Preview: The quick brown fox",
                fontSize = steps[activeIndex].sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Voice picker — 5 tiles (Eve, Ara, Rex, Sal, Leo)
// ────────────────────────────────────────────────────────────────────────────

@Composable
internal fun VoicePicker(
    selected: com.tinyggrok.app.data.model.VoiceOption,
    previewingVoice: com.tinyggrok.app.data.model.VoiceOption?,
    onSelect: (com.tinyggrok.app.data.model.VoiceOption) -> Unit,
    onPreview: (com.tinyggrok.app.data.model.VoiceOption) -> Unit
) {
    val voices = com.tinyggrok.app.data.model.VoiceOption.values()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        voices.forEach { voice ->
            val isActive = voice == selected
            val isPreviewing = voice == previewingVoice
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(
                        if (!isActive) Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp)
                        ) else Modifier
                    )
                    .clickable { onSelect(voice) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = voice.gender,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                                else MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    // Try button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                            .clickable(enabled = !isPreviewing) { onPreview(voice) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPreviewing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "▶ Try",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = selected.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

// ────────────────────────────────────────────────────────────────────────────
// Personality mode picker — grouped card list with emoji + age-gate badge
// ────────────────────────────────────────────────────────────────────────────

@Composable
internal fun PersonalityPicker(
    selected: com.tinyggrok.app.data.model.PersonalityMode,
    previewingPersonality: com.tinyggrok.app.data.model.PersonalityMode?,
    onSelect: (com.tinyggrok.app.data.model.PersonalityMode) -> Unit,
    onPreview: (com.tinyggrok.app.data.model.PersonalityMode) -> Unit
) {
    val modes = com.tinyggrok.app.data.model.PersonalityMode.values()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        modes.forEach { mode ->
            val isActive = mode == selected
            val isPreviewing = mode == previewingPersonality
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mode) },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                     else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.elevatedCardElevation(
                    defaultElevation = if (isActive) 4.dp else 1.dp
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = mode.emoji,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // ▶ Try button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            )
                            .clickable(enabled = !isPreviewing) { onPreview(mode) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPreviewing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "▶ Try",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (isActive) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
