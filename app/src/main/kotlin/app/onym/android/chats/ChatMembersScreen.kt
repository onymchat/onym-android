package app.onym.android.chats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.chain.SepGroupType
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupAvatarImage
import app.onym.android.group.MemberProfile
import app.onym.android.identity.IdentitiesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Roster screen drilled into from a chat row. Renders one row per
 * entry in [ChatGroup.memberProfiles], sorted by alias, with the
 * active identity badged "(you)".
 *
 * Reads the latest [ChatGroup] by ID from [chatsViewModel.groups] so
 * the view re-renders when an admin's PR-79 fanout lands a new entry
 * via the PR-80 dispatcher.
 *
 * Mirrors `ChatMembersView.swift` from onym-ios.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMembersScreen(
    groupId: String,
    chatsViewModel: ChatsViewModel,
    identityViewModel: IdentitiesViewModel,
    onBack: () -> Unit,
    onShareInviteClick: (() -> Unit)? = null,
) {
    val groups by chatsViewModel.groups.collectAsStateWithLifecycle()
    val identityRows by identityViewModel.items.collectAsStateWithLifecycle()
    val activeBlsHex: String? = identityRows.firstOrNull { it.isActive }
        ?.summary
        ?.blsPublicKey
        ?.toHexLowercase()

    val group = groups.firstOrNull { it.id == groupId }

    // PR 94: only the cryptographic admin of a Tyranny group should
    // see the Share Invite button. A non-admin's minted invite would
    // surface join requests in their inbox but never approve on chain
    // (sep-tyranny gates `update_commitment` on the admin's BLS
    // secret) — dead-end UX.
    //
    // Anarchy / OneOnOne never show it (admit ceremonies aren't wired
    // in V1; OneOnOne is a fixed 2-party group).
    //
    // The check uses the BLS pubkey, NOT ownerIdentityId. The latter
    // is per-device — on a joiner-side group it points at the joiner,
    // so it would falsely report "you own this" everywhere.
    val canShareInvite = remember(group, identityRows, activeBlsHex) {
        val g = group ?: return@remember false
        if (g.groupType != SepGroupType.TYRANNY) return@remember false
        val storedAdminHex = g.adminPubkeyHex?.lowercase() ?: return@remember false
        activeBlsHex != null && activeBlsHex.lowercase() == storedAdminHex
    }
    val showShareInvite = onShareInviteClick != null && canShareInvite

    // Only the cryptographic admin may change the group photo — same
    // gate as Share Invite (the receive-side trust check rejects a
    // non-admin's avatar message anyway, so a non-admin's edit would be
    // a dead-end). Non-admins get the read-only display below.
    val canEditAvatar = canShareInvite

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val g = group ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                decodeAndEncodeAvatar(context, uri)
            } ?: return@launch
            chatsViewModel.setGroupAvatar(g.id, bytes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "Group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // PR 86 wires the share-invite toolbar; PR 94
                    // hides it for non-admins.
                    if (showShareInvite) {
                        IconButton(
                            onClick = onShareInviteClick!!,
                            modifier = Modifier.testTag("members.share_invite_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "Share invite",
                            )
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (group == null) {
            MissingGroupState(modifier = Modifier.padding(padding).fillMaxSize())
        } else {
            ChatMembersBody(
                group = group,
                activeBlsHex = activeBlsHex,
                canEditAvatar = canEditAvatar,
                onPickPhoto = {
                    photoPicker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onRemovePhoto = { chatsViewModel.setGroupAvatar(group.id, null) },
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ChatMembersBody(
    group: ChatGroup,
    activeBlsHex: String?,
    canEditAvatar: Boolean,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(group.memberProfiles, activeBlsHex) {
        group.memberProfiles.map { (key, profile) ->
            MemberRow(
                blsHex = key,
                blsPrefix = key.take(12),
                displayAlias = profile.alias.ifEmpty { "(unnamed)" },
                isSelf = activeBlsHex != null &&
                    key.equals(activeBlsHex, ignoreCase = true),
            )
        }.sortedWith(compareBy({ !it.isSelf }, { it.displayAlias.lowercase() }))
    }

    Column(modifier = modifier) {
        GroupAvatarHeader(
            group = group,
            canEdit = canEditAvatar,
            onPickPhoto = onPickPhoto,
            onRemovePhoto = onRemovePhoto,
        )
        if (rows.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize())
        } else {
            MembersCard(rows = rows)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (rows.size == 1) "${rows.size} member" else "${rows.size} members",
                modifier = Modifier.padding(horizontal = 24.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MembersCard(rows: List<MemberRow>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        rows.forEachIndexed { idx, row ->
            MemberRowView(row = row)
            if (idx != rows.lastIndex) {
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun MemberRowView(row: MemberRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("members.row.${row.blsHex}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(letter = row.displayAlias.firstOrNull()?.uppercase().orEmpty(), self = row.isSelf)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.displayAlias,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                if (row.isSelf) {
                    Spacer(Modifier.size(6.dp))
                    SelfPill()
                }
            }
            Text(
                text = "BLS ${row.blsPrefix}…",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AvatarCircle(letter: String, self: Boolean) {
    val accent = if (self) Color(0xFF34C759) else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (self) 0.25f else 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (letter.isNotEmpty()) {
            Text(
                text = letter,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        } else {
            Icon(
                Icons.Filled.PersonOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = accent,
            )
        }
    }
}

@Composable
private fun SelfPill() {
    Text(
        text = "(you)",
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("members.empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.PersonOutline,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text("No members yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Invite people from the chat to see them here.",
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun MissingGroupState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("members.missing"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Group not found", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "It may have been removed or it belongs to a different identity.",
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * Group-photo header. Renders the avatar (the stored JPEG, or a
 * monogram placeholder) above the roster. Admins ([canEdit]) get a
 * "Change photo" affordance — tapping the avatar or the button opens
 * the system photo picker — plus "Remove" when a photo is set.
 * Non-admins see the same image, read-only.
 */
@Composable
private fun GroupAvatarHeader(
    group: ChatGroup,
    canEdit: Boolean,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    val avatarBitmap = remember(group.avatar) {
        group.avatar?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
            .testTag("members.avatar_header"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val circle = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (canEdit) Modifier.clickable(onClick = onPickPhoto) else Modifier)
            .testTag("members.avatar_image")
        Box(modifier = circle, contentAlignment = Alignment.Center) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = "Group photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val letter = group.name.firstOrNull()?.uppercase().orEmpty()
                if (letter.isNotEmpty()) {
                    Text(
                        text = letter,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.PersonOutline,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (canEdit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onPickPhoto,
                    modifier = Modifier.testTag("members.avatar_change"),
                ) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (group.avatar == null) "Add photo" else "Change photo")
                }
                if (group.avatar != null) {
                    TextButton(
                        onClick = onRemovePhoto,
                        modifier = Modifier.testTag("members.avatar_remove"),
                    ) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

internal data class MemberRow(
    val blsHex: String,
    val blsPrefix: String,
    val displayAlias: String,
    val isSelf: Boolean,
)

private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
    for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
}

/**
 * Decode the picked image [uri] into a downsampled [Bitmap] and run it
 * through [GroupAvatarImage.encode] to get budget-bounded JPEG bytes.
 * Returns `null` on any decode failure. Call off the main thread.
 */
private fun decodeAndEncodeAvatar(
    context: android.content.Context,
    uri: android.net.Uri,
): ByteArray? {
    val resolver = context.contentResolver
    // Pass 1: bounds only, so a huge source doesn't OOM on decode.
    // `decodeStream` returns null in this mode by design (it just fills
    // outWidth/outHeight), so the null guard MUST be on `openInputStream`
    // — not on the decode result, or we'd bail out on every image.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    val minEdge = minOf(bounds.outWidth, bounds.outHeight)
    if (minEdge <= 0) return null

    // Downsample so the smaller edge is at least 2× the target — enough
    // detail for the centre-crop + 256² scale without decoding full res.
    var sample = 1
    while (minEdge / (sample * 2) >= GroupAvatarImage.SIZE * 2) {
        sample *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, decodeOpts)
    } ?: return null

    return runCatching { GroupAvatarImage.encode(bitmap) }.getOrNull()
}
