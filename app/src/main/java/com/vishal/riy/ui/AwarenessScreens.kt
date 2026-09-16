package com.vishal.riy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.awareness.AwarenessLockEngine
import com.vishal.riy.awareness.AwarenessSnapshot
import com.vishal.riy.awareness.Phase
import com.vishal.riy.awareness.Trigger

/**
 * Full-screen overlays for the awareness flow. Each is opaque so the browser
 * underneath is never reachable while a pause or lock is in progress; there is
 * deliberately no button that dismisses or bypasses a lock.
 */

/** Stage 2: the 10-20 second calm pause between detection and the lock. */
@Composable
fun AwarenessPauseOverlay(
    state: AwarenessSnapshot,
    modifier: Modifier = Modifier,
) {
    val message = state.message
    CalmScaffold(modifier) {
        PauseDot()
        Spacer(Modifier.height(24.dp))
        message?.let {
            Text(
                text = it.title,
                style = typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            it.body?.let { body ->
                Text(
                    text = body,
                    style = typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        // A thin, number-free indicator that the pause is finite. No countdown
        // digits: urgency is the opposite of what this moment is for.
        LinearProgressIndicator(
            progress = { state.pauseFraction },
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(3.dp),
        )
    }
}

/** Stage 3: optional one-tap feeling tag. Never forced — Skip always works. */
@Composable
fun TriggerSelectionOverlay(
    state: AwarenessSnapshot,
    onSelected: (Trigger) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalmScaffold(modifier) {
        Text(
            text = stringResource(R.string.trigger_sheet_title),
            style = typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.trigger_sheet_subtitle),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        state.triggerOptions.forEach { trigger ->
            OutlinedButton(
                onClick = { onSelected(trigger) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(triggerLabel(trigger))
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.trigger_skip))
        }
    }
}

/** Stage 4: browsing is locked. Shows remaining time, offers no way out. */
@Composable
fun LockOverlay(
    state: AwarenessSnapshot,
    modifier: Modifier = Modifier,
) {
    CalmScaffold(modifier) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.lock_protection_active),
            style = typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.message?.title.orEmpty(),
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(
                R.string.lock_remaining,
                AwarenessLockEngine.formatRemaining(state.remainingMillis),
            ),
            style = typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.message?.body.orEmpty(),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Minimal progress block shown on the protection screen. Streaks are
 * intentionally absent — the metrics here are awareness, not scorekeeping.
 */
@Composable
fun AwarenessProgressSection(
    state: AwarenessSnapshot,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.progress_title),
                style = typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ProgressLine(stringResource(R.string.progress_avoided), state.metrics.adultSearchesAvoided.toString())
            ProgressLine(
                stringResource(R.string.progress_pauses),
                state.metrics.awarenessPausesCompleted.toString(),
            )
            ProgressLine(
                stringResource(R.string.progress_protection),
                stringResource(if (state.locked) R.string.progress_protection_locked else R.string.progress_protection_open),
            )
            ProgressLine(
                stringResource(R.string.progress_trigger),
                state.topTrigger7d?.let { triggerLabel(it) }
                    ?: stringResource(R.string.progress_trigger_none),
            )
        }
    }
}

@Composable
private fun ProgressLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = typography.bodyMedium, color = colorScheme.onSurfaceVariant)
        Text(value, style = typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CalmScaffold(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PauseDot() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(colorScheme.tertiaryContainer, CircleShape),
    )
}

@Composable
private fun triggerLabel(trigger: Trigger): String = when (trigger) {
    Trigger.BOREDOM -> stringResource(R.string.trigger_boredom)
    Trigger.STRESS -> stringResource(R.string.trigger_stress)
    Trigger.LONELINESS -> stringResource(R.string.trigger_loneliness)
    Trigger.HABIT -> stringResource(R.string.trigger_habit)
    Trigger.LATE_NIGHT -> stringResource(R.string.trigger_late_night)
    Trigger.OTHER -> stringResource(R.string.trigger_other)
}

