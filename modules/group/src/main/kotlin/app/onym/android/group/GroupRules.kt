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
 * order, same cap, so a signature made on either platform verifies on
 * the other.
 */
object GroupRules {
    /**
     * The longest rules text an invite may carry.
     *
     * The binding constraint is the QR code, not the screen. An invite
     * link is `base64(JSON)` in a URL, rendered at correction level M,
     * whose byte-mode ceiling is 2331 bytes. Measured end to end, with
     * both 32-byte keys and a 30-character group name in the JSON:
     *
     *     500 ASCII characters     →   924-byte link
     *     500 Cyrillic characters  →  1591-byte link
     *     500 CJK characters       →  2258-byte link
     *
     * So 500 fits even when every character costs three UTF-8 bytes,
     * which is the case that decides the cap. A longer one would produce
     * links that encode fine and then fail to scan — the worst way to
     * find out.
     *
     * The headroom in that last row is thin, and it is shared with the
     * group name, which has no cap of its own: 500 CJK characters of
     * rules alongside a 30-character CJK name comes to 2338 bytes and
     * clears the ceiling. Latin and Cyrillic names leave room to spare,
     * which is why 500 stands, but the pair is what a future change has
     * to re-measure.
     */
    const val MAX_LENGTH = 500

    /** Domain separator. Versioned: a change to the statement's shape
     *  must not let old signatures verify under the new reading. */
    internal const val DOMAIN = "onym-group-rules-v1"

    /**
     * The one canonical form, applied by *both* sides before hashing.
     *
     * Ends-only trimming, nothing else: the founder wrote this text and
     * a joiner is agreeing to it, so collapsing inner whitespace or
     * normalising case would mean signing something other than what was
     * displayed. Trailing newlines from a text field are the one
     * difference that carries no meaning, and letting them through would
     * make agreement fail on an invisible character.
     */
    fun canonical(rules: String): String = rules.trim()

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

    /** The exact bytes both sides sign and verify. See the type doc. */
    fun statement(
        groupId: ByteArray,
        rulesHash: ByteArray,
        joinerSendingPublicKey: ByteArray,
    ): ByteArray =
        DOMAIN.toByteArray(Charsets.UTF_8) + groupId + rulesHash + joinerSendingPublicKey

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
