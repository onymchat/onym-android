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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.chain.SepGroupType
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupAvatarImage
import app.onym.android.group.GroupRulesProof
import app.onym.android.group.GroupRulesStanding
import app.onym.android.group.MemberProfile
import app.onym.android.group.rulesStanding
import app.onym.android.identity.IdentitiesViewModel
import app.onym.android.strings.R
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
    val chatItems by chatsViewModel.items.collectAsStateWithLifecycle()
    val identityRows by identityViewModel.items.collectAsStateWithLifecycle()
    val activeBlsHex: String? = identityRows.firstOrNull { it.isActive }
        ?.summary
        ?.blsPublicKey
        ?.toHexLowercase()

    val group = chatItems.firstOrNull { it.group.id == groupId }?.group

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
        g.isAdmin(activeBlsHex)
    }
    val showShareInvite = onShareInviteClick != null && canShareInvite

    // Only the cryptographic admin may change the group photo — same
    // gate as Share Invite (the receive-side trust check rejects a
    // non-admin's avatar message anyway, so a non-admin's edit would be
    // a dead-end). Non-admins get the read-only display below.
    val canEditAvatar = canShareInvite

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Admin-only rename dialog state.
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val g = group ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                GroupAvatarImage.decodeFromUri(context, uri)
            } ?: return@launch
            chatsViewModel.setGroupAvatar(g.id, bytes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: stringResource(R.string.group_fallback_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    // Admin-only rename (same gate as the avatar edit).
                    if (canEditAvatar && group != null) {
                        IconButton(
                            onClick = {
                                renameText = group.name
                                showRename = true
                            },
                            modifier = Modifier.testTag("members.rename_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DriveFileRenameOutline,
                                contentDescription = stringResource(R.string.group_rename_cd),
                            )
                        }
                    }
                    // PR 86 wires the share-invite toolbar; PR 94
                    // hides it for non-admins.
                    if (showShareInvite) {
                        IconButton(
                            onClick = onShareInviteClick!!,
                            modifier = Modifier.testTag("members.share_invite_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = stringResource(R.string.share_invite_cd),
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

    if (showRename && group != null) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.group_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.group_rename_placeholder)) },
                    supportingText = { Text(stringResource(R.string.group_rename_message)) },
                    modifier = Modifier.testTag("members.rename_field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        chatsViewModel.setGroupName(group.id, renameText)
                        showRename = false
                    },
                    enabled = renameText.isNotBlank(),
                    modifier = Modifier.testTag("members.rename_save"),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** The group's invitation message (greeting / policy / articles),
 *  shown as the group's intro at the top of the info screen. */
@Composable
private fun InvitationSection(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.members_section_rules),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp)
                .testTag("members.invitation"),
        )
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
    // Keyed on everything a standing reads — the group id included,
    // since the signature is verified against it — and nothing else.
    // Each one
    // is an Ed25519 verify plus a SHA-256, so re-deriving them on every
    // recomposition would put a hundred-member roster's worth of
    // verification on the frame — and the group's photo is not an input
    // worth comparing to find that out.
    val rows = remember(
        group.id,
        group.memberProfiles,
        group.invitationMessage,
        group.adminPubkeyHex,
        group.groupType,
        activeBlsHex,
    ) {
        group.memberProfiles.map { (key, profile) ->
            // The overload that takes the profile already in hand: the
            // key-only one is nullable, and either swallowing that with
            // a fallback (mislabelling a member) or dropping the row
            // (losing one from the card and the count) would be a lie
            // about a case that cannot happen here.
            val standing = group.rulesStanding(profile, key)
            MemberRow(
                blsHex = key,
                blsPrefix = key.take(12),
                displayAlias = profile.alias.ifEmpty { "(unnamed)" },
                isSelf = activeBlsHex != null &&
                    key.equals(activeBlsHex, ignoreCase = true),
                standing = standing,
            )
        }.sortedWith(
            // Self first, then alias, then the fingerprint. The last is
            // not decoration: aliases are self-asserted and non-unique,
            // and rows are tap targets now — two members sharing a name
            // could otherwise swap places under a thumb already moving
            // toward a row that then opens somebody else's agreement.
            compareBy({ !it.isSelf }, { it.displayAlias.lowercase() }, { it.blsHex }),
        )
    }
    var proofFor by remember { mutableStateOf<String?>(null) }

    // Swept from here as well as from the proof sheet: someone who
    // opens one sheet and never another would otherwise leave that
    // member's rules and signature in the cache until the OS evicted
    // it. Any later visit to any group's member list clears it.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { sweepStaleRulesProofExports(context) }
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        GroupAvatarHeader(
            group = group,
            canEdit = canEditAvatar,
            onPickPhoto = onPickPhoto,
            onRemovePhoto = onRemovePhoto,
        )
        group.invitationMessage?.takeIf { it.isNotBlank() }?.let { message ->
            InvitationSection(message)
        }
        if (rows.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize())
        } else {
            MembersCard(rows = rows, onOpenProof = { proofFor = it })
            Spacer(Modifier.height(8.dp))
            Text(
                text = pluralStringResource(R.plurals.members_count, rows.size, rows.size),
                modifier = Modifier.padding(horizontal = 24.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Resolved from the live group at present time rather than from a
    // captured row: a roster update while the sheet is open would
    // otherwise leave a proof rendered beside a group it no longer
    // describes. A member who vanished underneath closes it rather than
    // showing an empty sheet.
    proofFor?.let { blsHex ->
        // Remembered on the same inputs the standings are, and *not*
        // rebuilt per recomposition. `GroupRulesProof` has no `equals`,
        // so a fresh instance on every group emission — an avatar, a
        // name, a roster change — restarted the sheet's write effect:
        // the Export button blinked out from under a thumb, another
        // directory was minted, and an Ed25519 verify ran per frame,
        // which is the cost the roster memo above exists to avoid.
        val proof = remember(
            group.memberProfiles,
            group.invitationMessage,
            group.adminPubkeyHex,
            group.groupType,
            group.id,
            // The name too: the proof renders it and files under it, so
            // a rename while the sheet is open would otherwise leave the
            // written export naming the group as it used to be.
            group.name,
            blsHex,
        ) {
            GroupRulesProof.of(group, blsHex)
        }
        if (proof != null) {
            MemberRulesProofSheet(proof = proof, onDismiss = { proofFor = null })
        } else {
            // Cleared in an effect, not during composition: writing the
            // state this block reads is a backwards write, and it
            // invalidates the scope that just read it.
            LaunchedEffect(blsHex) { proofFor = null }
        }
    }
}

@Composable
private fun MembersCard(rows: List<MemberRow>, onOpenProof: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        rows.forEachIndexed { idx, row ->
            MemberRowView(row = row, onOpenProof = onOpenProof)
            if (idx != rows.lastIndex) {
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun MemberRowView(row: MemberRow, onOpenProof: (String) -> Unit) {
    val mark = groupRulesMark(row.standing)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Only where there is something to hand someone. A row that
            // opens a sheet saying "nothing to show" is an invitation to
            // a dead end, and gating on the mark rather than on one
            // standing keeps the two answers from drifting apart.
            .then(
                if (mark != null) {
                    // `Role.Button`, so TalkBack says the row opens
                    // something. A chevron below says the same to
                    // everyone else — before this the rows were tap
                    // targets with no affordance at all.
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.rules_proof_open_cd),
                    ) { onOpenProof(row.blsHex) }
                } else {
                    Modifier
                },
            )
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
            // The fingerprint stays. It is the load-bearing identifier
            // — aliases are self-asserted, so two members calling
            // themselves the same thing are told apart by this and
            // nothing else — and the standing is a second line rather
            // than a replacement for it.
            Text(
                text = "BLS ${row.blsPrefix}…",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mark != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.testTag("members.standing.${row.blsHex}"),
                ) {
                    Icon(
                        imageVector = mark.icon,
                        contentDescription = null,
                        tint = mark.color,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(text = mark.text, fontSize = 12.sp, color = mark.color)
                }
            }
        }
        if (mark != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
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
        text = stringResource(R.string.members_you_suffix),
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
        Text(stringResource(R.string.members_empty_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.members_empty_body),
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
        Text(stringResource(R.string.group_not_found_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.group_not_found_body),
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
                    contentDescription = stringResource(R.string.group_photo_cd),
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
                        Text(stringResource(R.string.create_group_photo_remove), color = MaterialTheme.colorScheme.error)
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
    /** Derived from the stored signature rather than read from a flag,
     *  once per group snapshot. */
    val standing: GroupRulesStanding,
)

private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
    for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
}
