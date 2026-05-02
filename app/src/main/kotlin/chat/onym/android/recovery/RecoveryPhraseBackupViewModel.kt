package chat.onym.android.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.onym.android.R
import chat.onym.android.identity.Identity
import chat.onym.android.identity.IdentityRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Single source of truth for the "Back up keys" flow.
 *
 * State flows down ([step]); intents flow up (the `tapped*` / `picked` /
 * `dismissed*` methods). The UI never mutates state directly — it
 * collects [step], renders the matching screen, and calls intents on
 * user actions. All side effects (storage via [IdentityRepository],
 * [BiometricAuthenticator], [ClipboardWriter], randomness, [delay])
 * live here.
 *
 * Mirrors `onym-ios/Sources/OnymIOS/Recovery/RecoveryPhraseBackupFlow.swift`
 * 1:1, with iOS primitives mapped to their Android equivalents:
 *
 *   - `@Observable` → `StateFlow`
 *   - actor / `nonisolated AsyncStream` → `StateFlow` (snapshot drain)
 *   - `Task { … }` / `Task.sleep` → `viewModelScope.launch { … }` /
 *     [delay]
 *   - `LAContext` → AndroidX BiometricPrompt (behind
 *     [BiometricAuthenticator])
 *   - `UIPasteboard` → [android.content.ClipboardManager] (behind
 *     [ClipboardWriter])
 */
class RecoveryPhraseBackupViewModel(
    private val repository: IdentityRepository,
    private val authenticator: BiometricAuthenticator,
    private val clipboard: ClipboardWriter,
    private val strings: StringProvider,
    private val clipboardClearDelay: Duration = 60.seconds,
    private val verifyAdvanceDelay: Duration = 450.milliseconds,
) : ViewModel() {

    sealed class Step {
        data object Intro : Step()
        data class AuthFailed(val reason: String) : Step()
        data class Reveal(val phrase: String, val revealed: Boolean) : Step()
        data class Verify(
            val phrase: String,
            val rounds: List<VerifyRound>,
            val index: Int,
            val state: VerifyState,
        ) : Step()
        data object Done : Step()
    }

    sealed class VerifyState {
        data object Idle : VerifyState()
        data object Correct : VerifyState()
        data class Wrong(val word: String) : VerifyState()
    }

    data class VerifyRound(
        /** 1-based position in the phrase (so the prompt can say
         *  "word #4"). */
        val wordPosition: Int,
        val correct: String,
        val options: List<String>,
    )

    // ─── Public state ────────────────────────────────────────────────

    private val _step = MutableStateFlow<Step>(Step.Intro)
    val step: StateFlow<Step> = _step.asStateFlow()

    /**
     * `true` once [IdentityRepository.bootstrap] has resolved and
     * produced a snapshot. The view disables the Continue button until
     * this flips so a too-eager tap on first launch can't race the
     * bootstrap write.
     */
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    // ─── Internal ───────────────────────────────────────────────────

    private var snapshotJob: Job? = null
    private var verifyAdvanceJob: Job? = null
    private var clipboardClearJob: Job? = null

    /**
     * Cached identity — read once at authenticate-time so the reveal
     * step doesn't have to suspend on the repo. The view never sees
     * this; only the recovery phrase escapes via [Step.Reveal.phrase],
     * gated behind the auth + tap-to-reveal flow.
     */
    private var currentIdentity: Identity? = null

    /**
     * Begin draining the repository's snapshots into the cached
     * [currentIdentity]. Idempotent — safe to call from
     * [androidx.compose.runtime.LaunchedEffect] on every entry.
     */
    fun start() {
        if (snapshotJob != null) return
        snapshotJob = viewModelScope.launch {
            try { repository.bootstrap() } catch (_: Throwable) { /* surfaced in authenticate() */ }
            repository.snapshots.collect { snapshot ->
                currentIdentity = snapshot
                if (snapshot != null && !_isReady.value) _isReady.value = true
            }
        }
    }

    /**
     * Cancel any in-flight jobs. Called automatically by
     * [androidx.lifecycle.ViewModel.onCleared] via [viewModelScope];
     * exposed so tests + `LifecycleEventObserver`-style callers can
     * tear down deterministically.
     */
    fun stop() {
        snapshotJob?.cancel(); snapshotJob = null
        verifyAdvanceJob?.cancel(); verifyAdvanceJob = null
        clipboardClearJob?.cancel(); clipboardClearJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    // ─── Intents (called from the view) ─────────────────────────────

    fun tappedContinueFromIntro() {
        viewModelScope.launch { authenticate() }
    }

    fun dismissedAuthError() {
        if (_step.value is Step.AuthFailed) _step.value = Step.Intro
    }

    fun tappedReveal() {
        val current = _step.value
        if (current is Step.Reveal) {
            _step.value = current.copy(revealed = true)
        }
    }

    fun tappedCopyPhrase() {
        val current = _step.value
        if (current !is Step.Reveal || !current.revealed) return
        // onym:allow-secret-read: copy is the explicit user intent on the
        // reveal screen; auto-clears after `clipboardClearDelay` so the
        // value doesn't sit on the system clipboard indefinitely.
        val phrase = current.phrase
        clipboard.write(phrase)
        clipboardClearJob?.cancel()
        clipboardClearJob = viewModelScope.launch {
            delay(clipboardClearDelay)
            clipboard.clearIfStill(phrase)
        }
    }

    fun tappedContinueFromReveal() {
        val current = _step.value
        if (current is Step.Reveal && current.revealed) {
            _step.value = Step.Verify(
                phrase = current.phrase,
                rounds = makeRounds(current.phrase),
                index = 0,
                state = VerifyState.Idle,
            )
        }
    }

    fun picked(word: String) {
        val current = _step.value as? Step.Verify ?: return
        // Second pick during the in-flight 450ms advance is ignored.
        if (current.state is VerifyState.Correct) return
        val round = current.rounds[current.index]
        if (word == round.correct) {
            _step.value = current.copy(state = VerifyState.Correct)
            verifyAdvanceJob?.cancel()
            verifyAdvanceJob = viewModelScope.launch {
                delay(verifyAdvanceDelay)
                // Re-check that we're still in the same verify step
                // we left — a wipe / restart could have moved the
                // ground out from under us mid-delay.
                val now = _step.value as? Step.Verify ?: return@launch
                if (now.index != current.index || now.state !is VerifyState.Correct) return@launch
                _step.value = if (now.index + 1 >= now.rounds.size) {
                    Step.Done
                } else {
                    now.copy(index = now.index + 1, state = VerifyState.Idle)
                }
            }
        } else {
            _step.value = current.copy(state = VerifyState.Wrong(word))
        }
    }

    fun tappedDoneFromCompletion() {
        // Single-screen app: loop back to intro so the user can re-verify.
        // Once a settings/home screen exists, this becomes a navigation pop.
        _step.value = Step.Intro
    }

    // ─── Internal (also reachable from tests) ───────────────────────

    /**
     * Drives the auth + reveal handoff. Public-by-package so tests can
     * exercise it without going through [tappedContinueFromIntro]
     * (which buries the call inside [viewModelScope.launch]).
     */
    internal suspend fun authenticate() {
        try {
            authenticator.authenticate(
                title = strings[R.string.authenticate_to_reveal_recovery_phrase],
            )
        } catch (t: Throwable) {
            _step.value = Step.AuthFailed(
                reason = t.message
                    ?: t::class.qualifiedName
                    ?: strings[R.string.authentication_failed],
            )
            return
        }
        // onym:allow-secret-read: revealing the recovery phrase to the user
        // is the entire purpose of this flow. The reveal screen gates the
        // text behind a tap-to-reveal, FLAG_SECURE on the window, and the
        // (biometric / device-credential)-gated authenticator above.
        val phrase = currentIdentity?.recoveryPhrase
        if (phrase == null) {
            _step.value = Step.AuthFailed(
                reason = strings[R.string.recovery_phrase_unavailable],
            )
            return
        }
        _step.value = Step.Reveal(phrase = phrase, revealed = false)
    }

    companion object {
        /**
         * Pick three random word positions, present each as a 4-way
         * multiple choice with one correct + three random distractors
         * from the same phrase. Matches the iOS reference impl.
         */
        internal fun makeRounds(phrase: String): List<VerifyRound> {
            val words = phrase.split(" ").filter { it.isNotEmpty() }
            val positions = (0 until words.size).shuffled().take(3).sorted()
            return positions.map { pos ->
                val opts = mutableSetOf(words[pos])
                while (opts.size < 4) {
                    val candidate = words.random()
                    if (candidate != words[pos]) opts.add(candidate)
                }
                VerifyRound(
                    wordPosition = pos + 1,
                    correct = words[pos],
                    options = opts.toList().shuffled(),
                )
            }
        }
    }
}
