package app.onym.android.group

import app.onym.android.chain.SepGroupType

/**
 * Seam for appending a locally-minted membership notice to a group's
 * chat thread.
 *
 * Exists because the dependency graph runs `:chats-core → :group`, so
 * [JoinRequestApprover] (here) cannot reach `MessageRepository` directly
 * without a cycle. The production implementation is
 * `ChatSystemEventRecorder` in `:chats-core`, which is also what
 * `IncomingMessageDispatcher` uses directly — so every system row in the
 * app is minted by exactly one piece of code, with one id-derivation
 * rule.
 *
 * Defaulted to [NoopGroupSystemEventRecorder] at the injection site so
 * the existing test constructions of [JoinRequestApprover] don't have to
 * thread a recorder they never assert on.
 *
 * Mirrors `GroupSystemEventRecording` in onym-ios.
 */
interface GroupSystemEventRecording {
    /**
     * Append "<alias> joined" to [groupId]'s thread as seen by
     * [ownerIdentityId]. Idempotent on
     * `(groupId, ownerIdentityId, joinerBlsPubkeyHex)` — a caller that
     * fires twice for the same joiner produces one row.
     */
    suspend fun recordMemberJoined(
        groupId: String,
        ownerIdentityId: String,
        groupType: SepGroupType,
        joinerBlsPubkeyHex: String,
        alias: String,
        atMillis: Long,
    )
}

/**
 * Drops every notice. Test/default seam, mirroring the no-op
 * conformers already used for the transport and refresh seams.
 */
object NoopGroupSystemEventRecorder : GroupSystemEventRecording {
    override suspend fun recordMemberJoined(
        groupId: String,
        ownerIdentityId: String,
        groupType: SepGroupType,
        joinerBlsPubkeyHex: String,
        alias: String,
        atMillis: Long,
    ) = Unit
}
