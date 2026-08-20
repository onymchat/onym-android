package app.onym.android.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The device-backup consent screen. Leads with, in this order: (1)
 * what backing up does to *other people* in the person's
 * conversations, (2) that nobody — not the operator, not Onym — can
 * recover the archive without the recovery phrase, (3) the honest
 * scheduling sentence. Then the operator's terms in full,
 * unsummarized, from [items]. "Turn On Backup" stays disabled until
 * [hasReachedEnd] is true.
 *
 * Mirrors `BackupEnrolmentView` in onym-ios OnymBackupUI.
 */
@Composable
fun BackupEnrolmentScreen(
    items: List<BackupDisclosureItem>,
    scheduleSentence: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val reachedEnd by remember {
        derivedStateOf { hasReachedEnd(listState) }
    }

    Scaffold(modifier = modifier) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = true)) {
                item {
                    Text(
                        BackupDisclosure.THIRD_PARTY_CONSEQUENCE,
                        modifier = Modifier.padding(16.dp).testTag("backup.enrolment.third_party"),
                    )
                }
                item {
                    Text(
                        BackupDisclosure.NO_RESET_PATH,
                        modifier = Modifier.padding(16.dp).testTag("backup.enrolment.no_reset"),
                    )
                }
                item {
                    Text(
                        scheduleSentence,
                        modifier = Modifier.padding(16.dp).testTag("backup.enrolment.schedule"),
                    )
                }
                items(items, key = { it.id }) { item ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(item.label)
                        Text(item.value, modifier = Modifier.testTag("backup.enrolment.term.${item.id}"))
                    }
                }
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAccept,
                    enabled = reachedEnd,
                    modifier = Modifier.testTag("backup.enrolment.accept"),
                ) {
                    Text("Turn On Backup")
                }
                Button(onClick = onDecline, modifier = Modifier.testTag("backup.enrolment.decline")) {
                    Text("Not Now")
                }
            }
        }
    }
}

/**
 * Pure predicate: has the list been scrolled to its end? Extracted
 * out of the Composable so it's independently testable. Content
 * shorter than the viewport counts as already-read — there's nothing
 * more to scroll to — but that's only knowable AFTER the first
 * measurement pass: `visibleItemsInfo` is also empty before
 * `LazyColumn` has measured anything at all (first composition frame,
 * briefly after a configuration change), and defaulting to `true` in
 * that ambiguous case would let "Turn On Backup" render enabled for a
 * frame before the person has read anything.
 */
internal fun hasReachedEnd(state: LazyListState): Boolean {
    val layoutInfo = state.layoutInfo
    if (layoutInfo.totalItemsCount == 0) return false
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    val isLastItemVisible = lastVisible.index == layoutInfo.totalItemsCount - 1
    val isLastItemFullyVisible = lastVisible.offset + lastVisible.size <= layoutInfo.viewportEndOffset
    return isLastItemVisible && isLastItemFullyVisible
}
