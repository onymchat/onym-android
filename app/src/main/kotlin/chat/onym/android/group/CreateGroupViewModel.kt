package chat.onym.android.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.onym.android.chain.SepGroupType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The five routes the design's flow walks through. Each screen
 * renders based on [CreateGroupState.route]; transitions are driven
 * by intents (next, back, addInvitee, submit, etc).
 *
 * Mirrors `CreateGroupRoute` from onym-ios PR #26.
 */
enum class CreateGroupRoute {
    Step1,            // name + accent + governance
    Step2,            // review invitees + create
    InviteByKey,      // paste 64-char inbox key
    Creating,         // progress steps
    Success,          // hero + members + done
}

/**
 * One pasted-and-validated invitee. The 32-byte X25519 key is what
 * the interactor needs; [displayLabel] is the hex prefix the UI
 * renders (full hex is too long for a row).
 *
 * Mirrors `OnymInvitee` from onym-ios PR #26.
 */
data class OnymInvitee(
    val id: String,
    val inboxPublicKey: ByteArray,
    /** Cached "a4f9b2…51" prefix for display. Computed once at add
     *  time so the LazyColumn doesn't re-derive on every row. */
    val displayLabel: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OnymInvitee) return false
        return id == other.id &&
            inboxPublicKey.contentEquals(other.inboxPublicKey) &&
            displayLabel == other.displayLabel
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + inboxPublicKey.contentHashCode()
        h = 31 * h + displayLabel.hashCode()
        return h
    }
}

/**
 * The ViewModel's state. Every screen reads off this single value;
 * intent methods on [CreateGroupViewModel] mutate it through a
 * [MutableStateFlow] so Compose recomposes via
 * `collectAsStateWithLifecycle()`.
 */
data class CreateGroupState(
    val name: String = "",
    /** Friendly placeholder generated on init / reset (e.g. "Maple
     *  Garden"). The TextField pre-fills with this so the user can
     *  hit Create immediately without typing. First focus on the
     *  field clears it; submit also falls back to this if the user
     *  emptied the field and didn't retype. */
    val generatedName: String = "",
    /** Goes true on the first focus event of the name field. Used
     *  to distinguish "user accepted the placeholder" from "user
     *  wants to type their own". */
    val nameFieldHasBeenFocused: Boolean = false,
    val accent: OnymAccent = OnymAccent.Blue,
    /** Always [OnymUIGovernance.Tyranny] in PR-C — the picker
     *  disables the others. */
    val governance: OnymUIGovernance = OnymUIGovernance.Tyranny,
    val invitees: List<OnymInvitee> = emptyList(),
    /** Bound to the InviteByKey TextField. */
    val inviteeInput: String = "",
    /** Inline error shown under the InviteByKey TextField. Cleared
     *  on every keystroke. */
    val inviteeError: String? = null,
    val route: CreateGroupRoute = CreateGroupRoute.Step1,
    val progress: CreateGroupProgress? = null,
    /** Set when the interactor throws. Surface as a banner / inline
     *  error in the Creating / Step1 / Step2 screens. */
    val error: CreateGroupError? = null,
    /** Populated when the pipeline finishes — drives the Success
     *  screen content. */
    val createdGroup: ChatGroup? = null,
) {
    /** True when the selected governance type is wired to the chain
     *  layer. All three flavours (Tyranny / 1-on-1 / Anarchy) are
     *  wired today. The name no longer gates advance — submit falls
     *  back to [generatedName] when the field is empty. */
    val canAdvanceToStep2: Boolean
        get() = governance.isAvailable

    /** What the interactor receives: the user's typed name if
     *  non-blank, else the placeholder we generated for them. */
    val effectiveName: String
        get() = name.trim().ifEmpty { generatedName }

    /** Label for the primary "Create" CTA on Step2. Per-governance:
     *  - Tyranny tolerates 0..N invitees ("Create empty group" / "with N").
     *  - 1-on-1 needs exactly 1 invitee — the CTA spells out the gate
     *    so the user understands why it's disabled. */
    val createCtaLabel: String
        get() = when (governance) {
            OnymUIGovernance.OneOnOne -> when (invitees.size) {
                0 -> "Add the other person"
                1 -> "Start 1-on-1"
                else -> "1-on-1 needs exactly one"
            }
            else -> when (invitees.size) {
                0 -> "Create empty group"
                1 -> "Create with 1 person"
                else -> "Create with ${invitees.size} people"
            }
        }

    /** Whether the Create CTA on Step2 is enabled. Tyranny is always
     *  enabled (zero invitees = creator-only group); 1-on-1 requires
     *  exactly one invitee — the chain leg would reject otherwise
     *  (PR-2's `OneOnOneRequiresExactlyOneInvitee`). */
    val canSubmit: Boolean
        get() = when (governance) {
            OnymUIGovernance.OneOnOne -> invitees.size == 1
            else -> governance.isAvailable
        }

    /** Live char count for the InviteByKey field — matches the
     *  design's `(43/64)` chip. Strips whitespace first. */
    val inviteeInputCleanedLength: Int
        get() = inviteeInput.replace(WHITESPACE_REGEX, "").length

    val inviteeInputIsValid: Boolean
        get() {
            val cleaned = inviteeInput.replace(WHITESPACE_REGEX, "")
            return cleaned.length == 64 && decodeHex(cleaned)?.size == 32
        }
}

/**
 * StateFlow-backed driver for the five Create Group screens.
 * Android equivalent of iOS's `@Observable @MainActor CreateGroupFlow`.
 * Compose collects [state] via `collectAsStateWithLifecycle()` and
 * dispatches intents on tap.
 *
 * Mirrors `CreateGroupFlow` from onym-ios PR #26.
 */
/**
 * Function the ViewModel calls to drive the create pipeline.
 * Production wires this to [CreateGroupInteractor.create]; tests
 * pass a stub that returns a canned [ChatGroup] (or throws) without
 * standing up the real identity / relayer / contracts graph.
 *
 * The function shape — `suspend (name, invitees, onProgress)
 * → ChatGroup` — is the iOS twin's `interactor.create` contract
 * lifted into a Kotlin functional type. Keeping the VM dependency
 * narrow (just this lambda, not the whole interactor) lets the VM
 * tests stay in `test/` (no Android Keystore + JNI required).
 */
typealias GroupCreator = suspend (
    name: String,
    invitees: List<ByteArray>,
    groupType: SepGroupType,
    onProgress: (CreateGroupProgress) -> Unit,
) -> ChatGroup

class CreateGroupViewModel(
    private val createGroup: GroupCreator,
) : ViewModel() {

    private val _state = MutableStateFlow(freshState())
    val state: StateFlow<CreateGroupState> = _state.asStateFlow()

    /** Tapped Cancel/Done from any screen — host (Dialog presenter)
     *  dismisses. */
    var onClose: () -> Unit = {}

    /**
     * Called by the Step1 view when the name TextField gets focus.
     * On the *first* focus we clear the field so the user can type a
     * fresh name without manually deleting the placeholder. After
     * that, focus is a no-op — the user is in charge of the field.
     *
     * Mirrors `tappedNameFieldFocused` from onym-ios PR #27.
     */
    fun nameFieldFocused() {
        val s = _state.value
        if (s.nameFieldHasBeenFocused) return
        _state.value = s.copy(
            nameFieldHasBeenFocused = true,
            // Only clear if the user hasn't already replaced the
            // placeholder with something else (defensive — onFocusChanged
            // can fire before onValueChange in some Compose builds).
            name = if (s.name == s.generatedName) "" else s.name,
        )
    }

    // ─── Step 1 → Step 2 ──────────────────────────────────────────

    fun setName(text: String) {
        // Match iOS: cap at 32 chars to avoid runaway names that break layout.
        val capped = if (text.length > 32) text.take(32) else text
        _state.value = _state.value.copy(name = capped)
    }

    fun setAccent(accent: OnymAccent) {
        _state.value = _state.value.copy(accent = accent)
    }

    fun setGovernance(governance: OnymUIGovernance) {
        // Disabled cards send no intent in the UI; this guard makes
        // the ViewModel-level invariant explicit.
        if (!governance.isAvailable) return
        _state.value = _state.value.copy(governance = governance)
    }

    fun tappedNext() {
        if (!_state.value.canAdvanceToStep2) return
        _state.value = _state.value.copy(route = CreateGroupRoute.Step2)
    }

    // ─── Step 2 ───────────────────────────────────────────────────

    fun tappedInviteByKey() {
        _state.value = _state.value.copy(
            inviteeInput = "",
            inviteeError = null,
            route = CreateGroupRoute.InviteByKey,
        )
    }

    fun tappedBackFromStep2() {
        _state.value = _state.value.copy(route = CreateGroupRoute.Step1)
    }

    fun removeInvitee(index: Int) {
        val current = _state.value.invitees
        if (index !in current.indices) return
        _state.value = _state.value.copy(invitees = current - current[index])
    }

    // ─── InviteByKey ──────────────────────────────────────────────

    fun setInviteeInput(text: String) {
        // Typing clears the stale error.
        _state.value = _state.value.copy(inviteeInput = text, inviteeError = null)
    }

    fun tappedAddInvitee() {
        val cleaned = _state.value.inviteeInput.replace(WHITESPACE_REGEX, "")
        if (cleaned.isEmpty()) {
            _state.value = _state.value.copy(inviteeError = "Paste an inbox key to continue.")
            return
        }
        if (cleaned.length != 64) {
            _state.value = _state.value.copy(
                inviteeError = "Inbox keys are 64 characters. You pasted ${cleaned.length}.",
            )
            return
        }
        val raw = decodeHex(cleaned)
        if (raw == null || raw.size != 32) {
            _state.value = _state.value.copy(
                inviteeError = "That doesn’t look like a valid inbox key.",
            )
            return
        }
        val prefix = cleaned.take(6)
        val suffix = cleaned.takeLast(4)
        val invitee = OnymInvitee(
            id = UUID.randomUUID().toString(),
            inboxPublicKey = raw,
            displayLabel = "$prefix…$suffix",
        )
        _state.value = _state.value.copy(
            invitees = _state.value.invitees + invitee,
            inviteeInput = "",
            inviteeError = null,
            route = CreateGroupRoute.Step2,
        )
    }

    fun tappedCancelInviteByKey() {
        _state.value = _state.value.copy(route = CreateGroupRoute.Step2)
    }

    // ─── Submit ───────────────────────────────────────────────────

    fun tappedCreate() {
        viewModelScope.launch { submit() }
    }

    /** Public for tests; production code calls [tappedCreate]. */
    suspend fun submit() {
        val current = _state.value
        if (!current.governance.isAvailable) {
            _state.value = current.copy(
                error = CreateGroupError.NoContractBinding(current.governance.governanceType),
            )
            return
        }
        // 1-on-1 needs exactly one invitee — surface the error here
        // rather than letting the interactor throw mid-pipeline.
        if (current.governance == OnymUIGovernance.OneOnOne &&
            current.invitees.size != 1
        ) {
            _state.value = current.copy(
                error = CreateGroupError.OneOnOneRequiresExactlyOneInvitee(
                    actual = current.invitees.size,
                ),
            )
            return
        }
        _state.value = current.copy(
            error = null,
            progress = CreateGroupProgress.Validating,
            route = CreateGroupRoute.Creating,
        )

        try {
            val group = createGroup(
                current.effectiveName,
                current.invitees.map { it.inboxPublicKey },
                current.governance.sepGroupType,
            ) { p -> _state.value = _state.value.copy(progress = p) }
            _state.value = _state.value.copy(
                createdGroup = group,
                progress = null,
                route = CreateGroupRoute.Success,
            )
        } catch (e: CreateGroupError) {
            _state.value = _state.value.copy(error = e, progress = null)
            // Stay on Creating to show the error banner; the user
            // can dismiss back to Step2 from there.
        } catch (e: Throwable) {
            _state.value = _state.value.copy(
                error = CreateGroupError.SdkFailure(e.message ?: e.toString()),
                progress = null,
            )
        }
    }

    // ─── Success / reset ──────────────────────────────────────────

    fun tappedDone() {
        reset()
        onClose()
    }

    fun tappedDismissError() {
        _state.value = _state.value.copy(error = null, route = CreateGroupRoute.Step2)
    }

    /**
     * User chose to cancel out of the flow from the error state on
     * the Creating screen. The group may already be saved on disk
     * (the interactor saves before sending invitations) — leaving
     * it intact is fine; a future "retry invites" UI can pick it
     * up. Just close the modal and reset.
     *
     * Mirrors `tappedCancelFromError` from onym-ios PR #27.
     */
    fun cancelFromError() {
        reset()
        onClose()
    }

    private fun reset() {
        _state.value = freshState()
    }

    private companion object {
        /** Build a fresh "Adjective Noun" placeholder + a fresh
         *  initial state. Called at construction + every reset so a
         *  new flow session starts with a different default name. */
        fun freshState(): CreateGroupState {
            val placeholder = generatePlaceholderName()
            return CreateGroupState(
                name = placeholder,
                generatedName = placeholder,
                nameFieldHasBeenFocused = false,
            )
        }

        /** Mirrors `CreateGroupFlow.generatePlaceholderName` from
         *  onym-ios PR #27. Same lexicon, same "Adjective Noun"
         *  shape — keeps the user-facing default consistent across
         *  platforms. */
        fun generatePlaceholderName(): String =
            "${ADJECTIVES.random()} ${NOUNS.random()}"

        private val ADJECTIVES = listOf(
            "Maple", "Quiet", "Sunny", "Brave", "Crimson",
            "Velvet", "Northern", "Golden", "Ember", "Wild",
            "Distant", "Tidal", "Silver", "Twilight", "Amber",
        )
        private val NOUNS = listOf(
            "Garden", "Forest", "Harbor", "Meadow", "Atlas",
            "River", "Cottage", "Lantern", "Compass", "Orchard",
            "Mountain", "Lighthouse", "Plateau", "Valley", "Bay",
        )
    }
}

/**
 * UI-side mirror of the design's three governance cards. Maps to
 * [SepGroupType] for the actual chain call. PR-C only enables
 * [Tyranny] — the other two render with a "Soon" pill and aren't
 * selectable.
 *
 * Mirrors `OnymUIGovernance` from onym-ios PR #26.
 */
enum class OnymUIGovernance(
    val label: String,
    val sub: String,
    val oneLine: String,
    val tooltip: String,
) {
    Tyranny(
        label = "Tyranny",
        sub = "Single admin",
        oneLine = "You control membership and settings.",
        tooltip = "Only the admin can manage this group.",
    ),
    OneOnOne(
        label = "1‑1on‑1",
        sub = "Dialog",
        oneLine = "A private two-person conversation.",
        tooltip = "Exactly two people. No one else can join.",
    ),
    Anarchy(
        label = "Anarchy",
        sub = "Open control",
        oneLine = "Every member has the same control.",
        tooltip = "Anyone can add, remove, or change settings.",
    ),
    ;

    /** All three governance flavours are wired to the chain layer:
     *  Tyranny (`@RunWith` PR #36), OneOnOne (#36/47/48 stack),
     *  Anarchy (#50/51/this-PR stack). Future flavours
     *  (Democracy, Oligarchy) flip on as they wire. */
    val isAvailable: Boolean get() = true

    /** One-line addendum rendered in the Step-2 banner under the
     *  governance label. Type-specific because the implications
     *  differ — Tyranny mentions the admin role, 1-on-1 mentions the
     *  exact-one-invitee constraint. */
    val step2Hint: String
        get() = when (this) {
            Tyranny -> "You'll be the only admin."
            OneOnOne -> "Pick exactly one person."
            Anarchy -> "Everyone shares control."
        }

    val sepGroupType: SepGroupType
        get() = when (this) {
            Tyranny -> SepGroupType.TYRANNY
            OneOnOne -> SepGroupType.ONE_ON_ONE
            Anarchy -> SepGroupType.ANARCHY
        }

    /** Bridge to the chain-layer [chat.onym.android.chain.GovernanceType]
     *  used by [CreateGroupError.NoContractBinding]. */
    val governanceType: chat.onym.android.chain.GovernanceType
        get() = when (this) {
            Tyranny -> chat.onym.android.chain.GovernanceType.Tyranny
            OneOnOne -> chat.onym.android.chain.GovernanceType.OneOnOne
            Anarchy -> chat.onym.android.chain.GovernanceType.Anarchy
        }
}

private val WHITESPACE_REGEX = Regex("\\s+")

/** Lenient hex → bytes. Returns `null` on any non-hex char or odd
 *  length. Package-private so [CreateGroupState] and
 *  [CreateGroupViewModel] can both use it. */
internal fun decodeHex(text: String): ByteArray? {
    val lower = text.lowercase()
    if (lower.length % 2 != 0) return null
    val out = ByteArray(lower.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(lower[i * 2], 16)
        val lo = Character.digit(lower[i * 2 + 1], 16)
        if (hi == -1 || lo == -1) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
