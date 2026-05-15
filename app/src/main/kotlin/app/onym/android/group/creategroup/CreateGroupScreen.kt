package app.onym.android.group.creategroup

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.R
import app.onym.android.group.CreateCtaLabel
import app.onym.android.group.CreateGroupProgress
import app.onym.android.group.CreateGroupRoute
import app.onym.android.group.CreateGroupViewModel
import app.onym.android.group.OnymAccent
import app.onym.android.group.OnymGovIcon
import app.onym.android.group.OnymGroupAvatar
import app.onym.android.group.LocalOnymTokens
import app.onym.android.group.OnymUIGovernance

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
            .background(LocalOnymTokens.current.bg),
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
    val accent = state.accent.color()
    val focusManager = LocalFocusManager.current
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OnymNavTitle(
                title = stringResource(R.string.create_group_step1_title),
                subtitle = stringResource(R.string.create_group_step1_subtitle),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 8.dp)
                    .size(32.dp)
                    .clickable(onClick = viewModel.onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    color = LocalOnymTokens.current.text2,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
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

            // Name field — pre-filled with the generated placeholder
            // (e.g. "Maple Garden") so the user can hit Create
            // immediately. First focus clears the field via
            // `nameFieldFocused()`; the empty placeholder text below
            // shows the same generated name dimmed so the user
            // remembers what would be submitted.
            val namePlaceholder = stringResource(R.string.create_group_name_placeholder)
            OnymCard {
                BasicTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    textStyle = TextStyle(
                        color = LocalOnymTokens.current.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(accent),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (state.name.isEmpty()) {
                                Text(
                                    text = state.generatedName.ifEmpty { namePlaceholder },
                                    color = LocalOnymTokens.current.text3,
                                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) viewModel.nameFieldFocused()
                        },
                )
            }
            Text(
                text = stringResource(R.string.create_group_name_helper),
                color = LocalOnymTokens.current.text3,
                style = TextStyle(fontSize = 11.5.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 6.dp),
            )

            OnymSectionLabel(stringResource(R.string.create_group_accent_color))
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
                            .background(a.color())
                            .then(
                                if (state.accent == a) {
                                    Modifier.border(2.dp, a.color(), CircleShape)
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

            OnymSectionLabel(stringResource(R.string.create_group_how_its_run))
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
            Spacer(Modifier.height(16.dp))
        }

        // Sticky footer
        FlowFooter {
            OnymPrimaryButton(
                // The button is always enabled when the governance
                // type is wired (Tyranny only in PR-C); name has a
                // random placeholder default so there's no "fill in
                // the blank" gating step.
                title = stringResource(R.string.create_group_next),
                accent = accent,
                enabled = state.canAdvanceToStep2,
                onClick = viewModel::tappedNext,
            )
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
        available -> LocalOnymTokens.current.text
        else -> LocalOnymTokens.current.text2
    }
    val borderColor = if (selected) accent else LocalOnymTokens.current.hairline
    val bgTint = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(LocalOnymTokens.current.surface2)
            .background(bgTint)
            .border(width = if (selected) 1.5.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(18.dp))
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            OnymGovIcon(
                type = type,
                accent = if (selected) accent else LocalOnymTokens.current.text,
                size = 42.dp,
                dimmed = !selected || !available,
            )
            if (!available) {
                Text(
                    text = stringResource(R.string.create_group_soon_badge),
                    color = LocalOnymTokens.current.text3,
                    style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(LocalOnymTokens.current.surface3)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(type.cardLabelRes()),
            color = labelColor,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.13).sp),
        )
        Text(
            text = stringResource(type.subRes()),
            color = LocalOnymTokens.current.text2,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        )
    }
}

// ─── Step 2 — review invitees + create ──────────────────────────

@Composable
private fun Step2Screen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color()
    Column(modifier = Modifier.fillMaxSize()) {
        OnymNavTitle(
            title = stringResource(R.string.create_group_step2_title),
            subtitle = stringResource(R.string.create_group_step2_subtitle),
        )
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
                fill = LocalOnymTokens.current.surface2,
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
                        text = "${stringResource(state.governance.cardLabelRes())}. " +
                            stringResource(state.governance.step2HintRes()),
                        color = LocalOnymTokens.current.text2,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(LocalOnymTokens.current.surface3)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "${state.invitees.size}",
                            color = LocalOnymTokens.current.text,
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
                        text = stringResource(R.string.create_group_no_invitees_title),
                        color = LocalOnymTokens.current.text,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.create_group_no_invitees_body),
                        color = LocalOnymTokens.current.text2,
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
                                        .background(LocalOnymTokens.current.hairline),
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
                                        .background(LocalOnymTokens.current.surface3),
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
                                        text = stringResource(
                                            R.string.create_group_invitee_inbox_label,
                                            invitee.displayLabel,
                                        ),
                                        color = LocalOnymTokens.current.text,
                                        style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                                    )
                                    Text(
                                        text = stringResource(R.string.create_group_invitee_inbox_subtitle),
                                        color = LocalOnymTokens.current.text2,
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
                                        color = LocalOnymTokens.current.text3,
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
                fill = LocalOnymTokens.current.surface2,
                borderColor = LocalOnymTokens.current.hairlineStrong,
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
                            text = stringResource(R.string.create_group_invite_by_key_row_title),
                            color = LocalOnymTokens.current.text,
                            style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = stringResource(R.string.create_group_invite_by_key_row_subtitle),
                            color = LocalOnymTokens.current.text2,
                            style = TextStyle(fontSize = 11.5.sp),
                        )
                    }
                    Text(
                        text = "›",
                        color = LocalOnymTokens.current.text3,
                        style = TextStyle(fontSize = 16.sp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OnymPrimaryButton(
                title = state.createCtaLabel.resolved(),
                accent = accent,
                enabled = state.canSubmit,
                onClick = viewModel::tappedCreate,
            )
            OnymQuietButton(
                title = stringResource(R.string.back),
                onClick = viewModel::tappedBackFromStep2,
            )
        }
    }
}

// ─── InviteByKey — paste 64-char inbox key ──────────────────────

@Composable
private fun InviteByKeyScreen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color()
    Column(modifier = Modifier.fillMaxSize()) {
        val groupLabel = state.name.ifEmpty {
            stringResource(R.string.create_group_invite_by_key_default_group_label)
        }
        OnymNavTitle(
            title = stringResource(R.string.create_group_invite_by_key_screen_title),
            subtitle = stringResource(
                R.string.create_group_invite_by_key_for_subtitle,
                groupLabel,
            ),
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
                        color = LocalOnymTokens.current.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(accent),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (state.inviteeInput.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.create_group_invite_by_key_placeholder),
                                    color = LocalOnymTokens.current.text3,
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
                    text = stringResource(
                        R.string.create_group_invite_by_key_count,
                        state.inviteeInputCleanedLength,
                    ),
                    color = if (state.inviteeInputIsValid) accent else LocalOnymTokens.current.text3,
                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                )
                if (state.inviteeError != null) {
                    Text(
                        text = state.inviteeError!!,
                        color = LocalOnymTokens.current.red,
                        style = TextStyle(fontSize = 11.5.sp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.create_group_invite_by_key_helper),
                color = LocalOnymTokens.current.text3,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
        }

        FlowFooter {
            OnymPrimaryButton(
                title = stringResource(R.string.create_group_invite_by_key_add),
                accent = accent,
                enabled = state.inviteeInputIsValid,
                onClick = viewModel::tappedAddInvitee,
            )
            OnymQuietButton(
                title = stringResource(R.string.cancel),
                onClick = viewModel::tappedCancelInviteByKey,
            )
        }
    }
}

// ─── Creating — live progress steps ─────────────────────────────

@Composable
private fun CreatingScreen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        val creatingName = state.name.ifEmpty {
            stringResource(R.string.create_group_creating_default_name)
        }
        Text(
            text = stringResource(R.string.create_group_creating_title, creatingName),
            color = LocalOnymTokens.current.text,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(24.dp))
        OnymGroupAvatar(size = 92.dp, accent = accent, spinning = state.error == null, brand = true)
        Spacer(Modifier.height(22.dp))

        OnymCard {
            Column {
                val steps = creatingStepsLabels(state.invitees.size)
                steps.forEachIndexed { index, step ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(LocalOnymTokens.current.hairline),
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
                fill = LocalOnymTokens.current.red.copy(alpha = 0.10f),
                borderColor = LocalOnymTokens.current.red.copy(alpha = 0.30f),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Soroban diagnostic chains can be 500+ chars
                    // (proof-verify failures spell out the contract
                    // IDs + error codes + bytes payloads); cap the
                    // banner height so the Cancel/Try again row stays
                    // visible. Monospaced + left-aligned for hex
                    // readability; SelectionContainer lets the user
                    // long-press to copy into a bug report.
                    // Mirrors onym-ios PR #29.
                    val fallbackError = stringResource(R.string.create_group_error_fallback)
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = state.error?.message ?: fallbackError,
                            color = LocalOnymTokens.current.red,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Cancel — closes the whole flow. Mirrors the
                        // PR-C-followup error-banner change in iOS PR #27.
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(LocalOnymTokens.current.surface3)
                                .clickable(onClick = viewModel::cancelFromError)
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.cancel),
                                color = LocalOnymTokens.current.text2,
                                style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(LocalOnymTokens.current.surface2)
                                .clickable(onClick = viewModel::tappedDismissError)
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.try_again),
                                color = accent,
                                style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.create_group_helpful_note),
                color = LocalOnymTokens.current.text3,
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

@Composable
private fun creatingStepsLabels(inviteeCount: Int): List<Pair<String, String>> {
    val setup = stringResource(R.string.create_group_step_setup) to
        stringResource(R.string.create_group_step_setup_sub)
    val admin = stringResource(R.string.create_group_step_admin) to
        stringResource(R.string.create_group_step_admin_sub)
    val invitationSub = stringResource(R.string.create_group_step_invitation_sub)
    val invitations = (1..inviteeCount).map { n ->
        stringResource(R.string.create_group_step_invitation, n) to invitationSub
    }
    val anchor = stringResource(R.string.create_group_step_anchor) to
        stringResource(R.string.create_group_step_anchor_sub)
    return buildList {
        add(setup)
        add(admin)
        addAll(invitations)
        add(anchor)
    }
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
                        StepStatus.Done -> LocalOnymTokens.current.green
                        StepStatus.Active -> accent.copy(alpha = 0.18f)
                        StepStatus.Pending -> Color.Transparent
                    },
                )
                .border(
                    width = if (status == StepStatus.Pending) 2.dp else 0.dp,
                    color = LocalOnymTokens.current.hairlineStrong,
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
                color = if (status == StepStatus.Pending) LocalOnymTokens.current.text2 else LocalOnymTokens.current.text,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = sub,
                color = LocalOnymTokens.current.text2,
                style = TextStyle(fontSize = 12.sp),
            )
        }
    }
}

// ─── Success — hero + members + done ────────────────────────────

@Composable
private fun SuccessScreen(viewModel: CreateGroupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = state.accent.color()
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
            color = LocalOnymTokens.current.text,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.create_group_anchored_testnet),
            color = LocalOnymTokens.current.text2,
            style = TextStyle(fontSize = 13.sp),
        )

        Spacer(Modifier.height(24.dp))
        OnymCard {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.create_group_members_section),
                    color = LocalOnymTokens.current.text3,
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.88.sp),
                )
                Spacer(Modifier.height(8.dp))
                val memberCount = group?.members?.size ?: 1
                Text(
                    text = pluralStringResource(
                        R.plurals.create_group_members_count,
                        memberCount,
                        memberCount,
                    ),
                    color = LocalOnymTokens.current.text,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.create_group_invitations_sent,
                        state.invitees.size,
                        state.invitees.size,
                    ),
                    color = LocalOnymTokens.current.text2,
                    style = TextStyle(fontSize = 12.5.sp),
                )
            }
        }

        Spacer(Modifier.weight(1f))
        FlowFooter {
            OnymPrimaryButton(
                title = stringResource(R.string.share_invite_link_chooser),
                accent = accent,
                onClick = viewModel::tappedShareInvite,
            )
            Spacer(Modifier.height(6.dp))
            OnymQuietButton(
                title = stringResource(R.string.done),
                onClick = viewModel::tappedDone,
            )
        }
    }
}

// ─── Localized resource lookups for governance + CTA enums ──────

@androidx.annotation.StringRes
private fun OnymUIGovernance.cardLabelRes(): Int = when (this) {
    OnymUIGovernance.Tyranny -> R.string.governance_tyranny_card_label
    OnymUIGovernance.OneOnOne -> R.string.governance_oneonone_card_label
    OnymUIGovernance.Anarchy -> R.string.governance_anarchy_card_label
}

@androidx.annotation.StringRes
private fun OnymUIGovernance.subRes(): Int = when (this) {
    OnymUIGovernance.Tyranny -> R.string.governance_tyranny_sub
    OnymUIGovernance.OneOnOne -> R.string.governance_oneonone_sub
    OnymUIGovernance.Anarchy -> R.string.governance_anarchy_sub
}

@androidx.annotation.StringRes
private fun OnymUIGovernance.step2HintRes(): Int = when (this) {
    OnymUIGovernance.Tyranny -> R.string.governance_tyranny_step2hint
    OnymUIGovernance.OneOnOne -> R.string.governance_oneonone_step2hint
    OnymUIGovernance.Anarchy -> R.string.governance_anarchy_step2hint
}

@Composable
private fun CreateCtaLabel.resolved(): String = when (this) {
    CreateCtaLabel.Empty -> stringResource(R.string.create_group_cta_empty)
    CreateCtaLabel.OnePerson -> stringResource(R.string.create_group_cta_one_person)
    is CreateCtaLabel.NPeople -> stringResource(R.string.create_group_cta_n_people, count)
    CreateCtaLabel.OneOnOneAddOther -> stringResource(R.string.create_group_cta_oneonone_add)
    CreateCtaLabel.OneOnOneStart -> stringResource(R.string.create_group_cta_oneonone_start)
    CreateCtaLabel.OneOnOneTooMany -> stringResource(R.string.create_group_cta_oneonone_too_many)
}

// ─── Sticky-bottom footer wrapper ───────────────────────────────

@Composable
private fun FlowFooter(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .background(LocalOnymTokens.current.bg)
            .border(
                width = 1.dp,
                color = LocalOnymTokens.current.hairline,
                shape = RoundedCornerShape(0.dp),
            )
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 22.dp),
    ) { content() }
}

