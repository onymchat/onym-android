package app.onym.android.group

import app.onym.android.chain.AnchorSelectionKey
import app.onym.android.chain.ContractsRepository
import app.onym.android.chain.GovernanceType
import app.onym.android.chain.GroupProofGenerator
import app.onym.android.chain.GroupProofGeneratorError
import app.onym.android.chain.GroupProofUpdateInput
import app.onym.android.chain.NetworkPreferenceProvider
import app.onym.android.chain.OkHttpSepContractTransport
import app.onym.android.chain.OnymGroupProofGenerator
import app.onym.android.chain.RelayerRepository
import app.onym.android.chain.SepContractClient
import app.onym.android.chain.SepContractError
import app.onym.android.chain.SepContractTransport
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.chain.TyrannyUpdateCommitmentPayload
import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Test seam consumed by [app.onym.android.chats.ChatsViewModel]. The
 * production conformer is [GroupMemberRemover]; tests inject a stub
 * instead of standing up the prover + relayer stack just to exercise
 * the VM's bookkeeping. Mirrors [JoinRequestApproving].
 */
interface GroupMemberRemoving {
    suspend fun remove(groupId: String, victimBlsHex: String): GroupMemberRemover.Outcome

    /**
     * In-flight removals as `"groupId:victimBlsHex"` keys, and the
     * latest failure per group id. Both live HERE rather than in a
     * ViewModel because the members screen's VM is scoped to its
     * `NavBackStackEntry`: back-nav or a rotation would otherwise take
     * the row spinner and the error dialog with it, and a failed
     * removal could vanish without ever being shown.
     */
    val removalsInFlight: StateFlow<Set<String>>
    val removalErrors: StateFlow<Map<String, GroupMemberRemover.Outcome>>

    /**
     * Fire-and-forget [remove] on an application-lifetime scope, with
     * [removalsInFlight] / [removalErrors] bookkeeping. Must NOT run on
     * a screen-scoped coroutine: the anchor→persist span is seconds
     * long (PLONK prove + relayer round-trip) and cancelling between
     * the relayer accepting the anchor and the local write would leave
     * the chain ahead of local state — after which every subsequent
     * `proveUpdate` proves against a stale `epochOld` and is rejected,
     * bricking joins AND removals for that group with nobody notified.
     */
    fun removeAsync(groupId: String, victimBlsHex: String)

    fun clearRemovalError(groupId: String)
}

/**
 * Admin-side "remove member from a Tyranny group" flow, with
 * groupSecret rotation. Ordering is anchor → persist → fan out
 * (mirrors [JoinRequestApprover.approve]):
 *
 *  1. Shrink the roster, prove the Tyranny `update_commitment` over
 *     the new Merkle root, submit to the relayer. Epoch bumps, salt
 *     rotates, and — unlike a join — a **fresh random groupSecret**
 *     is generated so the removed member holds no post-removal
 *     member-only secret.
 *  2. Persist the advanced local state (roster minus victim, victim's
 *     [MemberProfile] tombstoned via `revoked = true` — never deleted,
 *     so past-message rendering and dedup keep working).
 *  3. Fan a [MemberRemovalPayload] out per recipient, best-effort:
 *     remaining members receive the variant carrying
 *     `group_secret_new` + `salt_new`; the victim receives the
 *     secret-free variant (they learn the fact, never the secrets).
 *
 * The fanout is deliberately OFF the critical path: once the chain
 * anchor lands and local state persists, the removal is real — a
 * member that misses the payload converges later via inbox replay.
 * (stellar-mls postmortem lesson: never block the security-critical
 * leg on courtesy delivery.)
 *
 * [mutex] must be the SAME instance [JoinRequestApprover] holds —
 * both flows are read-prove-anchor-persist on the same group and an
 * interleaving would prove against a stale epoch.
 *
 * Mirrors `removeMember` on `JoinRequestApprover` in onym-ios (which
 * joins the approver's `approvalChain` for the same reason).
 */
class GroupMemberRemover(
    private val activeIdentity: ActiveIdentityProvider,
    private val envelopeSealer: InvitationEnvelopeSealer,
    /** Reads the active identity's BLS secret for the update proof.
     *  Injectable closure (production:
     *  `identityRepository::blsSecretKey`) so JVM tests don't stand
     *  up the whole keychain stack. */
    private val blsSecretKey: suspend () -> ByteArray,
    private val groupRepository: GroupRepository,
    private val inboxTransport: InboxTransport,
    private val relayers: RelayerRepository?,
    private val contracts: ContractsRepository?,
    private val networkPreference: NetworkPreferenceProvider?,
    private val proofGenerator: GroupProofGenerator = OnymGroupProofGenerator(),
    private val makeContractTransport: (String) -> SepContractTransport = { url ->
        OkHttpSepContractTransport(httpClient = OkHttpClient(), endpointUrl = url)
    },
    /** Shared with [JoinRequestApprover] in production — see class KDoc. */
    private val mutex: Mutex = Mutex(),
    /** Test seam for the rotated groupSecret. Default matches
     *  `CreateGroupInteractor.randomBytes`. */
    private val randomBytes: (Int) -> ByteArray = { count ->
        ByteArray(count).also { java.security.SecureRandom().nextBytes(it) }
    },
    /** Test seams for the JNI-backed commitment math — the OnymSDK
     *  `.so` only loads on-device, so JVM unit tests inject pure-Kotlin
     *  stand-ins (same reason `GroupProofGenerator` is an interface). */
    private val computeMerkleRoot: (List<GovernanceMember>, SepTier) -> ByteArray =
        GroupCommitmentBuilder::computeMerkleRoot,
    private val computePublicKey: (ByteArray) -> ByteArray =
        GroupCommitmentBuilder::computePublicKey,
    /** Application-lifetime scope for [removeAsync]. Screen-scoped
     *  scopes are unsafe here — see [GroupMemberRemoving.removeAsync]. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : GroupMemberRemoving {

    private val _removalsInFlight = MutableStateFlow<Set<String>>(emptySet())
    override val removalsInFlight: StateFlow<Set<String>> = _removalsInFlight.asStateFlow()

    private val _removalErrors = MutableStateFlow<Map<String, Outcome>>(emptyMap())
    override val removalErrors: StateFlow<Map<String, Outcome>> = _removalErrors.asStateFlow()

    override fun removeAsync(groupId: String, victimBlsHex: String) {
        val key = removalKey(groupId, victimBlsHex)
        if (key in _removalsInFlight.value) return
        _removalsInFlight.value = _removalsInFlight.value + key
        scope.launch {
            try {
                val outcome = remove(groupId, victimBlsHex)
                val groupKey = groupId.lowercase()
                _removalErrors.value = if (outcome is Outcome.Sent) {
                    _removalErrors.value - groupKey
                } else {
                    _removalErrors.value + (groupKey to outcome)
                }
            } finally {
                _removalsInFlight.value = _removalsInFlight.value - key
            }
        }
    }

    override fun clearRemovalError(groupId: String) {
        _removalErrors.value = _removalErrors.value - groupId.lowercase()
    }
    sealed class Outcome {
        /** Anchored on chain, persisted locally, fanned out (best-effort). */
        object Sent : Outcome()

        /** No group with this id under the active identity. */
        object UnknownGroup : Outcome()

        /** No identity selected. */
        object NoIdentityLoaded : Outcome()

        /** Removal only exists for Tyranny groups. */
        object NotTyrannyGroup : Outcome()

        /** Active identity isn't this group's admin. */
        object NotAdminOfThisGroup : Outcome()

        /** The admin can't remove themself. */
        object CannotRemoveSelf : Outcome()

        /** BLS hex not present in [ChatGroup.memberProfiles]. */
        object UnknownMember : Outcome()

        /** The member's profile is already tombstoned. */
        object AlreadyRemoved : Outcome()

        /** Present in the app-level directory but missing from the
         *  on-chain [ChatGroup.members] roster — the tree can't be
         *  shrunk around a leaf that was never in it. (Directory and
         *  roster are allowed to diverge; removal is the first flow
         *  that needs them to agree.) */
        object MemberNotInRoster : Outcome()

        /** [RelayerRepository.selectUrl] returned null. */
        object NoActiveRelayer : Outcome()

        /** No deployed Tyranny contract for the active network. */
        object NoContractBinding : Outcome()

        /** `Tyranny.proveUpdate` failed. */
        class ProofFailed(val reason: String) : Outcome()

        /** Relayer accepted the POST but the contract rejected. */
        class AnchorRejected(val reason: String) : Outcome()

        /** Local/transport failure outside the prove+anchor leg. */
        class TransportFailed(val reason: String) : Outcome()
    }

    /**
     * Remove [victimBlsHex] (lowercase 96-char BLS pubkey hex) from
     * [groupId]. See class KDoc for the three-step shape.
     */
    override suspend fun remove(groupId: String, victimBlsHex: String): Outcome = mutex.withLock {
        val victimHex = victimBlsHex.lowercase()
        val group = groupRepository.snapshots.value
            .firstOrNull { it.id.equals(groupId, ignoreCase = true) }
            ?: return@withLock Outcome.UnknownGroup
        activeIdentity.currentIdentityId.value ?: return@withLock Outcome.NoIdentityLoaded

        if (group.groupType != SepGroupType.TYRANNY) return@withLock Outcome.NotTyrannyGroup
        val adminPubkeyHex = group.adminPubkeyHex?.lowercase()
            ?: return@withLock Outcome.NotAdminOfThisGroup
        if (victimHex == adminPubkeyHex) return@withLock Outcome.CannotRemoveSelf

        val victimProfile = group.memberProfiles[victimHex]
            ?: return@withLock Outcome.UnknownMember
        if (victimProfile.revoked) return@withLock Outcome.AlreadyRemoved

        val victimBytes = ChatGroup.bytesFromHex(victimHex)
        val victimMember = group.members.firstOrNull {
            it.publicKeyCompressed.contentEquals(victimBytes)
        } ?: return@withLock Outcome.MemberNotInRoster

        // ─── chain-anchor leg (mirrors anchorTyrannyJoin) ───────────
        val relayerUrl = relayers?.selectUrl()
            ?: return@withLock Outcome.NoActiveRelayer
        val networkPref = networkPreference?.current()
            ?: return@withLock Outcome.NoContractBinding
        val binding = contracts?.snapshots?.value?.binding(
            AnchorSelectionKey(
                network = networkPref.contractNetwork,
                type = GovernanceType.Tyranny,
            ),
        ) ?: return@withLock Outcome.NoContractBinding

        val adminBytes = ChatGroup.bytesFromHex(adminPubkeyHex)
        val adminIndexOld = group.members.indexOfFirst {
            it.publicKeyCompressed.contentEquals(adminBytes)
        }
        if (adminIndexOld < 0) {
            return@withLock Outcome.TransportFailed("admin not in members roster")
        }

        // Removal preserves the existing lex order — filtering can't
        // reorder an already-sorted roster.
        val newMembers = group.members.filterNot {
            it.publicKeyCompressed.contentEquals(victimMember.publicKeyCompressed)
        }
        val memberRootNew = try {
            computeMerkleRoot(newMembers, group.tier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withLock Outcome.ProofFailed("merkle_root: ${e.message ?: e}")
        }
        val saltNew = GroupCommitmentBuilder.generateSalt()
        val groupSecretNew = randomBytes(32)

        val blsSecret = try {
            // onym:allow-secret-read
            blsSecretKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withLock Outcome.TransportFailed("bls_secret: ${e.message ?: e}")
        }

        // Same pre-flight as the approver (PR 93): confirm the active
        // identity actually IS the admin before handing the secret to
        // the prover — catches "user switched identities" cleanly.
        val activePubFromSecret = try {
            computePublicKey(blsSecret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withLock Outcome.TransportFailed(
                "derive_pub: ${e.message ?: e::class.simpleName}",
            )
        }
        if (!activePubFromSecret.contentEquals(group.members[adminIndexOld].publicKeyCompressed)) {
            return@withLock Outcome.NotAdminOfThisGroup
        }

        val proof = try {
            proofGenerator.proveUpdate(
                GroupProofUpdateInput(
                    groupType = SepGroupType.TYRANNY,
                    tier = group.tier,
                    oldMembers = group.members,
                    adminBlsSecretKey = blsSecret,
                    adminIndexOld = adminIndexOld,
                    epochOld = group.epoch,
                    memberRootNew = memberRootNew,
                    groupId = group.groupIdBytes,
                    saltOld = group.salt,
                    saltNew = saltNew,
                ),
            )
        } catch (e: GroupProofGeneratorError) {
            return@withLock Outcome.ProofFailed(e.message ?: e.javaClass.simpleName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withLock Outcome.ProofFailed(e.message ?: e.toString())
        }

        val client = SepContractClient(
            contractID = binding.contractId,
            contractType = SepGroupType.TYRANNY,
            network = networkPref.sepNetwork,
            transport = makeContractTransport(relayerUrl),
        )

        // ─── anchor → persist → fan out: NON-CANCELLABLE ────────────
        // Once the relayer accepts, the chain epoch HAS moved. If the
        // local write were skipped — a cancelled screen-scoped caller,
        // or a `catch (Throwable)` swallowing CancellationException —
        // local state would sit behind the chain forever, and every
        // later proveUpdate would prove against a stale `epochOld` and
        // be rejected: no more joins AND no more removals for this
        // group, with no member ever learning why. So the POST and the
        // write share one NonCancellable span; the best-effort fanout
        // rides along so in-flight sends aren't torn down either.
        withContext(NonCancellable) {
            val response = try {
                client.updateCommitmentTyranny(
                    TyrannyUpdateCommitmentPayload(
                        groupId = group.groupIdBytes,
                        proof = proof.proof,
                        publicInputs = proof.publicInputs,
                    ),
                )
            } catch (e: SepContractError) {
                return@withContext Outcome.TransportFailed("anchor: ${e.message ?: e}")
            } catch (e: Throwable) {
                return@withContext Outcome.TransportFailed("anchor: ${e.message ?: e}")
            }
            if (!response.accepted) {
                return@withContext Outcome.AnchorRejected(response.message ?: "(no message)")
            }

            // The anchor landed: the removal is now the on-chain truth.
            // Persist BEFORE fanning out so a crash mid-fanout can't
            // lose the transition (peers converge via inbox replay).
            val anchored = group.copy(
                members = newMembers,
                commitment = proof.commitmentNew,
                epoch = group.epoch + 1uL,
                salt = saltNew,
                groupSecret = groupSecretNew,
                memberProfiles = group.memberProfiles +
                    (victimHex to victimProfile.copy(
                        revoked = true,
                        // Removal epoch — lets receive-side tombstone
                        // decisions stay order-independent under relay
                        // replay (MemberProfile.statusEpoch).
                        statusEpoch = group.epoch + 1uL,
                    )),
            )
            groupRepository.insert(anchored)

            broadcastRemoval(
                group = anchored,
                victimHex = victimHex,
                victimProfile = victimProfile,
                adminHex = adminPubkeyHex,
                groupSecretNew = groupSecretNew,
                saltNew = saltNew,
            )
            Outcome.Sent
        }
    }

    /**
     * One sealed envelope per recipient, sequential, failures
     * swallowed. The victim's copy is encoded WITHOUT the rotated
     * secrets — build both wire bodies once, outside the loop.
     *
     * ## Recovery path for a missed envelope
     *
     * If a recipient's send fails here (no relay accepted it), inbox
     * replay can NOT redeliver — the envelope never reached a relay.
     * That member's local state stays on the pre-removal epoch +
     * groupSecret until something triggers a
     * [app.onym.android.group.GroupStateRefreshRequest] toward the
     * admin (whose reply carries the full current snapshot). They
     * remain SAFE in the meantime — the removal is already anchored
     * and the victim can't read anything new — but they'll keep the
     * victim in their local roster and keep sealing messages to the
     * victim's inbox until they converge. A proactive resend queue is
     * a known follow-up.
     */
    private suspend fun broadcastRemoval(
        group: ChatGroup,
        victimHex: String,
        victimProfile: MemberProfile,
        adminHex: String,
        groupSecretNew: ByteArray,
        saltNew: ByteArray,
    ) {
        val commitment = group.commitment ?: return
        val sentAt = System.currentTimeMillis()
        val memberBytes: ByteArray
        val victimBytes: ByteArray
        try {
            val memberPayload = MemberRemovalPayload(
                version = 1,
                groupId = group.groupIdBytes,
                removedBlsHex = victimHex,
                commitment = commitment,
                epoch = group.epoch,
                sentAtMillis = sentAt,
                groupSecretNew = groupSecretNew,
                saltNew = saltNew,
            )
            val victimPayload = memberPayload.copy(groupSecretNew = null, saltNew = null)
            memberBytes = jsonFormat
                .encodeToString(MemberRemovalPayload.serializer(), memberPayload)
                .toByteArray(Charsets.UTF_8)
            victimBytes = jsonFormat
                .encodeToString(MemberRemovalPayload.serializer(), victimPayload)
                .toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            // Encode failures can't happen for sizes the constructor
            // already validated — but skipping fanout beats crashing
            // after the anchor landed.
            return
        }

        for ((memberKey, profile) in group.memberProfiles) {
            if (memberKey == adminHex) continue // self
            val body = if (memberKey == victimHex) {
                victimBytes
            } else {
                if (profile.revoked) continue // earlier tombstones
                memberBytes
            }
            val inboxKey = if (memberKey == victimHex) {
                victimProfile.inboxPublicKey
            } else {
                profile.inboxPublicKey
            }
            val sealed = try {
                envelopeSealer.sealInvitation(body, inboxKey)
            } catch (_: Throwable) {
                continue
            }
            val tag = TransportInboxId(IdentityRepository.inboxTag(inboxKey))
            runCatching { inboxTransport.send(sealed, tag) }
        }
    }

    companion object {
        /** `encodeDefaults = false` so the victim's copy omits the
         *  `group_secret_new` / `salt_new` keys entirely (matches the
         *  [GroupAvatarBroadcaster] null-omission convention). */
        private val jsonFormat = Json { encodeDefaults = false; ignoreUnknownKeys = true }

        /** Stable key for one (group, member) removal — the unit of
         *  [GroupMemberRemoving.removalsInFlight]. */
        fun removalKey(groupId: String, victimBlsHex: String): String =
            "${groupId.lowercase()}:${victimBlsHex.lowercase()}"
    }
}
