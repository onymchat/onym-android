package app.onym.android.identity

import app.onym.android.foundation.Bip39
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/**
 * Stable per-identity handle. Keys every per-identity record in the
 * app — secrets in [IdentitySecretStore], owner stamps on
 * [app.onym.android.group.ChatGroup], inbox tags in the transport
 * layer.
 *
 * Wraps a UUID — opaque, never user-visible. Display names live on
 * [IdentitySummary] alongside this id.
 *
 * The string form `value` is what lands on disk + on the wire — safe
 * to use as a SharedPreferences key suffix or Room column.
 */
@JvmInline
@Serializable
value class IdentityId(val value: String) {
    init {
        require(value.isNotBlank()) { "IdentityId must not be blank" }
    }

    companion object {
        /**
         * Generate a fresh, never-before-used handle.
         *
         * Only correct for an identity that has no entropy behind it —
         * which, in the app, is none of them. Every production identity
         * is minted from BIP39 entropy and must go through
         * [derivedFromEntropy] instead, or it stops being recoverable
         * (see that function). This survives because the repository's
         * instrumented tests mint throwaway ids for "an id this device
         * has never seen" assertions (`select_unknownId_throws`,
         * `rename_unknownId_throws`) and pushing those through a fake
         * entropy blob would buy nothing. There are no production
         * callers left; if a second one ever wants this, that is the
         * moment to make it argue for itself.
         */
        fun new(): IdentityId = IdentityId(UUID.randomUUID().toString())

        /**
         * Derive the id from the BIP39 entropy behind the identity, so
         * importing a recovery phrase reproduces the *same* id it had
         * before.
         *
         * This is load-bearing for backup restore. Groups and messages
         * are owner-scoped (`ChatGroup.ownerIdentityId`,
         * `MessageStore.listForGroup(ownerIdentityId, groupId)`, and the
         * chat-list filter in `OnymApplication`), so an archive written
         * by identity A is invisible to identity B. While the id was a
         * fresh random UUID on every creation — import included —
         * restoring after entering a recovery phrase produced a device
         * holding every row and showing none of them: the restore summary
         * counted the chats, the chat list stayed empty. The entropy is
         * the only thing an import actually carries across devices, so
         * the id has to come from it.
         *
         * **Domain separation is the point of the `info` label.** The
         * same entropy roots the Nostr secp256k1 and BLS12-381 secret
         * keys ([Bip39.deriveNostrKey] / [Bip39.deriveBlsKey]), and this
         * value is *not* secret: it lands in a SharedPreferences key
         * suffix and in plaintext group/message rows. HKDF's `info` is
         * exactly the mechanism that keeps those worlds apart — the id is
         * computationally unrelated to any key derived from the same
         * seed, so holding it reveals nothing about them and it can never
         * be mistaken for, or fed in as, key material. The `v1` in
         * `identity-id-v1` is there so a future change of scheme is a new
         * label rather than a silent reinterpretation of the same bytes.
         * The salt matches the rest of the seed hierarchy
         * (`app.onym.bip39`) so one root salt covers everything and the
         * labels alone do the separating.
         *
         * Note this takes the raw entropy, **not** the PBKDF2 seed the
         * secret keys are derived from. Both are deterministic functions
         * of the mnemonic so either would reproduce, but the entropy is
         * one step further from the key material and cheaper for another
         * platform to match.
         *
         * **Cross-platform contract.** Ported from onym-ios
         * `IdentityID.init(derivedFromEntropy:)` (PR #292). A snapshot
         * taken on one platform must restore on the other (profile
         * §18.18), and the archive records its owner id verbatim, so the
         * two platforms must agree on the whole *string* — bytes and
         * casing both. `Foundation.UUID.uuidString` is upper-case and
         * nothing on the iOS path lower-cases it, so upper-case is the
         * interop form; Java's `UUID.toString()` is lower-case, hence the
         * explicit [Locale.ROOT] uppercase here rather than accepting the
         * platform default rendering. [CrossPlatformFixtureTest] pins the
         * phrase-to-id pair against iOS's own fixture. Legacy random ids
         * stay lower-case — nothing is rewritten (see below) — which is
         * cosmetic only: [IdentityId] equality is exact string equality
         * and each id is produced by exactly one path.
         *
         * **Version bits.** The output is stamped a v4 (random) UUID even
         * though it is derived. The value has to be a well-formed UUID or
         * it fails to parse somewhere far from here. RFC 9562's v8
         * ("custom") is the more literally honest nibble and was
         * rejected: it would make every derived id visually
         * distinguishable from a legacy random one, printing "this
         * identity is seed-derived" into every stored row. Nothing
         * downstream needs to know which way an id was minted.
         *
         * **No migration.** Identities already on a device keep the
         * random UUID they were persisted under; this derives at mint
         * time and rewrites nothing. A "fix up the id on load" would
         * silently orphan every row already keyed to the old one —
         * `bootstrap_preexistingIdentity_keepsItsPersistedRandomId`
         * asserts the property rather than leaving it assumed.
         */
        fun derivedFromEntropy(entropy: ByteArray): IdentityId {
            val bytes = Bip39.hkdfSha256(
                ikm = entropy,
                salt = "app.onym.bip39".toByteArray(Charsets.UTF_8),
                info = "identity-id-v1".toByteArray(Charsets.UTF_8),
                length = 16,
            )
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()  // version 4
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()  // RFC 4122 variant

            var most = 0L
            var least = 0L
            for (i in 0 until 8) most = (most shl 8) or (bytes[i].toLong() and 0xFF)
            for (i in 8 until 16) least = (least shl 8) or (bytes[i].toLong() and 0xFF)
            return IdentityId(UUID(most, least).toString().uppercase(Locale.ROOT))
        }
    }
}
