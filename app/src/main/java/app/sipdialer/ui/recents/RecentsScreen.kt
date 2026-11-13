package app.sipdialer.ui.recents

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.sipdialer.R
import app.sipdialer.sip.CallDirection
import app.sipdialer.sip.RecentCall
import app.sipdialer.ui.components.Avatar
import app.sipdialer.ui.components.EmptyState
import app.sipdialer.ui.theme.callColors

@Composable
fun RecentsScreen(
    recents: List<RecentCall>,
    onCall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recents.isEmpty()) {
        EmptyState(
            icon = R.drawable.ic_history,
            title = "No recent calls",
            body = "Calls you make and receive over SIP will show up here.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(recents, key = { i, r -> "${r.startMs}-$i" }) { index, call ->
            SegmentedListItem(
                onClick = { onCall(call.remoteUri) },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = recents.size),
                leadingContent = { Avatar(call.title) },
                supportingContent = { CallDetails(call) },
                trailingContent = {
                    IconButton(onClick = { onCall(call.remoteUri) }) {
                        Icon(painterResource(R.drawable.ic_call), contentDescription = "Call ${call.title}")
                    }
                },
            ) {
                Text(
                    call.title,
                    color = if (call.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CallDetails(call: RecentCall) {
    val (icon, tint, label) = when {
        call.missed -> Triple(R.drawable.ic_call_missed, MaterialTheme.colorScheme.error, "Missed")
        call.direction == CallDirection.OUTGOING ->
            Triple(R.drawable.ic_call_made, MaterialTheme.callColors.accept, "Outgoing")
        else -> Triple(R.drawable.ic_call_received, MaterialTheme.colorScheme.primary, "Incoming")
    }
    val time = DateUtils.getRelativeTimeSpanString(
        call.startMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    val duration = if (call.durationSec > 0) " · ${DateUtils.formatElapsedTime(call.durationSec.toLong())}" else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("$time$duration", modifier = Modifier.padding(end = 4.dp))
    }
}
