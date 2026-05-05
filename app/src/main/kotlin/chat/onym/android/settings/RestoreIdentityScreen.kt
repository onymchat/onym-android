package chat.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.onym.android.R
import chat.onym.android.identity.RestoreIdentityViewModel

/**
 * Settings → Identities → Restore. Paste/type a 12- or 24-word BIP39
 * recovery phrase + an optional alias, then tap Restore. The repository
 * derives keys from the phrase and adds the identity alongside any
 * existing ones (non-destructive — see [chat.onym.android.identity.IdentityRepository.add]).
 *
 * Validation is live: the BIP39 checksum check from
 * [chat.onym.android.identity.Bip39.isValidMnemonic] gates the Restore
 * button and drives an inline hint that turns red on invalid input.
 *
 * The screen is intentionally bare — no biometric gate, no multi-step
 * verify — restore IS the verify (a wrong phrase yields a different
 * identity; the user notices on first chat). Mirrors how every other
 * BIP39 wallet treats restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreIdentityScreen(
    viewModel: RestoreIdentityViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val phrase by viewModel.phrase.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val validation by viewModel.validation.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val errorTemplate = stringResource(R.string.identities_error_add)
    LaunchedEffect(state) {
        val s = state
        if (s is RestoreIdentityViewModel.State.Error) {
            snackbarHostState.showSnackbar(errorTemplate.format(s.cause))
            viewModel.clearError()
        } else if (s is RestoreIdentityViewModel.State.Done) {
            viewModel.acknowledgeDone()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.restore_identity_screen_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("restore_identity.back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
                .testTag("restore_identity.screen"),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.restore_identity_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.restore_identity_phrase_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )

            val isInvalid = validation == RestoreIdentityViewModel.Validation.Invalid
            OutlinedTextField(
                value = phrase,
                onValueChange = viewModel::setPhrase,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .testTag("restore_identity.phrase"),
                placeholder = {
                    Text(stringResource(R.string.restore_identity_phrase_placeholder))
                },
                isError = isInvalid,
                singleLine = false,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Next,
                ),
            )

            Spacer(Modifier.height(6.dp))

            // Validation hint slot. Stable height so the layout doesn't
            // jump when the user finishes typing the 12th word.
            Box(modifier = Modifier.heightIn(min = 18.dp)) {
                val hint = when (validation) {
                    RestoreIdentityViewModel.Validation.Invalid ->
                        stringResource(R.string.restore_identity_invalid)
                    RestoreIdentityViewModel.Validation.Valid ->
                        stringResource(R.string.restore_identity_valid)
                    RestoreIdentityViewModel.Validation.Empty ->
                        stringResource(R.string.restore_identity_word_count_hint)
                }
                val color = when (validation) {
                    RestoreIdentityViewModel.Validation.Invalid -> MaterialTheme.colorScheme.error
                    RestoreIdentityViewModel.Validation.Valid -> SettingsTile.Green
                    RestoreIdentityViewModel.Validation.Empty -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .testTag("restore_identity.hint"),
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.restore_identity_name_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = viewModel::setName,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("restore_identity.name"),
                placeholder = {
                    Text(stringResource(R.string.restore_identity_name_placeholder))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            )

            Spacer(Modifier.height(28.dp))

            val canSubmit = validation == RestoreIdentityViewModel.Validation.Valid &&
                state !is RestoreIdentityViewModel.State.Restoring
            Button(
                onClick = viewModel::submit,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("restore_identity.submit"),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text(
                    text = if (state is RestoreIdentityViewModel.State.Restoring) {
                        stringResource(R.string.restore_identity_restoring)
                    } else {
                        stringResource(R.string.restore_identity_action)
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.restore_identity_footnote),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }
}
