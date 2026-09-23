package app.onym.android.naming

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.onym.android.group.ChatGroup
import app.onym.android.group.MemberProfile
import app.onym.android.identity.IdentityId
import app.onym.android.strings.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun NamingPanel(controller: NamingController, owner: IdentityId) {
    val enabled by controller.enabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var holder by remember(owner) { mutableStateOf<NamingController.Holder?>(null) }
    var account by remember(owner) { mutableStateOf("") }
    var offer by remember(owner) { mutableStateOf<JsonObject?>(null) }
    var current by remember(owner) { mutableStateOf<BsnNamingClient.Name?>(null) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(controller, owner) {
        try { holder = controller.holder(owner) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "BSN request failed" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.bsn_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.bsn_consent))
        Row {
            Checkbox(owner.value in enabled, onCheckedChange = { controller.enable(owner, it) })
            Text(stringResource(R.string.bsn_enable))
        }
        Text(stringResource(R.string.bsn_binding))
        holder?.let { h -> SelectionContainer { Text(h.stellarAccount) } }
        TextButton(onClick = { uri.openUri(BsnNamingClient.BASE + "guide/") }) { Text(stringResource(R.string.bsn_guide)) }
        OutlinedTextField(account, { account = it.trim(); offer = null }, label = { Text(stringResource(R.string.bsn_account)) }, enabled = !busy)
        Button(enabled = !busy && holder != null && account.isNotBlank(), onClick = {
            action { val h = controller.holder(owner); offer = controller.client.offer(account, h.subject, h.sign) }
        }) { Text(stringResource(R.string.bsn_request)) }
        offer?.let { record ->
            Text(record.text("displayName") + " @" + BsnNamingClient.NAMESPACE)
            Text(stringResource(R.string.bsn_publish_notice))
            Button(enabled = !busy, onClick = {
                action { val h = controller.holder(owner); current = controller.client.accept(record, h.subject, h.sign); offer = null }
            }) { Text(stringResource(R.string.bsn_accept)) }
        }
        TextButton(enabled = !busy && holder != null, onClick = {
            action { current = controller.client.resolve(controller.holder(owner).subject) }
        }) { Text(stringResource(R.string.bsn_refresh)) }
        current?.let { name ->
            Text(name.label)
            TextButton(enabled = !busy, onClick = {
                action { val h = controller.holder(owner); controller.client.disavow(name.record, h.subject, h.sign); current = null }
            }) { Text(stringResource(R.string.bsn_disavow)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Presentation-only aliases, scoped to this group's owner, roster, lifecycle and signed TTL. */
@Composable
fun rememberBsnProfiles(controller: NamingController?, group: ChatGroup?): Map<String, MemberProfile> {
    val original = group?.memberProfiles.orEmpty()
    if (controller == null || group == null) return original
    val enabled by controller.enabled.collectAsStateWithLifecycle()
    val consent = group.ownerIdentityId in enabled
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val subjects = original.mapValues { "onym:key:" + BsnNamingClient.hex(it.value.sendingPubkey) }
    var names by remember(group.id, group.ownerIdentityId, consent, subjects) { mutableStateOf<Map<String, BsnNamingClient.Name>>(emptyMap()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(controller, group.id, group.ownerIdentityId, consent, subjects, lifecycle) {
        if (!consent) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { while (true) { now = System.currentTimeMillis(); delay(1000) } }
            launch {
                while (true) {
                    // Bound fan-out. No background address-book enumeration or name persistence.
                    for ((member, subject) in subjects.entries.take(20)) {
                        try {
                            val name = controller.client.resolve(subject)
                            names = if (name == null) names - member else names + (member to name)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { names = names - member }
                    }
                    delay(30000)
                }
            }
        }
    }
    return original.mapValues { (member, profile) ->
        val name = names[member]
        if (consent && name != null && name.validUntil > maxOf(now, System.currentTimeMillis())) profile.copy(alias = name.label) else profile
    }
}
