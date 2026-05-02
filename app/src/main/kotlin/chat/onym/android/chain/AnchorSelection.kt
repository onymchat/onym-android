package chat.onym.android.chain

import kotlinx.serialization.Serializable

/**
 * Identifies one selectable contract slot — the `(network,
 * governanceType)` pair the user picks a release for. Used as the
 * map key in [AnchorSelectionStore]; serialised to a deterministic
 * string for the on-disk layout (see
 * [DataStorePreferencesAnchorSelectionStore]).
 */
data class AnchorSelectionKey(
    val network: ContractNetwork,
    val type: GovernanceType,
) {
    /** `"<network>.<type>"` — used as the JSON-encoded preferences
     *  map key. Stable across releases; do not change without a
     *  data migration. */
    fun storageKey(): String = "${network.wireValue}.${type.wireValue}"

    companion object {
        fun fromStorageKey(raw: String): AnchorSelectionKey? {
            val parts = raw.split(".", limit = 2)
            if (parts.size != 2) return null
            val net = ContractNetwork.fromWire(parts[0]) ?: return null
            val type = GovernanceType.fromWire(parts[1]) ?: return null
            return AnchorSelectionKey(network = net, type = type)
        }
    }
}

/**
 * Resolved triple a chat carries forever once it's anchored — the
 * (network, governanceType, contractId) the chat will always update
 * against, plus the release tag the contract came from for display
 * and audit.
 *
 * **Per-chat binding invariant.** When `ChatGroup` lands (separate
 * future PR), it carries an [AnchorBinding] copied at create-time
 * from `ContractsRepository.binding(for = key)`. Settings changes
 * after that never mutate the chat; the chat is the source of
 * truth for "which contract do I update".
 *
 * Two write paths future code MUST respect:
 *
 *  - **Create chat**: read
 *    `contractsRepository.snapshots.value.binding(for = key)` →
 *    store as `chat.anchor`.
 *  - **Update chat**: read `chat.anchor.contractId` +
 *    `chat.anchor.network` directly. Current settings only seed
 *    new chats.
 *
 * `@Serializable` so future Room storage of `ChatGroup` can
 * round-trip the anchor without a custom converter.
 */
@Serializable
data class AnchorBinding(
    val network: ContractNetwork,
    val governanceType: GovernanceType,
    val contractId: String,
    val release: String,
)
