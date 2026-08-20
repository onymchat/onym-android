package app.onym.android.backup.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.onym.android.strings.R

/** One vendor row's display data — a plain snapshot, not a live
 *  dependency bundle, so this screen doesn't need to know how a
 *  vendor's status is produced. */
data class BackupVendorRow(
    val componentId: String,
    val displayName: String,
    val status: DeviceBackupStatus,
)

/**
 * Lists every backup vendor the holder is currently consented to — a
 * holder may back up to several operators at once, each under its own
 * seed-derived key material (see `BackupSeat`'s doc comment on the
 * app side). Tapping a row opens that vendor's own settings/enrolment
 * screen.
 */
@Composable
fun BackupVendorsListScreen(
    vendors: List<BackupVendorRow>,
    onVendorClick: (componentId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { padding ->
        if (vendors.isEmpty()) {
            Text(
                stringResource(R.string.backup_vendors_empty),
                modifier = Modifier.testTag("backup.vendors.empty"),
            )
        } else {
            LazyColumn(contentPadding = padding, modifier = Modifier.testTag("backup.vendors.list")) {
                items(vendors, key = { it.componentId }) { vendor ->
                    Button(
                        onClick = { onVendorClick(vendor.componentId) },
                        modifier = Modifier.testTag("backup.vendors.row.${vendor.componentId}"),
                    ) {
                        Text("${vendor.displayName} — ${statusText(vendor.status)}")
                    }
                }
            }
        }
    }
}
