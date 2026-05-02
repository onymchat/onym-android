package chat.onym.android.group.creategroup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.onym.android.group.CreateGroupProgress
import chat.onym.android.group.CreateGroupRoute
import chat.onym.android.group.CreateGroupViewModel
import chat.onym.android.group.OnymAccent
import chat.onym.android.group.OnymGovIcon
import chat.onym.android.group.OnymGroupAvatar
import chat.onym.android.group.OnymTokens
import chat.onym.android.group.OnymUIGovernance

/**
 * Top-level screen for the Create Group flow. Switches between the
 * five sub-routes ([CreateGroupRoute]) based on the ViewModel's
 * state. The whole flow is hosted as a fullscreen Dialog from
 * Settings — see `SettingsScreen.tappedCreateGroup`.
 *
 * Pixel-port of `CreateGroupView.swift` from onym-ios PR #26 with
 * Compose-idiomatic adaptations (sticky-bottom CTAs via the per-
 * screen `Column` + `Spacer(weight = 1)` pattern; SF Symbols
 * replaced with Compose primitives where the meaning carries
 * without the icon).
 */
@Composable
fun CreateGroupScreen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OnymTokens.Bg),
    ) {
        AnimatedContent(
            targetState = state.route,
            transitionSpec = { fadeIn(initialAlpha = 0f) togetherWith fadeOut(targetAlpha = 0f) },
            label = "create_group_route",
        ) { route ->
            when (route) {
                CreateGroupRoute.Step1 -> Step1Screen(viewModel)
                CreateGroupRoute.Step2 -> Step2Screen(viewModel)
                CreateGroupRoute.InviteByKey -> InviteByKeyScreen(viewModel)
                CreateGroupRoute.Creating -> CreatingScreen(viewModel)
                CreateGroupRoute.Success -> SuccessScreen(viewModel)
            }
        }
    }
}

// ─── Step 1 — name + accent + governance ────────────────────────

@Composable
private fun Step1Screen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color
    Column(modifier = Modifier.fillMaxSize()) {
        OnymNavTitle(title = "New Group", subtitle = "Step 1 of 2")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            OnymGroupAvatar(size = 92.dp, accent = accent)
            Spacer(Modifier.height(18.dp))

            // Name field
            OnymCard {
                BasicTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    textStyle = TextStyle(
                        color = OnymTokens.Text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(accent),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (state.name.isEmpty()) {
                                Text(
                                    text = "Group name",
                                    color = OnymTokens.Text3,
                                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "Visible to members. You can change this anytime.",
                color = OnymTokens.Text3,
                style = TextStyle(fontSize = 11.5.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 6.dp),
            )

            OnymSectionLabel("Accent color")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (a in OnymAccent.entries) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(a.color)
                            .then(
                                if (state.accent == a) {
                                    Modifier.border(2.dp, a.color, CircleShape)
                                } else Modifier,
                            )
                            .clickable { viewModel.setAccent(a) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.accent == a) {
                            Text(
                                text = "✓",
                                color = Color.White,
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }

            OnymSectionLabel("How it’s run")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (g in OnymUIGovernance.entries) {
                    GovernanceCard(
                        type = g,
                        accent = accent,
                        selected = state.governance == g && g.isAvailable,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setGovernance(g) },
                    )
                }
            }

            // Selected explanation
            Spacer(Modifier.height(12.dp))
            OnymCard(
                fill = OnymTokens.Surface2,
                borderColor = accent.copy(alpha = 0.22f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(accent.copy(alpha = 0.08f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 6.dp, height = 36.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent.copy(alpha = 0.85f)),
                    )
                    Column {
                        Text(
                            text = "${state.governance.sub}. ${state.governance.tooltip}",
                            color = OnymTokens.Text2,
                            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            // Encrypted footer card
            OnymCard(fill = OnymTokens.Surface) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "🔒",
                        color = OnymTokens.Text2,
                        style = TextStyle(fontSize = 14.sp),
                    )
                    Text(
                        text = "End-to-end encrypted · published on Stellar so anyone can verify it’s real.",
                        color = OnymTokens.Text2,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Sticky footer
        FlowFooter {
            OnymPrimaryButton(
                title = if (state.canAdvanceToStep2) "Next · Add people" else "Name your group to continue",
                accent = accent,
                enabled = state.canAdvanceToStep2,
                onClick = viewModel::tappedNext,
            )
            Spacer(Modifier.height(4.dp))
            OnymQuietButton(title = "Cancel", onClick = viewModel.onClose)
        }
    }
}

@Composable
private fun GovernanceCard(
    type: OnymUIGovernance,
    accent: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val available = type.isAvailable
    val labelColor = when {
        selected -> accent
        available -> OnymTokens.Text
        else -> OnymTokens.Text2
    }
    val borderColor = if (selected) accent else OnymTokens.Hairline
    val bgTint = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(OnymTokens.Surface2)
            .background(bgTint)
            .border(width = if (selected) 1.5.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(18.dp))
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            OnymGovIcon(
                type = type,
                accent = if (selected) accent else OnymTokens.Text,
                size = 42.dp,
                dimmed = !selected || !available,
            )
            if (!available) {
                Text(
                    text = "Soon",
                    color = OnymTokens.Text3,
                    style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(OnymTokens.Surface3)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = type.label,
            color = labelColor,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.13).sp),
        )
        Text(
            text = type.sub,
            color = OnymTokens.Text2,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        )
    }
}

// ─── Step 2 — review invitees + create ──────────────────────────

@Composable
private fun Step2Screen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color
    Column(modifier = Modifier.fillMaxSize()) {
        OnymNavTitle(title = "Add People", subtitle = "Step 2 of 2")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // Type banner
            OnymCard(
                radius = 12,
                fill = OnymTokens.Surface2,
                borderColor = accent.copy(alpha = 0.20f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(accent.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OnymGovIcon(type = state.governance, accent = accent, size = 28.dp)
                    Text(
                        text = "${state.governance.label}. You'll be the only admin.",
                        color = OnymTokens.Text2,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(OnymTokens.Surface3)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "${state.invitees.size}",
                            color = OnymTokens.Text,
                            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            if (state.invitees.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = "No invitees yet",
                        color = OnymTokens.Text,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Use “Invite by inbox key” below to add someone.",
                        color = OnymTokens.Text2,
                        style = TextStyle(fontSize = 12.5.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                OnymCard {
                    Column {
                        state.invitees.forEachIndexed { index, invitee ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(OnymTokens.Hairline),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(OnymTokens.Surface3),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "🔑",
                                        color = accent,
                                        style = TextStyle(fontSize = 14.sp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Inbox ${invitee.displayLabel}",
                                        color = OnymTokens.Text,
                                        style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                                    )
                                    Text(
                                        text = "Direct inbox key",
                                        color = OnymTokens.Text2,
                                        style = TextStyle(fontSize = 12.sp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable { viewModel.removeInvitee(index) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "✕",
                                        color = OnymTokens.Text3,
                                        style = TextStyle(fontSize = 16.sp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        FlowFooter {
            // Invite-by-key entry row
            OnymCard(
                radius = 12,
                fill = OnymTokens.Surface2,
                borderColor = OnymTokens.HairlineStrong,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::tappedInviteByKey)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "🔑", color = accent, style = TextStyle(fontSize = 12.sp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Invite by inbox key",
                            color = OnymTokens.Text,
                            style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = "Paste a 64-char key",
                            color = OnymTokens.Text2,
                            style = TextStyle(fontSize = 11.5.sp),
                        )
                    }
                    Text(
                        text = "›",
                        color = OnymTokens.Text3,
                        style = TextStyle(fontSize = 16.sp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OnymPrimaryButton(
                title = state.createCtaLabel,
                accent = accent,
                onClick = viewModel::tappedCreate,
            )
            OnymQuietButton(title = "Back", onClick = viewModel::tappedBackFromStep2)
        }
    }
}

// ─── InviteByKey — paste 64-char inbox key ──────────────────────

@Composable
private fun InviteByKeyScreen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color
    Column(modifier = Modifier.fillMaxSize()) {
        OnymNavTitle(
            title = "Invite by Inbox Key",
            subtitle = "For ${if (state.name.isEmpty()) "group" else state.name}",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            OnymCard {
                BasicTextField(
                    value = state.inviteeInput,
                    onValueChange = viewModel::setInviteeInput,
                    textStyle = TextStyle(
                        color = OnymTokens.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(accent),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (state.inviteeInput.isEmpty()) {
                                Text(
                                    text = "Paste a 64-character hex key…",
                                    color = OnymTokens.Text3,
                                    style = TextStyle(fontSize = 14.sp),
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "(${state.inviteeInputCleanedLength}/64)",
                    color = if (state.inviteeInputIsValid) accent else OnymTokens.Text3,
                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                )
                if (state.inviteeError != null) {
                    Text(
                        text = state.inviteeError!!,
                        color = OnymTokens.Red,
                        style = TextStyle(fontSize = 11.5.sp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "The recipient generates this key on their own device. They can find it in Settings → Identity → Inbox Key.",
                color = OnymTokens.Text3,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
        }

        FlowFooter {
            OnymPrimaryButton(
                title = "Add invitee",
                accent = accent,
                enabled = state.inviteeInputIsValid,
                onClick = viewModel::tappedAddInvitee,
            )
            OnymQuietButton(title = "Cancel", onClick = viewModel::tappedCancelInviteByKey)
        }
    }
}

// ─── Creating — live progress steps ─────────────────────────────

@Composable
private fun CreatingScreen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Creating ${state.name.ifEmpty { "Group" }}",
            color = OnymTokens.Text,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(24.dp))
        OnymGroupAvatar(size = 92.dp, accent = accent, spinning = state.error == null, brand = true)
        Spacer(Modifier.height(22.dp))

        OnymCard {
            Column {
                val steps = creatingSteps(state.invitees.size)
                steps.forEachIndexed { index, step ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(OnymTokens.Hairline),
                        )
                    }
                    val status = stepStatus(index, steps.size, state.progress, state.error != null)
                    StepRow(label = step.first, sub = step.second, status = status, accent = accent)
                }
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            OnymCard(
                fill = OnymTokens.Red.copy(alpha = 0.10f),
                borderColor = OnymTokens.Red.copy(alpha = 0.30f),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.error?.message ?: "Couldn't create the group",
                        color = OnymTokens.Red,
                        style = TextStyle(fontSize = 13.sp),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(OnymTokens.Surface2)
                            .clickable(onClick = viewModel::tappedDismissError)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "Try again",
                            color = accent,
                            style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "This usually takes a few seconds. It’s safe to close this — we’ll finish in the background.",
                color = OnymTokens.Text3,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

private enum class StepStatus { Done, Active, Pending }

private fun stepStatus(
    index: Int,
    total: Int,
    progress: CreateGroupProgress?,
    hasError: Boolean,
): StepStatus {
    if (hasError) return StepStatus.Pending
    if (progress == null) return StepStatus.Done
    val activeIndex = when (progress) {
        is CreateGroupProgress.Validating -> 0
        is CreateGroupProgress.Proving -> 1
        is CreateGroupProgress.SendingInvitations -> 2
        is CreateGroupProgress.Anchoring -> total - 1
    }
    return when {
        index < activeIndex -> StepStatus.Done
        index == activeIndex -> StepStatus.Active
        else -> StepStatus.Pending
    }
}

private fun creatingSteps(inviteeCount: Int): List<Pair<String, String>> = buildList {
    add("Setting up encrypted group" to "Generating keys on your device")
    add("Setting up your admin keys" to "You’ll be the only admin")
    repeat(inviteeCount) { i ->
        add("Sending invitation #${i + 1}" to "Encrypted, end-to-end")
    }
    add("Anchoring on Stellar" to "So anyone can verify this group is real")
}

@Composable
private fun StepRow(label: String, sub: String, status: StepStatus, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when (status) {
                        StepStatus.Done -> OnymTokens.Green
                        StepStatus.Active -> accent.copy(alpha = 0.18f)
                        StepStatus.Pending -> Color.Transparent
                    },
                )
                .border(
                    width = if (status == StepStatus.Pending) 2.dp else 0.dp,
                    color = OnymTokens.HairlineStrong,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                StepStatus.Done -> Text(
                    text = "✓",
                    color = Color.Black,
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                )
                StepStatus.Active -> Text(
                    text = "•",
                    color = accent,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                )
                StepStatus.Pending -> Unit
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (status == StepStatus.Pending) OnymTokens.Text2 else OnymTokens.Text,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = sub,
                color = OnymTokens.Text2,
                style = TextStyle(fontSize = 12.sp),
            )
        }
    }
}

// ─── Success — hero + members + done ────────────────────────────

@Composable
private fun SuccessScreen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color
    val group = state.createdGroup
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        OnymGroupAvatar(size = 96.dp, accent = accent, brand = true)
        Spacer(Modifier.height(20.dp))
        Text(
            text = group?.name ?: state.name,
            color = OnymTokens.Text,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Anchored on Stellar testnet",
            color = OnymTokens.Text2,
            style = TextStyle(fontSize = 13.sp),
        )

        Spacer(Modifier.height(24.dp))
        OnymCard {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Members",
                    color = OnymTokens.Text3,
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.88.sp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${(group?.members?.size ?: 1)} member" + if ((group?.members?.size ?: 1) == 1) "" else "s",
                    color = OnymTokens.Text,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${state.invitees.size} invitation" +
                        (if (state.invitees.size == 1) "" else "s") + " sent",
                    color = OnymTokens.Text2,
                    style = TextStyle(fontSize = 12.5.sp),
                )
            }
        }

        Spacer(Modifier.weight(1f))
        FlowFooter {
            OnymPrimaryButton(
                title = "Done",
                accent = accent,
                onClick = viewModel::tappedDone,
            )
        }
    }
}

// ─── Sticky-bottom footer wrapper ───────────────────────────────

@Composable
private fun FlowFooter(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OnymTokens.Bg)
            .border(
                width = 1.dp,
                color = OnymTokens.Hairline,
                shape = RoundedCornerShape(0.dp),
            )
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 22.dp),
    ) { content() }
}

