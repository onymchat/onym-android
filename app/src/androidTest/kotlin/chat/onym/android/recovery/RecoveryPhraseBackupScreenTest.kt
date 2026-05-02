package chat.onym.android.recovery

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.onym.android.R
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Compose UI tests for [RecoveryPhraseBackupScreen]. Drives the
 * screen through every Step state and asserts on what's actually
 * rendered + that taps dispatch the right intents.
 *
 * Strategy: real [RecoveryPhraseBackupViewModel] (so the actual
 * production composables get exercised end-to-end) wired with the
 * same fakes as
 * [RecoveryPhraseBackupViewModelTest] — fake authenticator, fake
 * clipboard, real [IdentityRepository] against a per-test unique
 * EncryptedSharedPreferences file. The UI tests cover what the
 * ViewModel tests don't: that each [RecoveryPhraseBackupViewModel.Step]
 * value renders the expected text + that tap nodes wire to the
 * matching intent method.
 *
 * Lives in `androidTest/` for the same reason as
 * [RecoveryPhraseBackupViewModelTest]: the real [IdentityRepository]
 * needs the Android Keystore, which Robolectric doesn't simulate.
 *
 * Resource strings are resolved through the
 * [Context.getString]/`InstrumentationRegistry` path so assertions
 * stay in lockstep with `res/values/strings.xml` — translators
 * dropping in a new locale don't break these tests as long as the
 * default-locale string is unchanged.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryPhraseBackupScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testMnemonic =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val testWords = testMnemonic.split(" ")

    private lateinit var ctx: Context
    private lateinit var store: IdentitySecretStore
    private lateinit var repository: IdentityRepository
    private lateinit var authenticator: FakeAuthenticator
    private lateinit var clipboard: FakeClipboard
    private lateinit var viewModel: RecoveryPhraseBackupViewModel

    @Before
    fun setUp() = runBlocking {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
        ctx = ApplicationProvider.getApplicationContext()
        store = IdentitySecretStore(
            ctx,
            prefsFileName = "chat.onym.android.identity.uitests.${UUID.randomUUID()}",
        )
        repository = IdentityRepository(store)
        repository.restore(testMnemonic)
        authenticator = FakeAuthenticator()
        clipboard = FakeClipboard()
        viewModel = RecoveryPhraseBackupViewModel(
            repository = repository,
            authenticator = authenticator,
            clipboard = clipboard,
            strings = FakeStringProvider(ctx),
            clipboardClearDelay = 50.milliseconds,
            verifyAdvanceDelay = 20.milliseconds,
        )
    }

    @After
    fun tearDown() {
        viewModel.stop()
        try { store.wipe() } catch (_: Throwable) {}
    }

    // ─── Intro ─────────────────────────────────────────────────────

    @Test
    fun intro_renders_title_and_continue_button() {
        setContent()
        composeRule.onNodeWithText(string(R.string.your_identity_in_12_words))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.continue_with_biometrics))
            .assertIsDisplayed()
    }

    @Test
    fun intro_continue_disabled_until_bootstrap_completes_then_enabled() {
        setContent()
        // Wait for snapshot drain — viewModel.isReady flips true when
        // bootstrap's first emission lands.
        composeRule.waitUntil(timeoutMillis = 2_000) { viewModel.isReady.value }
        composeRule.onNodeWithText(string(R.string.continue_with_biometrics))
            .assertIsEnabled()
    }

    // ─── Reveal ────────────────────────────────────────────────────

    @Test
    fun reveal_unrevealed_shows_tap_to_reveal_overlay() {
        setContent()
        runBlocking { advanceToReveal() }
        composeRule.onNodeWithText(string(R.string.tap_to_reveal))
            .assertIsDisplayed()
        // Continue + Copy disabled until the user taps to reveal.
        composeRule.onNodeWithText(string(R.string.ive_written_it_down))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.copy))
            .assertIsNotEnabled()
    }

    @Test
    fun reveal_tap_shows_words_and_enables_actions() {
        setContent()
        runBlocking { advanceToReveal() }
        composeRule.onNodeWithText(string(R.string.tap_to_reveal)).performClick()

        // Tap-to-reveal overlay disappears + at least one early + late
        // word is now visible to the user. The test mnemonic repeats
        // "legal" / "winner" / "thank" by design (BIP39 spec vector),
        // so the assertion targets the first / last positions that are
        // unique in the phrase — `onNodeWithText` (singular) requires
        // a unique match.
        composeRule.onNodeWithText(string(R.string.tap_to_reveal))
            .assertIsNotDisplayed()
        composeRule.onNodeWithText(testWords[3], substring = true).assertExists()  // "year"
        composeRule.onNodeWithText(testWords.last(), substring = true).assertExists()  // "yellow"

        // Actions enabled.
        composeRule.onNodeWithText(string(R.string.ive_written_it_down))
            .assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.copy))
            .assertIsEnabled()
    }

    @Test
    fun reveal_copy_writes_to_clipboard_and_shows_dialog() {
        setContent()
        runBlocking { advanceToReveal() }
        composeRule.onNodeWithText(string(R.string.tap_to_reveal)).performClick()
        composeRule.onNodeWithText(string(R.string.copy)).performClick()

        // Confirmation dialog visible.
        composeRule.onNodeWithText(string(R.string.copied)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.recovery_phrase_copied_message))
            .assertIsDisplayed()
        // Side effect happened.
        assertEquals(testMnemonic, clipboard.lastWritten)
    }

    @Test
    fun reveal_continue_advances_to_verify_step() {
        setContent()
        runBlocking { advanceToReveal() }
        composeRule.onNodeWithText(string(R.string.tap_to_reveal)).performClick()
        composeRule.onNodeWithText(string(R.string.ive_written_it_down)).performClick()

        // Verify step prompt visible.
        composeRule.onNodeWithText(string(R.string.select_word_number))
            .assertIsDisplayed()
    }

    // ─── Verify ────────────────────────────────────────────────────

    @Test
    fun verify_wrong_pick_shows_error_text_and_stays_on_round() {
        setContent()
        runBlocking { advanceToVerify() }
        val verifyStep = viewModel.step.value as RecoveryPhraseBackupViewModel.Step.Verify
        val wrong = verifyStep.rounds[verifyStep.index]
            .options.first { it != verifyStep.rounds[verifyStep.index].correct }

        composeRule.onNodeWithText(wrong, substring = false).performClick()
        composeRule.onNodeWithText(string(R.string.not_the_right_word))
            .assertIsDisplayed()
        // Still on verify with the same prompt.
        composeRule.onNodeWithText(string(R.string.select_word_number))
            .assertIsDisplayed()
    }

    @Test
    fun verify_three_correct_picks_advance_to_done() {
        setContent()
        runBlocking { advanceToVerify() }

        // Walk all 3 rounds.
        repeat(3) {
            val step = viewModel.step.value as RecoveryPhraseBackupViewModel.Step.Verify
            val correct = step.rounds[step.index].correct
            composeRule.onNodeWithText(correct, substring = false).performClick()
            // verifyAdvanceDelay is 20ms; wait a comfortable multiple
            // for the next round to appear (or for Done to render).
            composeRule.waitUntil(timeoutMillis = 1_000) {
                val s = viewModel.step.value
                s !is RecoveryPhraseBackupViewModel.Step.Verify ||
                        s.index != step.index ||
                        s.state == RecoveryPhraseBackupViewModel.VerifyState.Idle
            }
        }
        composeRule.onNodeWithText(string(R.string.backup_verified))
            .assertIsDisplayed()
    }

    // ─── Done ──────────────────────────────────────────────────────

    @Test
    fun done_tap_loops_back_to_intro() {
        setContent()
        runBlocking {
            advanceToVerify()
            // Pick the correct word at every round so the flow reaches Done.
            repeat(3) {
                val step = viewModel.step.value as RecoveryPhraseBackupViewModel.Step.Verify
                viewModel.picked(step.rounds[step.index].correct)
                kotlinx.coroutines.delay(60)
            }
        }
        composeRule.waitUntil(timeoutMillis = 1_000) {
            viewModel.step.value == RecoveryPhraseBackupViewModel.Step.Done
        }
        composeRule.onNodeWithText(string(R.string.done)).performClick()
        composeRule.onNodeWithText(string(R.string.your_identity_in_12_words))
            .assertIsDisplayed()
    }

    // ─── Auth failure ──────────────────────────────────────────────

    @Test
    fun auth_failure_shows_alert_dialog_with_reason() {
        authenticator.outcome = FakeAuthenticator.Outcome.Failure(
            BiometricAuthException(13, "User cancelled")
        )
        setContent()
        runBlocking { viewModel.authenticate() }

        composeRule.onNodeWithText(string(R.string.authentication_failed))
            .assertIsDisplayed()
        composeRule.onNodeWithText("User cancelled").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.try_again)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
    }

    @Test
    fun auth_failure_dialog_cancel_dismisses_and_returns_to_intro() {
        authenticator.outcome = FakeAuthenticator.Outcome.Failure(
            BiometricAuthException(13, "x")
        )
        setContent()
        runBlocking { viewModel.authenticate() }

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        composeRule.onNodeWithText(string(R.string.authentication_failed))
            .assertIsNotDisplayed()
        composeRule.onNodeWithText(string(R.string.your_identity_in_12_words))
            .assertIsDisplayed()
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                RecoveryPhraseBackupScreen(viewModel = viewModel)
            }
        }
    }

    private fun string(resId: Int): String = ctx.getString(resId)

    private suspend fun advanceToReveal() {
        // The screen's LaunchedEffect kicks off start(); wait for the
        // bootstrap snapshot to land before driving auth.
        composeRule.waitUntil(timeoutMillis = 2_000) { viewModel.isReady.value }
        authenticator.outcome = FakeAuthenticator.Outcome.Success
        viewModel.authenticate()
        composeRule.waitUntil(timeoutMillis = 1_000) {
            viewModel.step.value is RecoveryPhraseBackupViewModel.Step.Reveal
        }
    }

    private suspend fun advanceToVerify() {
        advanceToReveal()
        viewModel.tappedReveal()
        viewModel.tappedContinueFromReveal()
        composeRule.waitUntil(timeoutMillis = 1_000) {
            viewModel.step.value is RecoveryPhraseBackupViewModel.Step.Verify
        }
    }

    // ─── Fakes ─────────────────────────────────────────────────────

    private class FakeAuthenticator : BiometricAuthenticator {
        sealed class Outcome {
            data object Success : Outcome()
            data class Failure(val cause: Throwable) : Outcome()
        }
        var outcome: Outcome = Outcome.Success
        override suspend fun authenticate(title: String, subtitle: String?) {
            when (val o = outcome) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> throw o.cause
            }
        }
    }

    private class FakeClipboard : ClipboardWriter {
        var lastWritten: String? = null
        var didClear: Boolean = false
        override fun write(value: String) { lastWritten = value }
        override fun clearIfStill(value: String) {
            if (lastWritten == value) didClear = true
        }
    }

    /** Production-shape provider; resolves real strings from resources
     *  so the ViewModel's resource lookups (BiometricPrompt title,
     *  fallback error message) match what the Composable layer reads. */
    private class FakeStringProvider(private val context: Context) : StringProvider {
        override fun get(resId: Int): String = context.getString(resId)
    }
}
