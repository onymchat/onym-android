package app.onym.android.group

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

/**
 * One member's agreement to a group's rules, as a file that can leave
 * the device and still mean something.
 *
 * ## What makes it a proof
 *
 * Not this app's say-so. The document carries the exact inputs to the
 * check — the rules text, the member's Ed25519 public key, and their
 * signature — so a reader who trusts none of this can recompute
 * [GroupRules.statement] and verify it with any Ed25519 implementation.
 * The `_readme` lines say how, in the file itself, because a proof
 * whose verification procedure lives in a codebase the reader doesn't
 * have is a screenshot with extra steps.
 *
 * ## The rules it carries are the signed ones
 *
 * [rules] is the text stored beside the signature, not whatever the
 * group says today. Those differ exactly when the founder changed the
 * wording after this member joined, and the honest export is the one
 * whose bytes verify — with `matches_current_rules` naming the
 * divergence rather than hiding it, and `current_text` carrying what
 * the group says now so the reader has something to compare.
 *
 * ## What it deliberately doesn't carry
 *
 * No group secret, no member roster, no inbox keys. A proof of
 * agreement should be showable to an outsider — a moderator, a
 * committee, a court — without also handing them the ability to read
 * the group or find the people in it. The sending public key is already
 * public by construction (it verifies every message this member sends)
 * and the signature is meaningless without it.
 *
 * Byte-compatible with `GroupRulesProof` in onym-ios: same keys, same
 * `_readme`, same filename shape, so a proof exported from either
 * platform reads the same and verifies the same.
 */
class GroupRulesProof private constructor(
    val groupIdHex: String,
    val groupName: String,
    val memberAlias: String,
    val memberBlsHex: String,
    val sendingPublicKey: ByteArray?,
    val signature: ByteArray?,
    val rules: String?,
    val currentRules: String?,
    val standing: GroupRulesStanding,
) {
    companion object {
        /**
         * `null` when no member is stored under [blsHex] — a proof
         * about a profile that isn't in this group's roster is a
         * document nobody should be able to produce by accident.
         */
        fun of(group: ChatGroup, blsHex: String): GroupRulesProof? {
            val member = group.memberProfiles[blsHex] ?: return null
            val standing = group.rulesStanding(member, blsHex)
            // The gate lives on the standing, and this is one of the
            // three things its KDoc says it gates — the one that puts
            // plaintext on disk. Building a document for a group that
            // asks nothing of anyone left that decision to whichever
            // screen happened to call this.
            if (!standing.hasSomethingToShow) return null
            val currentRules = GroupRules.normalized(group.invitationMessage)
            val proven = standing.isProven
            return GroupRulesProof(
                // Re-hexed from the bytes signing actually used rather
                // than echoing `group.id`, so the field and the
                // signature can't disagree about spelling. It is not a
                // length guarantee — the parser is lenient — but a
                // short id fails `statement`'s size check, so the
                // standing lands on `DOES_NOT_VERIFY` and no signature
                // is exported beside it.
                groupIdHex = group.groupIdBytes.toHexLowercase(),
                groupName = group.name,
                memberAlias = member.alias,
                memberBlsHex = blsHex,
                sendingPublicKey = if (proven) member.sendingPubkey else null,
                signature = if (proven) member.rulesSignature else null,
                // The author is the one unproven standing with words
                // worth carrying: they are the group's rules, and a
                // document about the person who wrote them containing
                // none of them is useless.
                rules = when {
                    proven -> member.rulesText
                    standing == GroupRulesStanding.AUTHOR -> currentRules
                    else -> null
                },
                currentRules = currentRules,
                standing = standing,
            )
        }

        /**
         * How to check this without trusting the app that wrote it.
         * Split across lines because JSON has no comments and a single
         * 400-column string is not something anyone reads.
         */
        internal val README = listOf(
            "Proof that this member agreed to this group's rules.",
            "To verify, with any Ed25519 implementation:",
            "  1. message = \"onym-group-rules-v1\" (ASCII, 19 bytes)",
            "             || group.id (32 bytes, hex above)",
            "             || SHA-256(rules.text as UTF-8) (32 bytes)",
            "             || member.sending_public_key (32 bytes, hex above)",
            "  2. check member.signature against that message and that key.",
            "The rules text here is the wording this member signed. When",
            "matches_current_rules is false, the group has changed its rules",
            "since — the signature still covers the text in this file, and",
            "current_text carries what the group says now, to compare —",
            "and is absent when the group has no rules at all any more.",
            "",
            "What the signature does NOT cover, and what you must not read",
            "out of it: alias and bls_public_key. Neither is inside the",
            "signed message. The alias is a name this member chose and can",
            "change, and the pairing of that name and that BLS key with this",
            "signature is an assertion by the app that wrote this file — not",
            "something the signature proves. What the signature proves is",
            "that the holder of sending_public_key agreed to these rules for",
            "this group. Tie that key to a person by some other means.",
        )

        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

        /** Fixed, so the same group can't name its file two ways on
         *  two phones: lowercasing under a Turkish locale maps I to a
         *  dotless ı. */
        private val FILE_LOCALE: Locale = Locale.US
    }

    /**
     * A filename someone can find again six months later — and one no
     * two members of a group can share.
     *
     * The readable part is the group and the alias, which is what a
     * person recognises. The key after it is what keeps two members
     * apart, and it is not decoration: aliases are self-asserted and
     * explicitly non-unique, and the ASCII-only stem collapses entirely
     * for a group named in Cyrillic or CJK, so every member of such a
     * group would otherwise land on one name. It also survives leaving
     * this app, where the filename is all the context there is.
     *
     * Both parts are scrubbed and the readable part is clamped. Neither
     * the group name nor the alias is ours: they arrive off the wire
     * with no length cap and no character rules of their own, and this
     * string reaches a filesystem. A roster key of `../../../x` reached
     * outside the export directory before the scrub, and a 400-character
     * alias pushed the name past the filesystem's limit, at which point
     * the write failed and that member's export was broken for good.
     */
    val suggestedFileName: String
        get() {
            val stem = fileSafe("$groupName-$memberAlias")
                .take(READABLE_STEM_MAX)
                .trim('-')
            val named = stem.ifEmpty { "group-rules" }
            // Scrubbed first, then taken — and hashed when scrubbing
            // left too little to tell two members apart. Taking the
            // prefix of a raw key and scrubbing that could collapse a
            // non-hex key to a few characters, or to none, putting back
            // the collision the suffix exists to prevent.
            // Hashed whenever scrubbing changed anything, not merely
            // when it shortened the result past a threshold. Roster
            // keys are arbitrary JSON object keys, so
            // `../../../Documents/xy` and `../../../Documents/xyz`
            // both scrub to something long enough *and* share their
            // first twelve characters — same stem, same suffix, one
            // export overwriting the other. That is the collision this
            // suffix exists to prevent, in the adversarial case the
            // rest of this doc is about.
            val scrubbed = fileSafe(memberBlsHex)
            val key = if (scrubbed == memberBlsHex && scrubbed.length >= KEY_LENGTH) {
                scrubbed.take(KEY_LENGTH)
            } else {
                MessageDigest.getInstance("SHA-256")
                    .digest(memberBlsHex.toByteArray(Charsets.UTF_8))
                    .copyOfRange(0, 6)
                    .toHexLowercase()
            }
            return "onym-rules-proof-$named-$key.json"
        }

    /**
     * The file's bytes: pretty-printed, key-ordered JSON, so two
     * exports of the same agreement are byte-identical and a diff
     * between them means something changed.
     */
    fun json(): String = JSON.encodeToString(document())

    internal fun document(): Document {
        val text = rules
        return Document(
            readme = README,
            group = Document.Group(id = groupIdHex, name = groupName),
            member = Document.Member(
                alias = memberAlias,
                blsPublicKey = memberBlsHex,
                sendingPublicKey = sendingPublicKey?.toHexLowercase(),
                signature = signature?.toHexLowercase(),
                signed = standing.isProven,
                note = noteFor(standing),
            ),
            rules = text?.let {
                // From the standing, not re-derived. `SIGNED` and
                // `SIGNED_EARLIER_VERSION` already *are* this fact, and
                // a type whose thesis is single-sourced derivation
                // shouldn't answer one question twice.
                val matches = standing != GroupRulesStanding.SIGNED_EARLIER_VERSION
                Document.Rules(
                    text = it,
                    sha256 = GroupRules.hash(it).toHexLowercase(),
                    matchesCurrentRules = matches,
                    // Only where it differs, and then in full: a reader
                    // told the wording diverged has nothing to compare
                    // against otherwise, and identical copies would be
                    // the same paragraph twice.
                    currentText = if (matches) null else currentRules,
                )
            },
        )
    }

    private fun noteFor(standing: GroupRulesStanding): String? = when (standing) {
        GroupRulesStanding.SIGNED, GroupRulesStanding.SIGNED_EARLIER_VERSION -> null
        GroupRulesStanding.NO_RULES ->
            "This group has no rules, so nothing was asked of anyone."
        GroupRulesStanding.NOT_COLLECTED ->
            "This kind of group has no join approval, so no agreement to its rules " +
                "is collected from anyone."
        GroupRulesStanding.AUTHOR ->
            "This member wrote the rules; founders do not sign their own."
        GroupRulesStanding.DID_NOT_SIGN ->
            "Joined before this group had rules, or from an app version that " +
                "predates them."
        GroupRulesStanding.UNKNOWN_RULES ->
            "A signature is stored for this member, but the wording it covers is " +
                "not on this device, so nothing here can check it."
        GroupRulesStanding.DOES_NOT_VERIFY ->
            "A signature is stored for this member and it does not verify."
    }

    /**
     * Lowercase ASCII alphanumerics, runs of anything else collapsed to
     * a single dash, ends trimmed.
     *
     * One function for every part of the name, so a component added
     * later can't reintroduce a separator or a `..` by being appended
     * raw — which is how the roster key got in on iOS.
     */
    private fun fileSafe(value: String): String = buildString {
        // Diacritics folded, then ASCII only: folding leaves CJK and
        // Cyrillic untouched, so "is a letter" would let them through
        // into a name that is meant to survive being emailed around.
        val folded = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(FILE_LOCALE)
        for (ch in folded) {
            val keep = ch.code < 128 && (ch.isLetterOrDigit())
            val next = if (keep) ch else '-'
            if (next == '-' && (isEmpty() || last() == '-')) continue
            append(next)
        }
    }.trim('-')

    @Serializable
    internal data class Document(
        @SerialName("_readme") val readme: List<String>,
        val group: Group,
        val member: Member,
        val rules: Rules? = null,
    ) {
        @Serializable
        internal data class Group(val id: String, val name: String)

        @Serializable
        internal data class Member(
            val alias: String,
            @SerialName("bls_public_key") val blsPublicKey: String,
            @SerialName("sending_public_key") val sendingPublicKey: String?,
            val signature: String?,
            val signed: Boolean,
            /** Present only when [signed] is false: why there is
             *  nothing to check, in words rather than a code. */
            val note: String?,
        )

        @Serializable
        internal data class Rules(
            val text: String,
            val sha256: String,
            @SerialName("matches_current_rules") val matchesCurrentRules: Boolean,
            @SerialName("current_text") val currentText: String?,
        )
    }
}

private const val READABLE_STEM_MAX = 60
private const val KEY_LENGTH = 12

private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
    for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
}
