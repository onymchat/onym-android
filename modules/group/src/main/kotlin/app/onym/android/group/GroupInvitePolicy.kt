package app.onym.android.group

import app.onym.android.chain.SepGroupType

/**
 * Whether this member may mint a join invite for this group.
 *
 * Tyranny is the only governance model where an invite can actually be
 * honoured: the contract gates `update_commitment` on the admin's BLS
 * secret, so an invite minted by anybody else surfaces join requests in
 * that member's inbox and then fails to approve them on chain — dead-end
 * UX (PR 94). Anarchy and OneOnOne have no admitting ceremony in V1 at
 * all, hence no invite.
 *
 * Kept out of the screens on purpose: the members roster and the chat
 * thread's top bar both ask this question, and a gate that drifts between
 * two surfaces is worse than one that lives in a single place.
 */
fun canInviteToGroup(groupType: SepGroupType?, isAdmin: Boolean): Boolean =
    groupType == SepGroupType.TYRANNY && isAdmin
