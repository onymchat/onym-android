package app.onym.android.group

import app.onym.android.chain.SepGroupType

/**
 * Where one member stands on a group's rules, decided from what this
 * device holds about them.
 *
 * The same question [JoinRequestApprover.RulesAgreement] answers for a
 * request that hasn't been accepted yet, asked again for a member who
 * is already in. A separate type because the answers differ: a request
 * cannot have been written by the founder, and a member cannot be "an
 * older client we could ask to upgrade" — they are in, and what is left
 * is what the stored bytes do or don't show.
 *
 * Every case is derived by re-verifying, never by reading a flag: the
 * signature is checked against the text stored beside it each time this
 * is asked. A stored boolean would be a claim about a check somebody
 * once ran.
 *
 * Mirrors `GroupRulesStanding` in onym-ios, case for case, so the two
 * platforms describe one member the same way.
 */
enum class GroupRulesStanding {
    /** The group asks nothing of anyone. */
    NO_RULES,

    /**
     * The group has rules, and this kind of group has no way to agree
     * to them: no join request, no approval, nobody to be the author.
     *
     * No group *this app creates* can be in this state today —
     * [SepGroupType.TYRANNY] is the only type the create flow produces.
     * It is not dead code and shouldn't be deleted as such: the
     * interactor can build the other types and the dispatcher
     * materializes whatever group type arrives on the wire, so the day
     * either changes, this is what stands between those groups and
     * marking every member, author included, as having refused to sign.
     */
    NOT_COLLECTED,

    /**
     * This member wrote the rules. Founders don't sign their own terms,
     * and rendering them as "didn't sign" would read as a failure
     * rather than as the shape of the thing.
     */
    AUTHOR,

    /** Verified, against the rules the group holds now. */
    SIGNED,

    /**
     * Verified — but over different words than the group's current
     * rules. They agreed to an earlier version, which is a fact about
     * the group's history rather than about this member.
     */
    SIGNED_EARLIER_VERSION,

    /**
     * Nothing to check: they joined before the group had rules, or
     * through a build that predates them.
     */
    DID_NOT_SIGN,

    /**
     * A stored signature this device cannot check, because it doesn't
     * hold the wording the signature covers.
     *
     * Reachable, and not the same as [DID_NOT_SIGN]: an admitting
     * device records the agreement with the rules as they stood, so a
     * founder who clears the rules between a request and its approval
     * produces exactly this — bytes with nothing to check them against.
     * Reported as "didn't sign" it would claim something, and something
     * false.
     */
    UNKNOWN_RULES,

    /**
     * Bytes that don't verify against the text stored with them. Kept
     * distinct from [DID_NOT_SIGN] because the two say different things
     * about the same member, and only one of them is odd.
     */
    DOES_NOT_VERIFY,
    ;

    /**
     * True only where a signature was actually checked and passed. The
     * mark on a row and the `signed` field in an export both read this
     * rather than each deciding for themselves.
     */
    val isProven: Boolean
        get() = this == SIGNED || this == SIGNED_EARLIER_VERSION

    /**
     * Whether this standing has anything to report about a member.
     *
     * The question a *screen* asks — whether to draw a mark, offer a way
     * in, or write an export — but it belongs to the standing rather
     * than to whatever renders it. On iOS this lived in the
     * presentation type until a review pointed out that a view type was
     * deciding whether plaintext hit the disk.
     */
    val hasSomethingToShow: Boolean
        get() = this != NO_RULES && this != NOT_COLLECTED
}

/**
 * Whether joining this kind of group passes through a request the
 * founder approves — which is the only place an agreement to the rules
 * is ever collected.
 *
 * Spelled out case by case rather than with an `else`: the wire enum
 * carries five types, only one of which has an approval step to carry a
 * signature or an admin to name as the rules' author. When one of the
 * other four grows a joining ceremony, the compiler should make whoever
 * builds it answer this question rather than inherit a silent "no".
 */
val SepGroupType.collectsRulesAgreements: Boolean
    get() = when (this) {
        SepGroupType.TYRANNY -> true
        SepGroupType.ANARCHY,
        SepGroupType.ONE_ON_ONE,
        SepGroupType.DEMOCRACY,
        SepGroupType.OLIGARCHY,
        -> false
    }

/**
 * Where the member stored under [blsHex] stands on this group's rules,
 * or `null` when no member is stored under that key.
 *
 * Takes the key rather than the profile, and looks the profile up here.
 * Accepting both let a caller hand over one member's profile under
 * another's key — and since [ChatGroup.adminPubkeyHex] is compared
 * against that key, the mismatch that mattered was the one that
 * answered "author" for somebody else.
 */
fun ChatGroup.rulesStanding(blsHex: String): GroupRulesStanding? {
    val member = memberProfiles[blsHex] ?: return null
    return rulesStanding(member, blsHex)
}

/**
 * The same answer for a member already in hand.
 *
 * Public so a caller that has just read the profile — building a roster
 * row, or a proof — doesn't look it up twice and give the two lookups a
 * chance to disagree later. The pairing is checked rather than trusted:
 * passing one member's profile under another's key is the mistake the
 * key-only overload exists to prevent, and leaving it merely
 * discouraged here would put it back within reach.
 *
 * The stored bytes are read *before* the group's current state, because
 * they outlive it. `invitationMessage` can change: a founder who clears
 * the rules would otherwise turn every agreement ever made into "this
 * group asks nothing of anyone", deleting the evidence by editing a
 * text field. What somebody signed happened; the group's present
 * wording doesn't get a vote.
 */
fun ChatGroup.rulesStanding(
    member: MemberProfile,
    blsHex: String,
): GroupRulesStanding {
    require(memberProfiles[blsHex] === member) {
        "profile does not belong to $blsHex in this group's roster"
    }
    // One reading of the stored bytes, from the function whose KDoc
    // calls itself "the only place that answers it" — rather than a
    // second `?:` ladder here re-deriving the same four outcomes.
    when (member.storedAgreement(groupIdBytes)) {
        MemberProfile.StoredAgreement.UNCHECKABLE -> return GroupRulesStanding.UNKNOWN_RULES
        MemberProfile.StoredAgreement.NOT_VERIFIED -> return GroupRulesStanding.DOES_NOT_VERIFY
        MemberProfile.StoredAgreement.VERIFIED -> {
            return if (member.rulesText == GroupRules.normalized(invitationMessage)) {
                GroupRulesStanding.SIGNED
            } else {
                GroupRulesStanding.SIGNED_EARLIER_VERSION
            }
        }
        MemberProfile.StoredAgreement.NONE -> Unit
    }
    if (GroupRules.normalized(invitationMessage) == null) return GroupRulesStanding.NO_RULES
    // Asked after the stored bytes, so a signature made under a
    // governance type that later changed still reads honestly.
    if (!groupType.collectsRulesAgreements) return GroupRulesStanding.NOT_COLLECTED
    // `ChatGroup.isAdmin` rather than a fourth hand-rolled comparison —
    // its KDoc exists because the copies on iOS had started to drift.
    if (isAdmin(blsHex)) return GroupRulesStanding.AUTHOR
    return GroupRulesStanding.DID_NOT_SIGN
}
