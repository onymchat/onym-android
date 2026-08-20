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
 *
 * This is also where a holder ACQUIRES a vendor: [catalogSection] is
 * the discovery-backed "from catalog" list, rendered below whatever is
 * already consented. Without it this screen can only ever show the
 * empty state — nothing else in the app offers a `storage.backup`
 * operator to consent to, so the seat would be a settings section that
 * appears only for holders who somehow already had a pinned consent.
 */
@Composable
fun BackupVendorsListScreen(
    vendors: List<BackupVendorRow>,
    onVendorClick: (componentId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** The operators discovery is offering for this seat, as a slot:
     *  :backup-ui has no :discovery dependency and no business gaining
     *  one — the composition root renders the catalog rows with the
     *  same atoms every other seat's picker uses. Null (the default)
     *  renders the screen exactly as it did before discovery reached
     *  this seat, which is what the UI-test harness without discovery
     *  fakes sees. */
    catalogSection: (@Composable () -> Unit)? = null,
) {
    Scaffold(modifier = modifier) { padding ->
        // One list either way: the catalog section has to be reachable
        // in BOTH states — a holder with no vendor needs it to get a
        // first one, and a holder with one needs it to add a second
        // (this seat is multi-vendor by construction).
        LazyColumn(contentPadding = padding, modifier = Modifier.testTag("backup.vendors.list")) {
            if (vendors.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.backup_vendors_empty),
                        modifier = Modifier.testTag("backup.vendors.empty"),
                    )
                }
            } else {
                items(vendors, key = { it.componentId }) { vendor ->
                    Button(
                        onClick = { onVendorClick(vendor.componentId) },
                        modifier = Modifier.testTag("backup.vendors.row.${vendor.componentId}"),
                    ) {
                        Text("${vendor.displayName} — ${statusText(vendor.status)}")
                    }
                }
            }
            catalogSection?.let { section -> item { section() } }
        }
    }
}
