package com.vishal.riy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vishal.riy.R

/**
 * Where the app is showing. The project has no navigation library, so this
 * extends the same single-routing approach [RiyApp] already used for the lock
 * screen rather than introducing a second mechanism.
 *
 * Order matters: the lock screen is decided BEFORE this is consulted, so a
 * live restriction can never be navigated away from.
 */
enum class AppScreen { MAIN, TRACKER, CHECK_IN }

/** The entry button rendered on the main protection screen. */
@Composable
fun TrackerEntryRow(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.tracker_entry_title),
                style = typography.titleMedium,
                color = colorScheme.onSurface,
            )
        }
    }
}