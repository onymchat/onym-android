package app.onym.android.discovery

import kotlinx.serialization.json.JsonElement
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Trust failures of the static-snapshot / Ed25519 profile, carrying
 * the abstract contract's error codes (`Discovery.md` §12 as mapped
 * by `Discovery-Static-Ed25519.md` §9). Sealed so callers can match
 * exhaustively; [code] is the wire/UI-stable identifier.
 */
sealed class DiscoveryTrustError(val code: String, message: String) : Exception(message) {
    /** Signature/key-pin failure, unknown top-level field, bad
     *  version/profile/seat, zero surviving catalog descriptors,
     *  expired `validUntil`, oversize, or URI violation on the
     *  provider manifest. */
    class ProviderManifestInvalid(reason: String) :
        DiscoveryTrustError("provider_manifest_invalid", reason)

    /** Signature failure, sequence gap/repeat/rollback,
     *  `previousDigest` mismatch, duplicate `componentId`, unknown
     *  top-level field, or oversize on a catalog snapshot. */
    class SnapshotInvalid(reason: String) :
        DiscoveryTrustError("snapshot_invalid", reason)

    /** `expiresAt` more than the 10-minute skew allowance in the past
     *  — entries are stale history, never current recommendations. */
    class SnapshotExpired(reason: String) :
        DiscoveryTrustError("snapshot_expired", reason)

    /** Fetched destination-manifest bytes hash differently from the
     *  digest the catalog entry pinned. The entry is rejected — never
     *  "refreshed" by trusting newer bytes the provider did not review. */
    class EntryManifestMismatch(reason: String) :
        DiscoveryTrustError("entry_manifest_mismatch", reason)

    /** Destination manifest unreachable or over the §7 size bound. */
    class EntryManifestUnavailable(reason: String) :
        DiscoveryTrustError("entry_manifest_unavailable", reason)

    /** Destination seat signature/schema/expiry failure (raised by
     *  seat adapters, not by this module's digest check). */
    class EntryManifestInvalid(reason: String) :
        DiscoveryTrustError("entry_manifest_invalid", reason)

    /** Two configured sources bind the same `componentId` to
     *  different manifest digests. */
    class SourceConflict(reason: String) :
        DiscoveryTrustError("source_conflict", reason)
}

/** [DiscoveryTrust.verifyProviderManifest] result: the parsed
 *  manifest plus the operator key to pin (trust-on-first-use — the
 *  caller shows the fingerprint, then persists [operatorKeyHex]). */
data class VerifiedProviderManifest(
    val manifest: DiscoveryProviderManifest,
    /** 64-char lowercase hex of the 32-byte Ed25519 operator key. */
    val operatorKeyHex: String,
    /** Descriptors that survived lossy per-descriptor decoding
     *  (§4.1) AND whose `audience` is exactly `"public"`, in manifest
     *  order. */
    val catalogs: List<CatalogDescriptor>,
    /** Indexes into `manifest.catalogs` that were skipped as
     *  malformed (unknown keys or bad fields) — surfaced, never
     *  silently dropped. */
    val skippedCatalogIndexes: List<Int>,
    /** Indexes of descriptors that decoded but whose `audience` is
     *  not exactly `"public"` — skipped per §1, surfaced in the
     *  source's skipped-catalog count, and NOT counted toward
     *  manifest invalidity (a manifest of decodable but all-non-public
     *  catalogs is a valid, empty-by-policy source). */
    val audienceSkippedCatalogIndexes: List<Int> = emptyList(),
)

/** §6's four-case chain comparison outcome for an ACCEPTED snapshot
 *  (rollback and fork are rejections, not outcomes). */
sealed class SnapshotChainOutcome {
    /** No retained acceptance: first acceptance of this catalog. Any
     *  sequence is accepted — TOFU pinning covers trust, and an
     *  established catalog past its first snapshot must be addable. */
    data object FirstAcceptance : SnapshotChainOutcome()

    /** `sequence` = retained + 1 with a matching `previousDigest`. */
    data object Successor : SnapshotChainOutcome()

    /** Same sequence, same bytes — the provider simply hasn't
     *  published since. No warning. */
    data object NoOpRefresh : SnapshotChainOutcome()

    /** `sequence` more than retained + 1: accepted with a
     *  source-integrity note (§6). The intermediate-fetch continuity
     *  walk is not performed (gap-listed in the profile's §11). */
    data class ForwardJumpWithNote(val missed: Long) : SnapshotChainOutcome()
}

/** [DiscoveryTrust.verifySnapshot] result. */
data class VerifiedSnapshot(
    val snapshot: CatalogSnapshot,
    /** Entries that survived lossy decoding + per-entry validation,
     *  in the snapshot's (policy-ranked) order. */
    val entries: List<CatalogEntry>,
    /** Indexes into `snapshot.entries` that were skipped as malformed. */
    val skippedEntryIndexes: List<Int>,
    /** `sha256:` digest of the exact fetched bytes — retained by the
     *  caller for the next refresh's chain comparison (§8). */
    val digest: String,
    /** How this acceptance relates to the retained chain state (§6). */
    val outcome: SnapshotChainOutcome = SnapshotChainOutcome.FirstAcceptance,
    /** True when `policyDigest` matched the retained PREVIOUS policy
     *  declaration rather than the manifest's current one — accepted
     *  with a surfaced policy-transition note (§4.2). */
    val policyTransition: Boolean = false,
)

/** Chain anchor for [DiscoveryTrust.verifySnapshot] when only the
 *  previously *accepted* digest + sequence were retained (§8) rather
 *  than the full previous bytes. */
data class AcceptedSnapshotRef(
    /** `sha256:` digest of the previously accepted snapshot's exact bytes. */
    val digest: String,
    val sequence: Long,
    /** The `policy` digest the manifest declared for this catalog at
     *  the last acceptance — the "immediately previous declaration"
     *  §4.2's policy-transition grace accepts with a note. */
    val previousPolicyDigest: String? = null,
)

/**
 * Hard-enforced client verification for the Discovery seat
 * (`Discovery-Static-Ed25519.md` §6): provider manifests with TOFU
 * key pinning, snapshot chains, and destination-manifest digest
 * binding.
 *
 * Unlike :foundation's `TrustedAssetVerifier` (a soft gate behind
 * `ENFORCE_SIGNATURES`, deliberately untouched), every check here is
 * unconditional — a Discovery document that fails verification is
 * rejected, full stop. `now` is always injected so tests pin exact
 * clock behaviour.
 *
 * Mirrors the Rust reference (`onym-discovery` `src/verify.rs`);
 * both pin the same conformance fixtures.
 */
object DiscoveryTrust {

    /** §4.2/§9 clock-skew allowance on snapshot expiry. */
    private val CLOCK_SKEW: Duration = Duration.ofMinutes(10)

    // ─── provider manifest ────────────────────────────────────────

    /**
     * Verify a provider manifest's size, strict schema, field rules,
     * expiry, self-signature — and the key pin.
     *
     * @param raw exact fetched bytes.
     * @param pinnedOperatorKeyHex the key pinned at add time, or
     *        `null` on first use: the returned
     *        [VerifiedProviderManifest.operatorKeyHex] is what the
     *        caller pins after showing the user the fingerprint. A
     *        later manifest signed by a different key is
     *        [DiscoveryTrustError.ProviderManifestInvalid], never a
     *        silent rotation.
     * @param now injected clock for the `validUntil` check.
     */
    fun verifyProviderManifest(
        raw: ByteArray,
        pinnedOperatorKeyHex: String? = null,
        now: Instant,
    ): VerifiedProviderManifest {
        if (raw.size > DiscoveryProfile.MAX_MANIFEST_BYTES) {
            throw manifestInvalid("manifest exceeds ${DiscoveryProfile.MAX_MANIFEST_BYTES} bytes")
        }
        val manifest = try {
            DiscoveryJson.strict.decodeFromString(
                DiscoveryProviderManifest.serializer(),
                String(raw, Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw manifestInvalid("schema: ${e.message}")
        }

        if (manifest.version != 1) {
            throw manifestInvalid("unsupported version ${manifest.version}")
        }
        if (manifest.implementationProfileId != DiscoveryProfile.IMPLEMENTATION_PROFILE) {
            throw manifestInvalid(
                "unsupported implementation profile ${manifest.implementationProfileId}"
            )
        }
        if (manifest.seat != "discovery") {
            throw manifestInvalid("seat must be discovery, got ${manifest.seat}")
        }
        validateComponentId(manifest.providerId) { manifestInvalid(it) }
        val operatorKeyHex = parseOperatorKeyHex(manifest.operator) { manifestInvalid(it) }
        if (pinnedOperatorKeyHex != null && pinnedOperatorKeyHex != operatorKeyHex) {
            throw manifestInvalid(
                "operator key does not match the pinned key — re-keying requires re-adding the source"
            )
        }

        // Lossy per-descriptor decode (§4.1): a descriptor with
        // unknown keys or otherwise malformed is skipped and counted,
        // never document-fatal. Zero DECODABLE descriptors is fatal —
        // audience-based skips (§1) do NOT count toward invalidity: a
        // manifest of decodable but all-non-public catalogs is a
        // valid, empty-by-policy source.
        val catalogs = mutableListOf<CatalogDescriptor>()
        val skippedCatalogs = mutableListOf<Int>()
        val audienceSkipped = mutableListOf<Int>()
        val seenCatalogIds = mutableSetOf<String>()
        manifest.catalogs.forEachIndexed { index, element ->
            val descriptor = decodeDescriptor(element)
            when {
                descriptor == null -> skippedCatalogs.add(index)
                else -> {
                    // Duplicate detection runs over every DECODED
                    // descriptor, audience-skipped or not (§4.1).
                    if (!seenCatalogIds.add(descriptor.catalogId)) {
                        throw manifestInvalid("duplicate catalogId ${descriptor.catalogId}")
                    }
                    if (descriptor.audience != "public") {
                        audienceSkipped.add(index)
                    } else {
                        catalogs.add(descriptor)
                    }
                }
            }
        }
        if (seenCatalogIds.isEmpty()) {
            throw manifestInvalid("no decodable catalog descriptors")
        }
        validateUri(manifest.privacyProfileUri) { manifestInvalid(it) }
        validateDigest(manifest.privacyProfile) { manifestInvalid(it) }

        val validUntil = parseTimestamp(manifest.validUntil) { manifestInvalid(it) }
        if (!validUntil.isAfter(now)) {
            throw manifestInvalid("manifest expired at ${manifest.validUntil}")
        }

        val signature = manifest.signature
            ?: throw manifestInvalid("manifest is unsigned")
        verifyEd25519(operatorKeyHex, raw, signature) { manifestInvalid(it) }

        return VerifiedProviderManifest(
            manifest = manifest,
            operatorKeyHex = operatorKeyHex,
            catalogs = catalogs,
            skippedCatalogIndexes = skippedCatalogs,
            audienceSkippedCatalogIndexes = audienceSkipped,
        )
    }

    /**
     * Lossy descriptor decode (§4.1): strict within the descriptor —
     * an unknown key, a bad catalog id, a §7 URI violation, or a
     * malformed policy digest → `null`, the caller skips + counts.
     * Never throws — one bad descriptor must not take the manifest
     * down; zero survivors is the caller's fatal case.
     */
    private fun decodeDescriptor(element: JsonElement): CatalogDescriptor? {
        val descriptor = try {
            DiscoveryJson.strict.decodeFromJsonElement(CatalogDescriptor.serializer(), element)
        } catch (_: Exception) {
            return null
        }
        return try {
            validateCatalogId(descriptor.catalogId) { entrySkip(it) }
            validateUri(descriptor.snapshot) { entrySkip(it) }
            validateDigest(descriptor.policy) { entrySkip(it) }
            validateUri(descriptor.policyUri) { entrySkip(it) }
            // §4.1: seatTypes members are seat-type tokens
            // (`[a-z0-9.-]{1,64}`) or the literal `"*"` wildcard,
            // which must appear alone. A member matching neither form
            // skips the descriptor (one lossiness model per level).
            for (member in descriptor.seatTypes) {
                if (!isSeatTypeMember(member)) throw entrySkip("invalid seatTypes member $member")
            }
            if ("*" in descriptor.seatTypes && descriptor.seatTypes.size > 1) {
                throw entrySkip("\"*\" must appear alone in seatTypes")
            }
            descriptor
        } catch (_: EntrySkipException) {
            null
        }
    }

    /** §4.1 `seatTypes` member: `[a-z0-9.-]{1,64}` token or `"*"`. */
    private fun isSeatTypeMember(value: String): Boolean {
        if (value == "*") return true
        if (value.isEmpty() || value.length > 64) return false
        return value.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
    }

    // ─── catalog snapshot ─────────────────────────────────────────

    /**
     * Verify a snapshot against its (already verified) provider
     * manifest and the exact bytes of the previously accepted
     * snapshot. Convenience over the [AcceptedSnapshotRef] variant —
     * checks additionally that the previous bytes are for the same
     * catalog.
     */
    fun verifySnapshot(
        raw: ByteArray,
        manifest: VerifiedProviderManifest,
        previousRaw: ByteArray? = null,
        now: Instant,
    ): VerifiedSnapshot {
        val previous = previousRaw?.let { bytes ->
            val prev = try {
                DiscoveryJson.strict.decodeFromString(
                    CatalogSnapshot.serializer(),
                    String(bytes, Charsets.UTF_8),
                )
            } catch (e: Exception) {
                throw snapshotInvalid("previous snapshot: ${e.message}")
            }
            val currentCatalogId = peekCatalogId(raw)
            if (currentCatalogId != null && prev.catalogId != currentCatalogId) {
                throw snapshotInvalid("previous snapshot is for a different catalog")
            }
            AcceptedSnapshotRef(digest = sha256Digest(bytes), sequence = prev.sequence)
        }
        return verifySnapshot(raw, manifest, previous, now)
    }

    /**
     * Verify a snapshot against its (already verified) provider
     * manifest and the retained chain anchor (§8: digest + sequence of
     * the last accepted snapshot). A sequence that does not increase
     * by exactly 1, or a `previousDigest` that does not match the
     * retained bytes, is evidence of rollback or equivocation —
     * [DiscoveryTrustError.SnapshotInvalid], never a silent accept of
     * either fork.
     */
    fun verifySnapshot(
        raw: ByteArray,
        manifest: VerifiedProviderManifest,
        previous: AcceptedSnapshotRef?,
        now: Instant,
    ): VerifiedSnapshot {
        if (raw.size > DiscoveryProfile.MAX_SNAPSHOT_BYTES) {
            throw snapshotInvalid("snapshot exceeds ${DiscoveryProfile.MAX_SNAPSHOT_BYTES} bytes")
        }
        val snapshot = try {
            DiscoveryJson.strict.decodeFromString(
                CatalogSnapshot.serializer(),
                String(raw, Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw snapshotInvalid("schema: ${e.message}")
        }

        if (snapshot.version != 1) {
            throw snapshotInvalid("unsupported version ${snapshot.version}")
        }
        if (snapshot.implementationProfileId != DiscoveryProfile.IMPLEMENTATION_PROFILE) {
            throw snapshotInvalid("unsupported implementation profile")
        }
        if (snapshot.providerId != manifest.manifest.providerId) {
            throw snapshotInvalid(
                "providerId ${snapshot.providerId} does not match manifest ${manifest.manifest.providerId}"
            )
        }
        val descriptor = manifest.catalogs.firstOrNull { it.catalogId == snapshot.catalogId }
            ?: throw snapshotInvalid("catalogId ${snapshot.catalogId} not declared by manifest")
        validateDigest(snapshot.policyDigest) { snapshotInvalid(it) }
        // §4.2 policy-transition grace: the snapshot must cite the
        // manifest's current declaration OR the immediately previous
        // one the client retained — accepted with a surfaced
        // transition note. Any other digest is snapshot_invalid.
        var policyTransition = false
        if (snapshot.policyDigest != descriptor.policy) {
            if (previous?.previousPolicyDigest == snapshot.policyDigest) {
                policyTransition = true
            } else {
                throw snapshotInvalid(
                    "policyDigest matches neither the manifest's pinned policy " +
                        "nor the retained previous declaration"
                )
            }
        }

        // Chain rules (§4.2, structural).
        if (snapshot.sequence < 1) {
            throw snapshotInvalid("sequence starts at 1")
        }
        if (snapshot.sequence == 1L) {
            if (snapshot.previousDigest != null) {
                throw snapshotInvalid("sequence 1 must not carry previousDigest")
            }
        } else {
            val previousDigest = snapshot.previousDigest
                ?: throw snapshotInvalid("sequence > 1 requires previousDigest")
            validateDigest(previousDigest) { snapshotInvalid(it) }
        }
        // §6 four-case comparison against the retained acceptance.
        val digest = sha256Digest(raw)
        val outcome: SnapshotChainOutcome = if (previous == null) {
            // First acceptance of this catalog: any sequence — an
            // established catalog past its first snapshot must be
            // addable; TOFU covers trust.
            SnapshotChainOutcome.FirstAcceptance
        } else {
            when {
                snapshot.sequence < previous.sequence ->
                    throw snapshotInvalid(
                        "rollback: sequence ${snapshot.sequence} after accepted ${previous.sequence}"
                    )
                snapshot.sequence == previous.sequence ->
                    if (digest == previous.digest) {
                        SnapshotChainOutcome.NoOpRefresh
                    } else {
                        throw snapshotInvalid(
                            "fork: sequence ${snapshot.sequence} republished with different bytes"
                        )
                    }
                snapshot.sequence == previous.sequence + 1 ->
                    if (snapshot.previousDigest == previous.digest) {
                        SnapshotChainOutcome.Successor
                    } else {
                        throw snapshotInvalid(
                            "fork: previousDigest does not match the retained snapshot digest"
                        )
                    }
                else ->
                    // Forward jump: accepted with a source-integrity
                    // note. The §6 intermediate continuity walk is not
                    // performed (gap-listed in the profile's §11).
                    SnapshotChainOutcome.ForwardJumpWithNote(
                        missed = snapshot.sequence - previous.sequence - 1
                    )
            }
        }

        // Freshness.
        val generatedAt = parseTimestamp(snapshot.generatedAt) { snapshotInvalid(it) }
        val expiresAt = parseTimestamp(snapshot.expiresAt) { snapshotInvalid(it) }
        if (!expiresAt.isAfter(generatedAt)) {
            throw snapshotInvalid("expiresAt must be after generatedAt")
        }
        if (Duration.between(generatedAt, expiresAt) >
            Duration.ofDays(DiscoveryProfile.MAX_EXPIRY_WINDOW_DAYS)
        ) {
            throw snapshotInvalid("expiry window exceeds ${DiscoveryProfile.MAX_EXPIRY_WINDOW_DAYS} days")
        }
        // §4.2: generatedAt must not lie in the verifier's future
        // beyond the skew allowance — otherwise the 90-day ceiling
        // could be minted forward.
        if (generatedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw snapshotInvalid("generatedAt ${snapshot.generatedAt} is in the future")
        }
        // Expired only when expiresAt is more than the skew allowance
        // in the past (§4.2/§9 — symmetric 10-minute clock skew; a
        // fast clock must not reject a fresh snapshot).
        if (expiresAt.plus(CLOCK_SKEW).isBefore(now)) {
            throw DiscoveryTrustError.SnapshotExpired("expired at ${snapshot.expiresAt}")
        }

        // Signature over the exact fetched bytes' canonical form, by
        // the manifest's operator key.
        val operatorKeyHex = parseOperatorKeyHex(manifest.manifest.operator) { snapshotInvalid(it) }
        val signature = snapshot.signature
            ?: throw snapshotInvalid("snapshot is unsigned")
        verifyEd25519(operatorKeyHex, raw, signature) { snapshotInvalid(it) }

        // Entries: lossy per-element decode, then strict per-entry
        // field validation; duplicates among survivors are fatal.
        if (snapshot.entries.size > DiscoveryProfile.MAX_ENTRIES) {
            throw snapshotInvalid(
                "${snapshot.entries.size} entries exceeds ${DiscoveryProfile.MAX_ENTRIES}"
            )
        }
        val entries = mutableListOf<CatalogEntry>()
        val skipped = mutableListOf<Int>()
        snapshot.entries.forEachIndexed { index, element ->
            val entry = decodeEntry(element)
            if (entry != null) entries.add(entry) else skipped.add(index)
        }
        val seenComponentIds = mutableSetOf<String>()
        for (entry in entries) {
            if (!seenComponentIds.add(entry.componentId)) {
                throw snapshotInvalid("duplicate componentId ${entry.componentId}")
            }
        }

        return VerifiedSnapshot(
            snapshot = snapshot,
            entries = entries,
            skippedEntryIndexes = skipped,
            digest = digest,
            outcome = outcome,
            policyTransition = policyTransition,
        )
    }

    // ─── destination manifest ─────────────────────────────────────

    /**
     * Verify fetched destination-manifest bytes against the digest
     * the catalog entry pinned. Discovery's authority stops here —
     * whether the manifest satisfies the destination *seat's* rules is
     * that seat's contract, applied by the client afterwards.
     */
    fun verifyDestination(manifestBytes: ByteArray, pinnedDigest: String) {
        if (manifestBytes.size > DiscoveryProfile.MAX_DESTINATION_MANIFEST_BYTES) {
            throw DiscoveryTrustError.EntryManifestUnavailable(
                "destination manifest exceeds ${DiscoveryProfile.MAX_DESTINATION_MANIFEST_BYTES} bytes"
            )
        }
        val actual = sha256Digest(manifestBytes)
        if (actual != pinnedDigest) {
            throw DiscoveryTrustError.EntryManifestMismatch(
                "pinned $pinnedDigest, fetched bytes hash to $actual"
            )
        }
    }

    // ─── shared helpers ───────────────────────────────────────────

    /** `sha256:` + 64 lowercase hex over [bytes] — always the exact
     *  published bytes, never a canonical form (§2). */
    fun sha256Digest(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Lossy entry decode: strict within the entry (unknown entry
     * field, bad syntax, unknown relationship, §7 URI violation →
     * `null`, the caller skips + counts). Never throws — one bad
     * entry must not take the snapshot down.
     */
    private fun decodeEntry(element: JsonElement): CatalogEntry? {
        val raw = try {
            DiscoveryJson.strict.decodeFromJsonElement(RawCatalogEntry.serializer(), element)
        } catch (_: Exception) {
            return null
        }
        return try {
            validateComponentId(raw.componentId) { entrySkip(it) }
            if (raw.seatType.isEmpty()) throw entrySkip("empty seatType")
            validateUri(raw.manifest.uri) { entrySkip(it) }
            validateDigest(raw.manifest.digest) { entrySkip(it) }
            parseOperatorKeyHex(raw.operator) { entrySkip(it) }
            parseTimestamp(raw.listedAt) { entrySkip(it) }
            raw.reviewedAt?.let { parseTimestamp(it) { m -> entrySkip(m) } }
            // Unknown relationship → skip, never defaulted to `none`
            // (advertising must not pass as organic via a new tag).
            val relationship = EntryRelationship.fromRaw(raw.relationship)
                ?: throw entrySkip("unknown relationship ${raw.relationship}")
            if (raw.placement.isEmpty()) throw entrySkip("empty placement")
            // §4.2: evidence must be absent or empty in v1 — a
            // non-empty array is an unrenderable attestation (the
            // audit-laundering the abstract §13 forbids presenting);
            // the entry is skipped, never passed through.
            if (raw.evidence.isNotEmpty()) throw entrySkip("non-empty evidence in v1")
            // §4.2 status: {state: warning|review, uri?}. A VALID
            // status never skips the entry — it is exactly the
            // warning the field exists to surface; anything else is
            // undecodable and skips the entry.
            raw.status?.let { status ->
                if (status.state !in EntryStatus.allowedStates) {
                    throw entrySkip("unknown status state ${status.state}")
                }
                status.uri?.let { validateUri(it) { m -> entrySkip(m) } }
            }
            CatalogEntry(
                componentId = raw.componentId,
                seatType = raw.seatType,
                manifest = raw.manifest,
                operator = raw.operator,
                profiles = raw.profiles,
                evidence = raw.evidence,
                listedAt = raw.listedAt,
                reviewedAt = raw.reviewedAt,
                relationship = relationship,
                placement = raw.placement,
                status = raw.status,
            )
        } catch (_: EntrySkipException) {
            null
        }
    }

    /** Internal control-flow marker for per-entry validation failures. */
    private class EntrySkipException(message: String) : Exception(message)

    private fun entrySkip(message: String) = EntrySkipException(message)

    private fun manifestInvalid(reason: String) =
        DiscoveryTrustError.ProviderManifestInvalid(reason)

    private fun snapshotInvalid(reason: String) =
        DiscoveryTrustError.SnapshotInvalid(reason)

    /** Cheap catalogId peek of the fetched bytes for the previous-
     *  bytes overload's same-catalog check; `null` if unparseable
     *  (the main strict decode reports the real error). */
    private fun peekCatalogId(raw: ByteArray): String? = try {
        val element = kotlinx.serialization.json.Json
            .parseToJsonElement(String(raw, Charsets.UTF_8))
        (element as? kotlinx.serialization.json.JsonObject)
            ?.get("catalogId")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull() }
    } catch (_: Exception) {
        null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null

    /** `onym:component:<token>` with token in `[a-z0-9-]{1,64}` (§2). */
    private inline fun validateComponentId(value: String, error: (String) -> Exception) {
        val token = value.removePrefixOrNull("onym:component:")
            ?: throw error("component id must start with onym:component: — got $value")
        if (token.isEmpty() || token.length > 64 ||
            !token.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        ) {
            throw error("invalid component token: $token")
        }
    }

    /** `[a-z0-9-]{1,64}` — catalog ids (§4.1). */
    private inline fun validateCatalogId(value: String, error: (String) -> Exception) {
        if (value.isEmpty() || value.length > 64 ||
            !value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        ) {
            throw error("invalid catalog id: $value")
        }
    }

    /** `sha256:` + 64 lowercase hex (§2). */
    private inline fun validateDigest(value: String, error: (String) -> Exception) {
        val hexPart = value.removePrefixOrNull("sha256:")
            ?: throw error("digest must start with sha256: — got $value")
        if (hexPart.length != 64 || !hexPart.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw error("digest must be 64 lowercase hex characters")
        }
    }

    /** `onym:key:` + 64 lowercase hex → the hex part (§2). */
    private inline fun parseOperatorKeyHex(value: String, error: (String) -> Exception): String {
        val hexPart = value.removePrefixOrNull("onym:key:")
            ?: throw error("operator key must start with onym:key: — got $value")
        if (hexPart.length != 64 || !hexPart.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw error("operator key must be 64 lowercase hex characters")
        }
        return hexPart
    }

    /**
     * Profile §7 URI rules: `https` only, DNS host (no IPv4/IPv6
     * literals — closes the SSRF-to-local-network path), no userinfo,
     * no query, no fragment, default port only (443, omitted).
     */
    private inline fun validateUri(value: String, error: (String) -> Exception) {
        val uri = try {
            URI(value)
        } catch (e: Exception) {
            throw error("$value: ${e.message}")
        }
        if (uri.scheme != "https") throw error("$value: scheme must be https")
        val host = uri.host ?: throw error("$value: host must be a DNS name")
        if (host.startsWith("[") || host.contains(':') || IPV4_LITERAL.matches(host)) {
            throw error("$value: host must be a DNS name, not an IP literal")
        }
        if (uri.userInfo != null) throw error("$value: userinfo is not allowed")
        if (uri.rawQuery != null) throw error("$value: query is not allowed")
        if (uri.rawFragment != null) throw error("$value: fragment is not allowed")
        if (uri.port != -1) throw error("$value: explicit port is not allowed")
        // Integer-form and hex-form IPv4 literals: a host whose LAST
        // dot label is all digits or 0x-prefixed is an IPv4 candidate
        // per the URL host parser, never a DNS name.
        val lastLabel = host.substringAfterLast('.')
        if (lastLabel.isNotEmpty() && lastLabel.all { it in '0'..'9' }) {
            throw error("$value: host must be a DNS name, not an IP literal")
        }
        if (lastLabel.lowercase().startsWith("0x")) {
            throw error("$value: host must be a DNS name, not an IP literal")
        }
        // §7: the port check also runs over the RAW string, not only
        // the parsed port — URL libraries commonly normalize a
        // redundant `:443` away before a parsed-port check is
        // reachable, and a verifier relying only on the parsed port
        // does not conform.
        val authorityStart = value.indexOf("://").let { if (it < 0) return else it + 3 }
        val authorityEnd = value.drop(authorityStart)
            .indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) value.length else authorityStart + it }
        val authority = value.substring(authorityStart, authorityEnd)
        val hostPort = authority.substringAfterLast('@')
        if (':' in hostPort) {
            throw error("$value: port component in raw URI string is not allowed")
        }
    }

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** RFC 3339 UTC with `Z` suffix (§2). */
    private inline fun parseTimestamp(value: String, error: (String) -> Exception): Instant =
        try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            throw error("invalid timestamp $value: ${e.message}")
        }

    /**
     * Ed25519 (BouncyCastle) over [raw]'s canonical signing bytes.
     * [signatureBase64] accepts padded or unpadded standard base64
     * (§3).
     */
    private inline fun verifyEd25519(
        operatorKeyHex: String,
        raw: ByteArray,
        signatureBase64: String,
        error: (String) -> Exception,
    ) {
        val signingBytes = try {
            DiscoveryCanonical.signingBytes(raw)
        } catch (e: Exception) {
            throw error("canonicalization: ${e.message}")
        }
        val signature = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (e: IllegalArgumentException) {
            throw error("signature is not valid base64: ${e.message}")
        }
        if (signature.size != 64) throw error("signature must be 64 bytes")
        val publicKey = operatorKeyHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        val verified = try {
            val verifier = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
            }
            verifier.update(signingBytes, 0, signingBytes.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
        if (!verified) throw error("Ed25519 signature did not verify")
    }

    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) removePrefix(prefix) else null
}
