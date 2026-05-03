package chat.onym.android.recovery

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives [RecoveryPhraseBackupViewModel] against:
 *   - a real [IdentityRepository] (per-test unique
 *     EncryptedSharedPreferences file so runs don't collide), seeded
 *     by `restore(mnemonic)` so the recovery phrase is deterministic
 *   - a fake [BiometricAuthenticator] whose outcome is set per test
 *   - a fake [ClipboardWriter] so the system clipboard is never touched
 *
 * Lives in `androidTest/` (not `test/`) because the real
 * `IdentityRepository` requires Android Keystore access, which
 * Robolectric doesn't simulate. Mirrors iOS's "real Keychain behaviour
 * or it doesn't count" rule.
 *
 * Verify-step rounds are random (3 of 12 with 3 distractors each), so
 * tests work against [RecoveryPhraseBackupViewModel.step] *shape* and
 * read the correct word from each round at runtime — reaching
 * [RecoveryPhraseBackupViewModel.Step.Done] requires picking the
 * correct word at every round.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryPhraseBackupViewModelTest {

    /**
     * Canonical BIP39 test vector with 12 distinct words so verify
     * rounds have 4 truly distinct options to choose from. (The other
     * canonical `abandon × 11 + about` mnemonic has 11 of 12 words
     * collide on `abandon`.)
     */
    private val testMnemonic =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"

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
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = IdentitySecretStore(
            ctx,
            prefsFileName = "chat.onym.android.identity.recoverytests.${UUID.randomUUID()}",
        )
        repository = IdentityRepository(store)
        repository.restore(testMnemonic)
        authenticator = FakeAuthenticator()
        clipboard = FakeClipboard()
        viewModel = RecoveryPhraseBackupViewModel(
            repository = repository,
            authenticator = authenticator,
            clipboard = clipboard,
            // FakeStringProvider returns the resource id stringified —
            // tests assert on Step shape + behaviour, not on locale.
            strings = FakeStringProvider,
            // Short delays so the suite runs in well under a second.
            // iOS runs in ~800 ms; matching that order of magnitude.
            clipboardClearDelay = 50.milliseconds,
            verifyAdvanceDelay = 20.milliseconds,
        )
        viewModel.start()
        waitForReady()
    }

    @After
    fun tearDown() {
        viewModel.stop()
        try { store.wipeAll() } catch (_: Throwable) {}
    }

    // ─── Auth ───────────────────────────────────────────────────────

    @Test
    fun initialStep_isIntro() {
        assertEquals(RecoveryPhraseBackupViewModel.Step.Intro, viewModel.step.value)
    }

    @Test
    fun isReady_flipsTrueAfterBootstrap() {
        // setUp's waitForReady spun until this flipped — re-assert
        // for the failure-mode visibility (the assertion message names
        // the broken invariant).
        assertTrue("flow should be ready after setUp's waitForReady", viewModel.isReady.value)
    }

    @Test
    fun authSuccess_transitionsToReveal_unrevealed() = runBlocking {
        authenticator.outcome = FakeAuthenticator.Outcome.Success
        viewModel.authenticate()

        val step = viewModel.step.value
        if (step !is RecoveryPhraseBackupViewModel.Step.Reveal) {
            fail("expected Reveal, got $step"); return@runBlocking
        }
        assertEquals(testMnemonic, step.phrase)
        assertEquals(false, step.revealed)
    }

    @Test
    fun authFailure_transitionsToAuthFailed_withMessage() = runBlocking {
        authenticator.outcome =
            FakeAuthenticator.Outcome.Failure(BiometricAuthException(13, "User cancelled"))
        viewModel.authenticate()

        val step = viewModel.step.value
        if (step !is RecoveryPhraseBackupViewModel.Step.AuthFailed) {
            fail("expected AuthFailed, got $step"); return@runBlocking
        }
        assertEquals("User cancelled", step.reason)
    }

    @Test
    fun dismissedAuthError_resetsToIntro() = runBlocking {
        authenticator.outcome =
            FakeAuthenticator.Outcome.Failure(BiometricAuthException(13, "x"))
        viewModel.authenticate()
        assertNotEquals(RecoveryPhraseBackupViewModel.Step.Intro, viewModel.step.value)

        viewModel.dismissedAuthError()
        assertEquals(RecoveryPhraseBackupViewModel.Step.Intro, viewModel.step.value)
    }

    // ─── Reveal ─────────────────────────────────────────────────────

    @Test
    fun tappedReveal_flipsRevealedTrue() = runBlocking {
        advanceToReveal()
        viewModel.tappedReveal()

        val step = viewModel.step.value
        if (step !is RecoveryPhraseBackupViewModel.Step.Reveal) {
            fail("expected Reveal, got $step"); return@runBlocking
        }
        assertTrue(step.revealed)
    }

    @Test
    fun tappedCopyPhrase_writesToClipboard_thenClearsAfterDelay() = runBlocking {
        advanceToReveal()
        viewModel.tappedReveal()

        viewModel.tappedCopyPhrase()
        assertEquals(testMnemonic, clipboard.lastWritten)

        // clipboardClearDelay is 50 ms in tests — wait a comfortable
        // multiple to let the auto-clear run.
        delay(150)
        assertTrue("clipboard auto-clear didn't run", clipboard.didClear)
    }

    @Test
    fun tappedCopyPhrase_isNoop_beforeReveal() = runBlocking {
        advanceToReveal()
        // Don't tap reveal — phrase still hidden.
        viewModel.tappedCopyPhrase()
        assertNull(clipboard.lastWritten)
    }

    @Test
    fun tappedContinueFromReveal_movesToVerify_withThreeRounds() = runBlocking {
        advanceToReveal()
        viewModel.tappedReveal()
        viewModel.tappedContinueFromReveal()

        val step = viewModel.step.value
        if (step !is RecoveryPhraseBackupViewModel.Step.Verify) {
            fail("expected Verify, got $step"); return@runBlocking
        }
        assertEquals(testMnemonic, step.phrase)
        assertEquals("three verify rounds, picked at random from 12 positions",
            3, step.rounds.size)
        assertEquals(0, step.index)
        assertEquals(RecoveryPhraseBackupViewModel.VerifyState.Idle, step.state)
        for (round in step.rounds) {
            assertEquals("four options per round", 4, round.options.size)
            assertTrue("correct word must be in options",
                round.options.contains(round.correct))
            assertTrue(round.wordPosition in 1..12)
        }
    }

    // ─── Verify ─────────────────────────────────────────────────────

    @Test
    fun pickedCorrect_advancesAfterDelay_andEventuallyHitsDone() = runBlocking {
        advanceToVerify()

        repeat(3) {
            val step = viewModel.step.value
            if (step !is RecoveryPhraseBackupViewModel.Step.Verify) {
                fail("expected Verify mid-loop, got $step"); return@runBlocking
            }
            viewModel.picked(step.rounds[step.index].correct)
            // verifyAdvanceDelay is 20 ms — wait 60 ms to let the advance fire.
            delay(60)
        }

        assertEquals(RecoveryPhraseBackupViewModel.Step.Done, viewModel.step.value)
    }

    @Test
    fun pickedWrong_marksWrong_andStaysOnSameRound() = runBlocking {
        advanceToVerify()

        val step = viewModel.step.value as RecoveryPhraseBackupViewModel.Step.Verify
        val round = step.rounds[step.index]
        val wrong = round.options.first { it != round.correct }
        viewModel.picked(wrong)

        val after = viewModel.step.value
        if (after !is RecoveryPhraseBackupViewModel.Step.Verify) {
            fail("expected to stay on Verify after wrong pick, got $after"); return@runBlocking
        }
        assertEquals("wrong pick must NOT advance the round", step.index, after.index)
        assertEquals(RecoveryPhraseBackupViewModel.VerifyState.Wrong(wrong), after.state)
    }

    @Test
    fun pickedCorrect_thenWrongPickIgnored_inflightAdvance() = runBlocking {
        advanceToVerify()

        val step = viewModel.step.value as RecoveryPhraseBackupViewModel.Step.Verify
        val round = step.rounds[step.index]
        val correct = round.correct
        val wrong = round.options.first { it != correct }

        viewModel.picked(correct)
        // Immediately try a wrong pick — should be ignored because state == Correct
        viewModel.picked(wrong)

        val after = viewModel.step.value
        if (after !is RecoveryPhraseBackupViewModel.Step.Verify) {
            fail("expected Verify after correct, got $after"); return@runBlocking
        }
        assertEquals(step.index, after.index)
        assertEquals(
            "second pick during in-flight advance must be ignored",
            RecoveryPhraseBackupViewModel.VerifyState.Correct,
            after.state,
        )
    }

    // ─── Done ──────────────────────────────────────────────────────

    @Test
    fun tappedDoneFromCompletion_loopsBackToIntro() = runBlocking {
        runEntireFlow()
        assertEquals(RecoveryPhraseBackupViewModel.Step.Done, viewModel.step.value)

        viewModel.tappedDoneFromCompletion()
        assertEquals(RecoveryPhraseBackupViewModel.Step.Intro, viewModel.step.value)
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private suspend fun waitForReady() {
        repeat(50) {
            if (viewModel.isReady.value) return
            delay(10)
        }
        fail("viewModel.isReady never flipped true within 500 ms")
    }

    private suspend fun advanceToReveal() {
        authenticator.outcome = FakeAuthenticator.Outcome.Success
        viewModel.authenticate()
        if (viewModel.step.value !is RecoveryPhraseBackupViewModel.Step.Reveal) {
            fail("expected Reveal, got ${viewModel.step.value}")
        }
    }

    private suspend fun advanceToVerify() {
        advanceToReveal()
        viewModel.tappedReveal()
        viewModel.tappedContinueFromReveal()
    }

    private suspend fun runEntireFlow() {
        advanceToVerify()
        repeat(3) {
            val step = viewModel.step.value as RecoveryPhraseBackupViewModel.Step.Verify
            viewModel.picked(step.rounds[step.index].correct)
            delay(60)
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

    /** Returns the resource id stringified, so tests assert on
     *  Step shape + behaviour rather than locale-specific copy. */
    private object FakeStringProvider : StringProvider {
        override fun get(resId: Int): String = "string:$resId"
    }
}
