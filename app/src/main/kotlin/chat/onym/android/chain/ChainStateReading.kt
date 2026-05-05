package chat.onym.android.chain

import okhttp3.OkHttpClient

/**
 * Receive-side seam for live chain reads. PR 89's
 * `verifyTyrannyInvitation` / `verifyTyrannyAnnouncement` use this
 * to fetch the on-chain `(commitment, epoch)` and reject any payload
 * whose claimed values don't match.
 *
 * V1 is Tyranny-only — that's where the admin-anchored update flow
 * exists. Non-Tyranny groups skip chain verification entirely (no
 * admin-driven update path).
 *
 * Mirrors `ChainStateReading.swift` from onym-ios PR #89.
 */
interface ChainStateReading {
    /** Fetch the current on-chain [SepCommitmentEntry] for a Tyranny
     *  group. Throws on transport / decode failures or when the
     *  active relayer / Tyranny contract isn't configured. Receivers
     *  treat any throw as "couldn't verify, reject" — never as
     *  "verification passed". */
    suspend fun tyrannyCommitment(groupId: ByteArray): SepCommitmentEntry
}

sealed class ChainReadError(message: String) : Throwable(message) {
    object NoActiveRelayer : ChainReadError("no active chain relayer configured") {
        private fun readResolve(): Any = NoActiveRelayer
    }
    object NoContractBinding : ChainReadError("no Tyranny contract selected for this network") {
        private fun readResolve(): Any = NoContractBinding
    }
}

/**
 * Production [ChainStateReading] — resolves the active relayer +
 * Tyranny contract binding per call, then delegates to
 * [SepContractClient.getCommitment].
 *
 * Mirrors `SEPContractChainStateReader` from onym-ios PR #89.
 */
class SepContractChainStateReader(
    private val relayers: RelayerRepository,
    private val contracts: ContractsRepository,
    private val networkPreference: NetworkPreferenceProvider,
    private val makeContractTransport: (String) -> SepContractTransport = { url ->
        OkHttpSepContractTransport(httpClient = OkHttpClient(), endpointUrl = url)
    },
) : ChainStateReading {
    override suspend fun tyrannyCommitment(groupId: ByteArray): SepCommitmentEntry {
        val relayerUrl = relayers.selectUrl() ?: throw ChainReadError.NoActiveRelayer
        val activeNetwork = networkPreference.current()
        val key = AnchorSelectionKey(
            network = activeNetwork.contractNetwork,
            type = GovernanceType.Tyranny,
        )
        val binding = contracts.snapshots.value.binding(key)
            ?: throw ChainReadError.NoContractBinding
        val transport = makeContractTransport(relayerUrl)
        val client = SepContractClient(
            contractID = binding.contractId,
            contractType = SepGroupType.TYRANNY,
            network = activeNetwork.sepNetwork,
            transport = transport,
        )
        return client.getCommitment(groupId)
    }
}
