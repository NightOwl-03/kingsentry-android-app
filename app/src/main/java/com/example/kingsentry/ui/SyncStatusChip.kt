package com.example.kingsentry.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.kingsentry.data.sync.SyncUiState
import com.example.kingsentry.ui.theme.ForestGreen
import kotlinx.coroutines.delay

/**
 * Material 3 reusable chip displaying the active [SyncUiState] with smooth animations,
 * localized text, rotating sync indicators, and a 3-second auto-collapse for [SyncUiState.Synced].
 */
@Composable
fun SyncStatusChip(
    state: SyncUiState,
    strings: AppStrings,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isCollapsed by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is SyncUiState.Synced) {
            isCollapsed = false
            delay(3000)
            isCollapsed = true
        } else {
            isCollapsed = false
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = when (state) {
            is SyncUiState.Offline -> Color.Gray.copy(alpha = 0.15f)
            is SyncUiState.Checking -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            is SyncUiState.Connected -> ForestGreen.copy(alpha = 0.15f)
            is SyncUiState.Syncing -> ForestGreen.copy(alpha = 0.20f)
            is SyncUiState.Synced -> ForestGreen.copy(alpha = 0.15f)
            is SyncUiState.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        },
        label = "chip_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when (state) {
            is SyncUiState.Offline -> Color.DarkGray
            is SyncUiState.Checking -> MaterialTheme.colorScheme.primary
            is SyncUiState.Connected -> ForestGreen
            is SyncUiState.Syncing -> ForestGreen
            is SyncUiState.Synced -> ForestGreen
            is SyncUiState.Failed -> MaterialTheme.colorScheme.error
        },
        label = "chip_fg"
    )

    val contentDesc = when (state) {
        is SyncUiState.Offline -> "Offline status, ${state.pending} pending scans"
        is SyncUiState.Checking -> "Checking internet connection"
        is SyncUiState.Connected -> "Connected to internet"
        is SyncUiState.Syncing -> "Syncing ${state.done} of ${state.total}"
        is SyncUiState.Synced -> "All scans synchronized"
        is SyncUiState.Failed -> "Sync failed, tap to retry"
    }

    Surface(
        modifier = modifier
            .semantics { contentDescription = contentDesc }
            .clickable(enabled = (state is SyncUiState.Failed) || (state is SyncUiState.Offline && state.pending > 0) || (state is SyncUiState.Synced)) {
                onRetry()
            },
        color = backgroundColor,
        shape = RoundedCornerShape(16.dp),
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220)) },
            label = "chip_content"
        ) { targetState ->
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (targetState) {
                    is SyncUiState.Offline -> {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        if (!isCollapsed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            val text = if (targetState.pending > 0) {
                                "${strings.syncOffline} · ${targetState.pending} ${strings.syncWaiting}"
                            } else {
                                strings.syncOffline
                            }
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }

                    is SyncUiState.Checking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = contentColor
                        )
                        if (!isCollapsed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.syncChecking,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }

                    is SyncUiState.Connected -> {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        if (!isCollapsed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.syncConnected,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }

                    is SyncUiState.Syncing -> {
                        val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
                        val rotationAngle by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
                            label = "rotation"
                        )
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(rotationAngle)
                        )
                        if (!isCollapsed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${strings.syncSyncing} ${targetState.done} / ${targetState.total}",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }

                    is SyncUiState.Synced -> {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        if (!isCollapsed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.syncAllSynced,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }

                    is SyncUiState.Failed -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        if (!isCollapsed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.syncFailedTapRetry,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
