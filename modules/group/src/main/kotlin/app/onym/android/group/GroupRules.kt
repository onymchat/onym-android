package app.onym.android.group

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

/**
 * The rules a founder sets for a group, and the joiner's signed
 * agreement to them.
 *
 * ## Why a signature at all
 *
 * A founder admitting a stranger is deciding on two things: who is
 * asking, and what they have agreed to. The first was already provable —
 * the request carries the joiner's long-term keys. The second was not,
 * and could not be inferred from the envelope: the sealed envelope's
 * Ed25519 signature covers the *ephemeral public key* only, not the
 * payload under it. A founder holding such an envelope can produce a
 * different plaintext for the same signature, so it proves nothing to a
 * third party about what the joiner said.
 *
 * So agreement gets its own detached signature, over bytes that name
 * what is being agreed to and by whom.
 *
 * ## The statement
 *
 *     "onym-group-rules-v1" ‖ group_id (32) ‖ SHA256(rules) (32)
 *                           ‖ joiner_sending_pub (32)
 *
 * Every component after the domain string is fixed-length, so the
 * concatenation is unambiguous without length prefixes.
 *
 *  - The **domain string** keeps this signature from being replayable as
 *    any other signature this identity produces — the same Ed25519 key
 *    signs moderation mandates and report disclosures.
 *  - **`group_id`** binds the agreement to one group. Without it, an
 *    acceptance collected for a permissive group could be shown as
 *    agreement to a stricter group's rules that happen to be equal.
 *  - **`SHA256(rules)`** is what makes it an agreement to *these* rules
 *    rather than to the idea of rules. Changing a comma invalidates it.
 *  - **`joiner_sending_pub`** names the signer inside the signed bytes,
 *    so a signature lifted from one request cannot be re-attributed to
 *    another joiner.
 *
 * Deliberately **not** signed: a timestamp. A joiner-supplied one is
 * unverifiable, and the only trustworthy time is when the founder's
 * device received the request — which the founder records itself.
 *
 * ## Keeping the proof
 *
 * A signature is only evidence if the bytes it covers can be produced
 * again. The verifier therefore stores the exact rules text alongside
 * the signature; a stored hash with no text proves that *something* was
 * agreed to and never what.
 *
 * Mirrors `GroupRules` in onym-ios — same domain string, same field
 * order, same canonical form, same byte cap, so a signature made on
 * either platform verifies on the other.
 */
object GroupRules {
    /**
     * The most rules an invite may carry, in **UTF-8 bytes**.
     *
     * Bytes, not characters, for two reasons that both bite.
     *
     * A character cap doesn't bound the payload it exists to bound. A
     * ZWJ family emoji is one grapheme cluster, two UTF-16 units per
     * component, and 25 UTF-8 bytes, so 500 of them is a legal
     * 500-"character" rules text and a ~12.5 KB payload — many times the
     * ceiling below.
     *
     * And a character cap isn't the same cap on both platforms. Swift's
     * `String.count` counts grapheme clusters; Kotlin's `String.length`
     * counts UTF-16 units. One non-BMP character makes them disagree,
     * and because decode *rejects* an over-long value rather than
     * truncating it, disagreement is an unusable link rather than a
     * cosmetic difference. UTF-8 byte count is the only unit the two
     * compute identically.
     *
     * The value: a QR code at correction level M holds 2331 bytes, and
     * the link is `base64(JSON)`, so the rules get ~1500 bytes of it.
     * Measured end to end, with both 32-byte keys and a 30-character
     * Latin group name:
     *
     *     1500 bytes as 1500 ASCII characters  →  2273-byte link
     *     1500 bytes as  750 Cyrillic chars    →  2273-byte link
     *     1500 bytes as  500 CJK characters    →  2273-byte link
     *
     * — the same link length, which is the point of measuring the budget
     * in the unit the wire actually spends.
     *
     * The remaining headroom is shared with the group name, which has no
     * cap of its own: 1500 bytes of rules alongside a 30-character CJK
     * name overruns. The failure is soft — the share screen falls back to
     * a copyable link with no QR — but it is why this number should not
     * be raised without re-measuring the pair. `GroupRulesWireTest` pins
     * both sides of that boundary, against the encoder that ships.
     */
    const val MAX_BYTES = 1500

    /**
     * What the compose field counts down from, in characters. A guide
     * for the person typing, not the limit that decides whether a link
     * is valid — [MAX_BYTES] is. Latin text hits neither before the
     * other; scripts that cost more per character hit the byte cap
     * first, which is correct and is why the field checks both.
     */
    const val MAX_LENGTH = 500

    /** Domain separator. Versioned: a change to the statement's shape
     *  must not let old signatures verify under the new reading. */
    internal const val DOMAIN = "onym-group-rules-v1"

    /**
     * Exactly the codepoints [canonical] trims from the ends, pinned
     * rather than named.
     *
     * Kotlin's `String.trim()` and Foundation's
     * `CharacterSet.whitespacesAndNewlines` are not the same set, in
     * both directions. `Char.isWhitespace` keeps U+0085 (NEL), which
     * Foundation trims, and trims the file/group/record/unit separators
     * (U+001C-U+001F), which Foundation keeps. Either difference is one
     * platform hashing a codepoint the other stripped — a genuine
     * agreement that fails to verify, which the founder would read as a
     * signature that doesn't check out rather than as a whitespace
     * disagreement.
     *
     * So the set is written out, and it is the same one iOS writes out:
     * the codepoints of `CharacterSet.whitespacesAndNewlines`. Spelling
     * them here also stops a Kotlin or Foundation revision from silently
     * changing what a signature covers.
     */
    val TRIMMED_CODEPOINTS: Set<Char> = setOf(
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u0085', '\u00A0',
        '\u1680', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006',
        '\u2007', '\u2008', '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F',
        '\u3000',
    )

    /**
     * The one canonical form, applied by *both* sides before hashing.
     *
     * Ends-only trimming, nothing else: the founder wrote this text and
     * a joiner is agreeing to it, so collapsing inner whitespace or
     * normalising case would mean signing something other than what was
     * displayed. Trailing newlines from a text field are the one
     * difference that carries no meaning, and letting them through would
     * make agreement fail on an invisible character.
     *
     * See [TRIMMED_CODEPOINTS] for why the set is spelled out.
     */
    fun canonical(rules: String): String = rules.trim { it in TRIMMED_CODEPOINTS }

    /** Whether this text fits an invite, in the unit the wire spends. */
    fun fits(rules: String): Boolean =
        canonical(rules).toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    /** Null for rules that are absent or blank — a group with no rules
     *  asks for no agreement, and an empty string must not become a
     *  thing to sign. */
    fun normalized(rules: String?): String? {
        val canonical = rules?.let(::canonical) ?: return null
        return canonical.ifEmpty { null }
    }

    /** SHA-256 over the canonical text's UTF-8. The identity of a set of
     *  rules, and what the founder compares against. */
    fun hash(rules: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical(rules).toByteArray(Charsets.UTF_8))

    /**
     * The exact bytes both sides sign and verify. See the type doc.
     *
     * The requirements are the unambiguity argument, enforced: the
     * concatenation needs no length prefixes *because* all three
     * components are fixed-length, and a caller that passed a short
     * group id would silently produce a preimage some other triple could
     * also produce. Every caller already holds validated values, so this
     * fires only on a programming error.
     */
    fun statement(
        groupId: ByteArray,
        rulesHash: ByteArray,
        joinerSendingPublicKey: ByteArray,
    ): ByteArray {
        require(groupId.size == 32) { "groupId must be 32 bytes" }
        require(rulesHash.size == 32) { "rulesHash must be 32 bytes" }
        require(joinerSendingPublicKey.size == 32) {
            "joinerSendingPublicKey must be 32 bytes"
        }
        return DOMAIN.toByteArray(Charsets.UTF_8) +
            groupId + rulesHash + joinerSendingPublicKey
    }

    /**
     * Whether [signature] is this joiner agreeing to exactly [rules] for
     * exactly this group.
     *
     * Takes the rules *text* rather than a hash on purpose: the caller
     * that can't produce the text has nothing to verify against, and
     * making that impossible to express is cheaper than documenting it.
     */
    fun isAgreement(
        signature: ByteArray,
        rules: String,
        groupId: ByteArray,
        joinerSendingPublicKey: ByteArray,
    ): Boolean = runCatching {
        // Sizes checked before `statement`, whose requirements are for
        // programming errors: everything here arrives from a stranger,
        // and wrong-sized bytes are a "no" rather than a throw.
        if (groupId.size != 32 || joinerSendingPublicKey.size != 32) return false
        val statement = statement(groupId, hash(rules), joinerSendingPublicKey)
        Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(joinerSendingPublicKey, 0))
            update(statement, 0, statement.size)
        }.verifySignature(signature)
        // A key of the wrong length, or bytes that aren't a signature at
        // all, throw rather than return false — and "we can't check it"
        // is the same answer as "it doesn't check out" to every caller.
    }.getOrDefault(false)
}
