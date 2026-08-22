package app.onym.android.group

import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.Normalizer
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
 * divergence rather than hiding it, and `group.current_rules` carrying
 * what the group asks today so the reader can compare.
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
    val sendingPublicKey: ByteArray,
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
            return GroupRulesProof(
                // Re-hexed from the bytes signing actually used rather
                // than echoing `group.id`, so the field and the
                // signature can't disagree about spelling. It is not a
                // length guarantee — the parser is lenient — but a
                // short id fails `statement`'s size check, so the
                // standing lands on `DOES_NOT_VERIFY` — the signature
                // still ships, as every stored one does, with
                // `signed: false` beside it.
                groupIdHex = group.groupIdBytes.toHexLowercase(),
                groupName = group.name,
                memberAlias = member.alias,
                memberBlsHex = blsHex,
                // Carried whenever they exist, not only when they
                // verify. A document that asserts "a signature is
                // stored and it does not verify" while withholding the
                // signature is asking to be taken on faith, in a file
                // whose whole thesis is that it shouldn't be. `signed`
                // and `note` carry this device's verdict; the bytes let
                // a reader reach their own.
                sendingPublicKey = member.sendingPubkey,
                signature = member.rulesSignature,
                // The wording this member put their name to, when
                // there is one: what they signed, or — for the author —
                // what they wrote. Absent for a member who signed
                // nothing, which is why the group's own rules are
                // carried separately below rather than left unstated.
                rules = when {
                    member.rulesText != null -> member.rulesText
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
            "Proof of what this member agreed to in this group's rules.",
            "To verify a signature, with any Ed25519 implementation:",
            "  1. message = \"onym-group-rules-v1\" (ASCII, 19 bytes)",
            "             || group.id (32 bytes, hex above)",
            "             || SHA-256(rules.text as UTF-8) (32 bytes)",
            "             || member.sending_public_key (32 bytes, hex above)",
            "  2. check member.signature against that message and that key.",
            "",
            "How to read the fields, including where they disagree:",
            "  member.signed is this app's verdict, not evidence. A stored",
            "  signature is exported whether or not it verified here, so",
            "  you can repeat the check and disagree; member.note says what",
            "  this device concluded.",
            "  rules.text is the wording this member put their name to. For",
            "  the member who wrote the rules it is their own text and",
            "  signed is false — founders do not sign their own terms.",
            "  rules is absent entirely when this member signed nothing, or",
            "  when the wording their signature covers is not held by the",
            "  device that wrote this file.",
            "  group.current_rules is what the group asks today. Compare it",
            "  against rules.text when matches_current_rules is false. It is",
            "  absent only when the group has no rules at all any more.",
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

        @OptIn(ExperimentalSerializationApi::class)
        private val JSON = Json {
            prettyPrint = true
            // Pinned, not defaulted. kotlinx indents four spaces and
            // Foundation's `.prettyPrinted` two, so leaving it to the
            // library would make the same agreement export differently
            // on the two platforms — for no reason a reader diffing
            // them could ever guess.
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
        }

        /** Fixed, so the same group can't name its file two ways on
         *  two phones: lowercasing under a Turkish locale maps I to a
         *  dotless ı. */
        private val FILE_LOCALE: Locale = Locale.US

        /** Compiled once rather than per call — `fileSafe` runs once
         *  per component of every filename. */
        private val COMBINING_MARKS = Regex("\\p{Mn}+")
    }

    /**
     * A filename someone can find again six months later — and one no
     * two exports can share, across groups as well as within one.
     *
     * The readable part is the group and the alias, which is what a
     * person recognises. The key after it is what keeps two members
     * apart, and it is not decoration: aliases are self-asserted and
     * explicitly non-unique, and the ASCII-only stem collapses entirely
     * for a group named in Cyrillic or CJK, so every member of such a
     * group would otherwise land on one name. It also survives leaving
     * this app, where the filename is all the context there is.
     *
     * The readable part is scrubbed and clamped, and the key after it
     * is a digest, so nothing off the wire lands in the name as it
     * arrived. Neither the group name nor the alias is ours: they
     * arrive off the wire with no length cap and no character rules of
     * their own, and this string reaches a filesystem. A roster key of
     * `../../../x` reached outside the export directory before either,
     * and a 400-character alias pushed the name past the filesystem's
     * limit, at which point the write failed and that member's export
     * was broken for good.
     */
    val suggestedFileName: String
        get() {
            // Scrubbed per part, then joined. Scrubbing the joined
            // string works today only because the separator survives
            // its own scrub; the invariant that holds regardless is
            // that nothing reaches this template un-scrubbed, and the
            // next component appended here is the one that would
            // otherwise carry a `..` in.
            val stem = "${fileSafe(groupName)}-${fileSafe(memberAlias)}"
                .take(READABLE_STEM_MAX)
                .trim('-')
            val named = stem.ifEmpty { "group-rules" }
            // Hashed, always. Scrubbing is not what makes a key
            // ambiguous: roster keys are unvalidated JSON object keys
            // off the wire, so two that are already lowercase hex and
            // share their first twelve characters take the same prefix
            // and, with equal aliases, produce the same filename — one
            // export overwriting the other. That is attacker-chosen,
            // not a birthday argument, so the suffix is a digest of the
            // whole key rather than a slice of it.
            //
            // The cost is that the suffix no longer echoes the
            // fingerprint on screen. The document carries
            // `bls_public_key` in full, which is where an exact
            // comparison belongs anyway.
            //
            // Over the group *and* the member, because the collision a
            // Downloads folder sees is not the one a roster sees. The
            // readable part is the first thing to collapse — two groups
            // named in CJK both scrub to nothing, two named alike share
            // their first sixty characters — and with the same person
            // in both, a key-only digest names both files identically
            // and the second export overwrites the first. `groupIdHex`
            // is in hand, is always hex, and is already in the
            // document.
            val key = MessageDigest.getInstance("SHA-256")
                .digest((groupIdHex + memberBlsHex).toByteArray(Charsets.UTF_8))
                .copyOfRange(0, 6)
                .toHexLowercase()
            return "onym-rules-proof-$named-$key.json"
        }

    /**
     * The file's bytes: pretty-printed, fields in declaration order,
     * so two exports of the same agreement are byte-identical and a
     * diff between them means something changed.
     */
    fun json(): String = JSON.encodeToString(document())

    internal fun document(): Document {
        val text = rules
        return Document(
            readme = README,
            group = Document.Group(
                id = groupIdHex,
                name = groupName,
                // What the group asks *now*, whenever it asks anything.
                // Without it a document about a member who signed
                // nothing never stated what was on the table — and one
                // about a member who signed an older wording gave the
                // reader nothing to compare against.
                currentRules = currentRules,
            ),
            member = Document.Member(
                alias = memberAlias,
                blsPublicKey = memberBlsHex,
                sendingPublicKey = sendingPublicKey.toHexLowercase(),
                signature = signature?.toHexLowercase(),
                signed = standing.isProven,
                note = noteFor(standing),
            ),
            rules = text?.let {
                // Compared, not inferred from the standing. The
                // standing answers this for the two verified cases and
                // not for the third: a stored text can diverge under
                // `DOES_NOT_VERIFY` too — `MemberProfile` requires it
                // to hash to `rulesHash`, not that the signature holds
                // — so inferring "matches" there told the reader this
                // *is* what the group asks today when it isn't. Still
                // one source: the two values this document carries.
                val matches = it == currentRules
                Document.Rules(
                    text = it,
                    sha256 = GroupRules.hash(it).toHexLowercase(),
                    matchesCurrentRules = matches,
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
            "A signature is stored for this member and it does not verify here. " +
                "Both it and the wording it covers are in this file; repeat the check."
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
        val folded = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
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
        internal data class Group(
            val id: String,
            val name: String,
            @SerialName("current_rules") val currentRules: String?,
        )

        @Serializable
        internal data class Member(
            val alias: String,
            @SerialName("bls_public_key") val blsPublicKey: String,
            @SerialName("sending_public_key") val sendingPublicKey: String,
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
        )
    }
}

private const val READABLE_STEM_MAX = 60

private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
    for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
}
