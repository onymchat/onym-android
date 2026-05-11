package chat.onym.android.recovery

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.onym.android.R
import java.text.DateFormat
import java.util.Date

/**
 * Top-level Compose root for the Back-up-keys flow. Stateless w.r.t.
 * domain data — collects [RecoveryPhraseBackupViewModel.step] and
 * renders the matching sub-composable, wires button taps to intent
 * methods.
 *
 * Mirrors `onym-ios/Sources/OnymIOS/Recovery/RecoveryPhraseBackupView.swift`.
 * Layout numbers (paddings, corner radii, font sizes) translated 1:1 from
 * the SwiftUI source; the visual design was tuned in the reference impl
 * and is the user-facing contract.
 *
 * Screenshot/recents protection is set on the Activity window via
 * `WindowManager.LayoutParams.FLAG_SECURE` in `MainActivity` — equivalent
 * to (and stronger than) iOS's scene-phase obscure overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryPhraseBackupScreen(
    viewModel: RecoveryPhraseBackupViewModel,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val isReady by viewModel.isReady.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.start()
    }

    val title = when (step) {
        is RecoveryPhraseBackupViewModel.Step.Intro,
        is RecoveryPhraseBackupViewModel.Step.AuthFailed -> stringResource(R.string.back_up_keys)
        is RecoveryPhraseBackupViewModel.Step.Reveal    -> stringResource(R.string.recovery_phrase_title)
        is RecoveryPhraseBackupViewModel.Step.Verify    -> stringResource(R.string.verify)
        is RecoveryPhraseBackupViewModel.Step.Done      -> ""
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            when (val s = step) {
                is RecoveryPhraseBackupViewModel.Step.Intro,
                is RecoveryPhraseBackupViewModel.Step.AuthFailed -> {
                    IntroScreen(
                        isReady = isReady,
                        onContinue = viewModel::tappedContinueFromIntro,
                    )
                }
                is RecoveryPhraseBackupViewModel.Step.Reveal -> {
                    RevealScreen(
                        phrase = s.phrase,
                        onCopy = viewModel::tappedCopyPhrase,
                        onContinue = viewModel::tappedContinueFromReveal,
                    )
                }
                is RecoveryPhraseBackupViewModel.Step.Verify -> {
                    VerifyScreen(
                        round = s.rounds[s.index],
                        progressIndex = s.index,
                        progressTotal = s.rounds.size,
                        state = s.state,
                        onPick = viewModel::picked,
                    )
                }
                is RecoveryPhraseBackupViewModel.Step.Done -> {
                    DoneScreen(onDone = viewModel::tappedDoneFromCompletion)
                }
            }
        }
    }

    val authFailed = step as? RecoveryPhraseBackupViewModel.Step.AuthFailed
    if (authFailed != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissedAuthError,
            title = { Text(stringResource(R.string.authentication_failed)) },
            text = { Text(authFailed.reason) },
            confirmButton = {
                TextButton(onClick = viewModel::tappedContinueFromIntro) {
                    Text(stringResource(R.string.try_again))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissedAuthError) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// ─── Intro ───────────────────────────────────────────────────────────

@Composable
private fun IntroScreen(isReady: Boolean, onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        HeroCard(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 18.dp),
        )

        SectionHeader(stringResource(R.string.before_you_start))
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            IntroRow(
                icon = Icons.Filled.Warning,
                iconBg = Color(0xFFFF9800),
                title = stringResource(R.string.rule_never_share),
                separator = true,
            )
            IntroRow(
                icon = Icons.Filled.Shield,
                iconBg = Color(0xFF4CAF50),
                title = stringResource(R.string.rule_store_offline),
                separator = true,
            )
            IntroRow(
                icon = Icons.Filled.Lock,
                iconBg = Color(0xFF8E8E93),
                title = stringResource(R.string.rule_anyone_can_read),
                separator = false,
            )
        }

        Button(
            onClick = onContinue,
            enabled = isReady,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 22.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.continue_with_biometrics),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HeroCard(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 22.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(listOf(accent, Color(0xFFAB47BC)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            stringResource(R.string.your_identity_in_12_words),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.recovery_phrase_intro_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .padding(bottom = 6.dp),
    )
}

@Composable
private fun IntroRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    title: String,
    separator: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
    if (separator) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 58.dp)
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

// ─── Reveal ──────────────────────────────────────────────────────────

@Composable
private fun RevealScreen(
    phrase: String,
    onCopy: () -> Unit,
    onContinue: () -> Unit,
) {
    var copyConfirmShown by remember { mutableStateOf(false) }
    val words = remember(phrase) { phrase.split(" ").filter { it.isNotEmpty() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        Text(
            pluralStringResource(
                R.plurals.write_down_words_in_order,
                count = words.size,
                words.size,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(top = 12.dp, bottom = 8.dp),
        )

        PhraseCard(
            words = words,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        )

        // Copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onCopy()
                    copyConfirmShown = true
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.copy), fontWeight = FontWeight.SemiBold)
            }
        }

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Text(stringResource(R.string.ive_written_it_down), fontWeight = FontWeight.SemiBold)
        }

        Text(
            stringResource(R.string.phrase_local_disclaimer),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(top = 18.dp),
        )
    }

    if (copyConfirmShown) {
        AlertDialog(
            onDismissRequest = { copyConfirmShown = false },
            title = { Text(stringResource(R.string.copied)) },
            text = { Text(stringResource(R.string.recovery_phrase_copied_message)) },
            confirmButton = {
                TextButton(onClick = { copyConfirmShown = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun PhraseCard(
    words: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 2-column grid of indexed words. Visible immediately — the
        // biometric gate in [authenticate] + FLAG_SECURE on the window
        // (set in MainActivity) is the protection. The previous attempt
        // (blur-then-tap-to-reveal, then alpha-then-tap-to-reveal) kept
        // a tap-to-reveal overlay that confused users when the words
        // were already plainly visible (issue #82). Since the user has
        // just authenticated with biometrics, the extra gate adds no
        // real protection and was visually broken on its broken paths.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            words.chunked(2).forEachIndexed { rowIdx, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEachIndexed { colIdx, word ->
                        val displayIndex = rowIdx * 2 + colIdx + 1
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "%2d".format(displayIndex),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                            Text(
                                word,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                    }
                    // Pad-cell when chunked left an odd word out
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ─── Verify ──────────────────────────────────────────────────────────

@Composable
private fun VerifyScreen(
    round: RecoveryPhraseBackupViewModel.VerifyRound,
    progressIndex: Int,
    progressTotal: Int,
    state: RecoveryPhraseBackupViewModel.VerifyState,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProgressDots(current = progressIndex, total = progressTotal)
        }

        // Hero "Select word number N"
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.select_word_number),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${round.wordPosition}",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(20.dp))

        // 4 option rows
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (option in round.options) {
                val isCorrect = state is RecoveryPhraseBackupViewModel.VerifyState.Correct &&
                        option == round.correct
                val isWrong = state is RecoveryPhraseBackupViewModel.VerifyState.Wrong &&
                        state.word == option
                VerifyOption(
                    word = option,
                    isCorrect = isCorrect,
                    isWrong = isWrong,
                    onTap = { onPick(option) },
                )
            }
        }

        if (state is RecoveryPhraseBackupViewModel.VerifyState.Wrong) {
            Text(
                stringResource(R.string.not_the_right_word),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(top = 18.dp),
            )
        }
    }
}

@Composable
private fun VerifyOption(
    word: String,
    isCorrect: Boolean,
    isWrong: Boolean,
    onTap: () -> Unit,
) {
    val (bg, border, fg) = when {
        isCorrect -> Triple(
            Color(0xFF4CAF50).copy(alpha = 0.14f),
            Color(0xFF4CAF50),
            Color(0xFF2E7D32),
        )
        isWrong -> Triple(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.error,
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            Color.Transparent,
            MaterialTheme.colorScheme.onSurface,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .clickable { onTap() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            word,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = fg,
            modifier = Modifier.weight(1f),
        )
        if (isCorrect) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressDots(current: Int, total: Int) {
    val accent = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until total) {
            val width by animateDpAsState(
                targetValue = if (i == current) 24.dp else 8.dp,
                animationSpec = tween(200),
                label = "dotWidth",
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(if (i == current) accent else inactive),
            )
        }
    }
}

// ─── Done ────────────────────────────────────────────────────────────

@Composable
private fun DoneScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF4CAF50), Color(0xFF33C759)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(44.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.backup_verified),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        )

        Spacer(Modifier.height(10.dp))

        Text(
            stringResource(R.string.backup_verified_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )

        Spacer(Modifier.height(36.dp))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Text(stringResource(R.string.done), fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.weight(1f))

        Text(
            stringResource(R.string.backed_up_footer, dateString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 32.dp),
        )
    }
}

private fun dateString(): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())

