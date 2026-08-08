package app.onym.android.support

import app.onym.android.chain.GetCommitmentPayload
import app.onym.android.chain.SepCommitmentEntry
import app.onym.android.chain.SepContractInvocation
import app.onym.android.chain.SepContractTransport
import app.onym.android.chain.SepSubmissionResponse
import app.onym.android.chain.TyrannyCreateGroupPayload
import app.onym.android.chain.TyrannyUpdateCommitmentPayload
import kotlinx.serialization.KSerializer

/**
 * In-memory stand-in for the SEP contract's on-chain state, shared
 * between the write path (`create_group` / `update_commitment`) and
 * the read path (`get_commitment`) so a Tyranny group anchored by one
 * identity verifies against the exact same `(commitment, epoch)` when
 * another identity materializes it. The Poseidon proof itself stays
 * real FFI — only the relayer/chain round-trip is faked.
 *
 * Mirrors `UITestChainLedger` from onym-ios.
 */
class InMemoryChainLedger {
    private val lock = Any()
    private val entries = mutableMapOf<String, SepCommitmentEntry>()

    fun recordCreate(groupIdHex: String, commitment: ByteArray) = synchronized(lock) {
        entries[groupIdHex] = SepCommitmentEntry(commitment = commitment, epoch = 0uL)
    }

    /** `update_commitment` advances the epoch by one and swaps in the
     *  new commitment (the approver computes `newEpoch = epoch + 1`
     *  locally, so this must match). */
    fun recordUpdate(groupIdHex: String, commitmentNew: ByteArray) = synchronized(lock) {
        val oldEpoch = entries[groupIdHex]?.epoch ?: 0uL
        entries[groupIdHex] = SepCommitmentEntry(commitment = commitmentNew, epoch = oldEpoch + 1uL)
    }

    fun commitment(groupIdHex: String): SepCommitmentEntry? = synchronized(lock) {
        entries[groupIdHex]
    }
}

/**
 * [SepContractTransport] backed by an [InMemoryChainLedger]. Dispatches
 * on the invocation's `function`; the concrete payload/response types
 * are cast directly (the caller — [app.onym.android.chain.SepContractClient] —
 * pins each R).
 */
class LedgerSepContractTransport(
    private val ledger: InMemoryChainLedger,
) : SepContractTransport {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <P, R> invoke(
        invocation: SepContractInvocation<P>,
        invocationSerializer: KSerializer<SepContractInvocation<P>>,
        responseSerializer: KSerializer<R>,
    ): R = when (invocation.function) {
        "create_group" -> {
            when (val p = invocation.payload) {
                is TyrannyCreateGroupPayload -> ledger.recordCreate(hex(p.groupId), p.commitment)
                else -> error("UITest ledger only supports Tyranny create_group")
            }
            SepSubmissionResponse(accepted = true, transactionHash = "uitest-create") as R
        }
        "update_commitment" -> {
            val p = invocation.payload as TyrannyUpdateCommitmentPayload
            // Tyranny update PI is [c_old, epoch_old, c_new, admin_pk, group_id_fr].
            ledger.recordUpdate(hex(p.groupId), p.publicInputs[2])
            SepSubmissionResponse(accepted = true, transactionHash = "uitest-update") as R
        }
        "get_commitment" -> {
            val p = invocation.payload as GetCommitmentPayload
            (ledger.commitment(hex(p.groupId))
                ?: error("UITest ledger: group not anchored")) as R
        }
        else -> error("UITest ledger: unsupported function ${invocation.function}")
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
