package app.onym.android.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant

/**
 * Where an entry came from — kept alongside every published entry so
 * the UI can disclose catalog source, commercial relationship, and
 * placement (`Discovery.md` §7's disclosure obligations), and so seat
 * adapters can bind consent to the exact reviewed snapshot.
 */
@Serializable
data class SourceAttribution(
    val providerId: String,
    val sourceLabel: String,
    val catalogId: String,
    /** `sha256:` digest of the exact snapshot bytes the entry came
     *  from — the anchor for later chain / consent comparisons. */
    val snapshotDigest: String,
    val relationship: EntryRelationship,
    val placement: String,
)

/** A verified catalog entry plus its source attribution. */
@Serializable
data class AttributedCatalogEntry(
    val entry: CatalogEntry,
    val attribution: SourceAttribution,
)

/**
 * Last refresh outcome for one configured source, kept in-memory
 * (like [DiscoveryFetchStatus], never persisted) so the Discovery
 * settings UI can chip each source independently. [isIntegrity]
 * separates trust findings (signature, chain, expiry — about the
 * provider) from plain fetch failures (about the network).
 */
data class DiscoverySourceError(
    val message: String,
    val isIntegrity: Boolean,
)

/** Refresh lifecycle, mirrored from :chain's `RelayerFetchStatus`. */
sealed class DiscoveryFetchStatus {
    data object Idle : DiscoveryFetchStatus()
    data object Fetching : DiscoveryFetchStatus()
    data object Success : DiscoveryFetchStatus()
    data class Failed(val message: String) : DiscoveryFetchStatus()
}

/** Everything the Discovery UI observes, published as one value.
 *  [sourceNotes] carries the last refresh's integrity/completeness
 *  notes per providerId — skipped catalogs and entries
 *  (`result_incomplete`), audience skips, forward-jump and
 *  policy-transition notes (§1/§6/§9). Informational: the source's
 *  data was still accepted. */
data class DiscoveryState(
    val configuration: DiscoverySourcesConfiguration,
    val entries: List<AttributedCatalogEntry>,
    val fetchStatus: DiscoveryFetchStatus,
    /** Per-source outcome of the last refresh, keyed by providerId.
     *  Absent key = the source refreshed cleanly (or hasn't been
     *  refreshed yet). In-memory only. */
    val sourceErrors: Map<String, DiscoverySourceError> = emptyMap(),
    val sourceNotes: Map<String, List<String>> = emptyMap(),
) {
    val sources: List<DiscoverySource> get() = configuration.sources

    companion object {
        val empty: DiscoveryState = DiscoveryState(
            configuration = DiscoverySourcesConfiguration.empty,
            entries = emptyList(),
            fetchStatus = DiscoveryFetchStatus.Idle,
        )
    }
}

/**
 * [DiscoveryRepository.addSource] result: the manifest verified but
 * the operator key is **not yet pinned** — the caller shows the user
 * [operatorKeyHex]'s fingerprint and only
 * [DiscoveryRepository.confirmAddSource] makes the source live
 * (trust-on-first-use, §6).
 */
data class PendingDiscoverySource(
    val url: String,
    val manifest: DiscoveryProviderManifest,
    val operatorKeyHex: String,
    /** Descriptors that survived lossy per-descriptor decoding (§4.1)
     *  — what the confirmation UI lists as the source's catalogs. */
    val catalogs: List<CatalogDescriptor> = emptyList(),
)

/**
 * Owns the in-memory view of the Discovery source set + verified,
 * source-attributed catalog entries, plus the corresponding writes to
 * the persistence + network seams. Same triad shape as :chain's
 * `RelayerRepository` — `Mutex` + `StateFlow`,
 * [bootstrap]/[start]/[refresh].
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** [store] + [fetcher] (+ clock + shipped defaults).
 *    No transport, no OnymSDK, no Compose, no `Context`.
 *  - Exposes a [StateFlow] for observation; [addSource] /
 *    [confirmAddSource] / [removeSource] / [start] / [refresh] /
 *    [bootstrap] are the suspend mutators.
 *  - Mutations serialise through a [Mutex]; reads are lock-free off
 *    the [StateFlow]. The mutex is **not** held across network calls.
 */
class DiscoveryRepository(
    private val store: DiscoveryStore,
    private val fetcher: DiscoveryFetching,
    /** Sources shipped with the app (e.g. Onym's own provider),
     *  pre-pinned. Merged at [bootstrap] minus
     *  [DiscoverySourcesConfiguration.removedDefaultProviderIds] —
     *  a removed default is never silently restored. */
    private val defaultSources: List<DiscoverySource> = emptyList(),
    /** Injected clock — every trust check runs against this. */
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow(DiscoveryState.empty)

    val snapshots: StateFlow<DiscoveryState> = _snapshots.asStateFlow()

    private var startInvoked = false

    /** Hydrate from disk — persisted configuration (merged with the
     *  shipped defaults minus explicit removals) + last verified
     *  entry list. Idempotent. Preserves the in-memory
     *  [DiscoveryFetchStatus] (which is `Idle` until the first
     *  [start] / [refresh]). */
    suspend fun bootstrap() = mutex.withLock {
        val persisted = store.loadConfiguration()
        val merged = mergeDefaultsLocked(persisted)
        if (merged != persisted) store.saveConfiguration(merged)
        _snapshots.value = _snapshots.value.copy(
            configuration = merged,
            entries = store.loadCachedEntries(),
        )
    }

    /** Fire-and-forget the first refresh. Idempotent; errors
     *  swallowed (app launch must not crash on a network blip) with
     *  the once-guard released on failure so a later [start] can
     *  retry — same recovery semantics as `RelayerRepository.start`. */
    suspend fun start() {
        val shouldFetch = mutex.withLock {
            if (startInvoked) false else { startInvoked = true; true }
        }
        if (!shouldFetch) return
        val succeeded = runCatching { refreshInternal() }.isSuccess
        if (!succeeded) {
            mutex.withLock { startInvoked = false }
        }
    }

    /** User-initiated refresh; failures propagate to the caller AND a
     *  [DiscoveryFetchStatus.Failed] snapshot is published. */
    suspend fun refresh() {
        refreshInternal()
    }

    // ─── source management ────────────────────────────────────────

    /**
     * Step one of adding a source by URL: fetch + verify the provider
     * manifest with **no pinned key**. Nothing is persisted — the
     * returned [PendingDiscoverySource] carries the key the UI must
     * show as a fingerprint before [confirmAddSource] pins it.
     */
    suspend fun addSource(url: String): PendingDiscoverySource {
        val raw = fetcher.fetch(url, DiscoveryProfile.MAX_MANIFEST_BYTES)
        val verified = DiscoveryTrust.verifyProviderManifest(
            raw = raw,
            pinnedOperatorKeyHex = null,
            now = now(),
        )
        return PendingDiscoverySource(
            url = url,
            manifest = verified.manifest,
            operatorKeyHex = verified.operatorKeyHex,
            catalogs = verified.catalogs,
        )
    }

    /**
     * Step two: the user confirmed the fingerprint — pin the key,
     * persist the source, then refresh so its catalogs load. If the
     * providerId was previously a removed default, confirming
     * re-adds it deliberately (an explicit user action, not a silent
     * restore) and clears the removal marker.
     */
    suspend fun confirmAddSource(
        pending: PendingDiscoverySource,
        label: String = defaultLabelFor(pending.url),
    ) {
        mutex.withLock {
            val current = _snapshots.value.configuration
            val source = DiscoverySource(
                url = pending.url,
                label = label,
                providerId = pending.manifest.providerId,
                pinnedOperatorKeyHex = pending.operatorKeyHex,
            )
            val others = current.sources.filterNot { it.providerId == source.providerId }
            applyConfigurationLocked(
                current.copy(
                    sources = others + source,
                    removedDefaultProviderIds =
                        current.removedDefaultProviderIds - source.providerId,
                )
            )
        }
        runCatching { refreshInternal() }
    }

    /**
     * Remove the source with [providerId]. Durable for shipped
     * defaults: the providerId is recorded in
     * [DiscoverySourcesConfiguration.removedDefaultProviderIds] so
     * [bootstrap] never silently restores it (§8). Entries attributed
     * to the removed source are dropped immediately.
     */
    suspend fun removeSource(providerId: String) = mutex.withLock {
        val current = _snapshots.value.configuration
        if (current.sources.none { it.providerId == providerId }) return@withLock
        val removedDefaults =
            if (defaultSources.any { it.providerId == providerId }) {
                current.removedDefaultProviderIds + providerId
            } else {
                current.removedDefaultProviderIds
            }
        applyConfigurationLocked(
            current.copy(
                sources = current.sources.filterNot { it.providerId == providerId },
                removedDefaultProviderIds = removedDefaults,
            )
        )
        val remaining = _snapshots.value.entries
            .filterNot { it.attribution.providerId == providerId }
        store.saveCachedEntries(remaining)
        _snapshots.value = _snapshots.value.copy(
            entries = remaining,
            sourceErrors = _snapshots.value.sourceErrors - providerId,
        )
    }

    // ─── refresh ──────────────────────────────────────────────────

    /**
     * Shared body of [start] + [refresh]. Per pinned source: verify
     * the manifest against the pinned key, then verify each declared
     * catalog's snapshot against the retained chain anchor
     * ([AcceptedCatalogSnapshot]). Unpinned sources are skipped —
     * nothing is trusted before TOFU confirmation. One failing source
     * doesn't block the others; the run fails (and throws the first
     * error) only when *every* pinned source failed.
     */
    private suspend fun refreshInternal() {
        val configuration = mutex.withLock {
            _snapshots.value = _snapshots.value.copy(fetchStatus = DiscoveryFetchStatus.Fetching)
            _snapshots.value.configuration
        }
        val pinned = configuration.sources.filter { it.pinnedOperatorKeyHex != null }

        val refreshedSources = mutableListOf<DiscoverySource>()
        val collected = mutableListOf<AttributedCatalogEntry>()
        val sourceErrors = mutableMapOf<String, DiscoverySourceError>()
        val notesByProvider = mutableMapOf<String, List<String>>()
        var firstError: Throwable? = null
        var failures = 0

        for (source in pinned) {
            try {
                val result = refreshSource(source)
                refreshedSources.add(result.source)
                collected.addAll(result.entries)
                if (result.notes.isNotEmpty()) notesByProvider[source.providerId] = result.notes
            } catch (e: Throwable) {
                failures += 1
                if (firstError == null) firstError = e
                refreshedSources.add(source) // keep pins + anchors intact
                // Trust findings (signature / chain / expiry) chip as
                // integrity problems in the settings UI; anything else
                // reads as a plain fetch failure.
                sourceErrors[source.providerId] = DiscoverySourceError(
                    message = e.message ?: e.javaClass.simpleName,
                    isIntegrity = e is DiscoveryTrustError,
                )
            }
        }

        if (pinned.isNotEmpty() && failures == pinned.size) {
            val error = firstError ?: IllegalStateException("discovery refresh failed")
            mutex.withLock {
                _snapshots.value = _snapshots.value.copy(
                    fetchStatus = DiscoveryFetchStatus.Failed(error.message ?: ""),
                    sourceErrors = sourceErrors.toMap(),
                )
            }
            throw error
        }

        mutex.withLock {
            val current = _snapshots.value.configuration
            // Re-merge refreshed chain anchors into the *current*
            // configuration (a concurrent add/remove wins on the
            // source list; we only carry forward acceptedCatalogs).
            val byProvider = refreshedSources.associateBy { it.providerId }
            val mergedSources = current.sources.map { byProvider[it.providerId] ?: it }
            val merged = current.copy(sources = mergedSources)
            if (merged != current) store.saveConfiguration(merged)
            val liveProviderIds = merged.sources.map { it.providerId }.toSet()
            val entries = collected.filter { it.attribution.providerId in liveProviderIds }
            store.saveCachedEntries(entries)
            _snapshots.value = _snapshots.value.copy(
                configuration = merged,
                entries = entries,
                fetchStatus = DiscoveryFetchStatus.Success,
                sourceErrors = sourceErrors.filterKeys { it in liveProviderIds },
                sourceNotes = notesByProvider.filterKeys { it in liveProviderIds },
            )
        }
    }

    /** One source's refresh result: updated chain anchors, attributed
     *  entries, and the §1/§6/§9 integrity/completeness notes. */
    private data class SourceRefreshResult(
        val source: DiscoverySource,
        val entries: List<AttributedCatalogEntry>,
        val notes: List<String>,
    )

    /** Fetch + verify one pinned source's manifest and every declared
     *  catalog snapshot. Returns the source with updated chain
     *  anchors plus its attributed entries. */
    private suspend fun refreshSource(
        source: DiscoverySource,
    ): SourceRefreshResult {
        val manifestRaw = fetcher.fetch(source.url, DiscoveryProfile.MAX_MANIFEST_BYTES)
        val verified = DiscoveryTrust.verifyProviderManifest(
            raw = manifestRaw,
            pinnedOperatorKeyHex = source.pinnedOperatorKeyHex,
            now = now(),
        )
        // Refresh-time identity match (§6): the URL must still serve
        // the pinned provider. `seat` and `implementationProfileId`
        // are already pinned to this profile's constants by
        // `verifyProviderManifest`; `providerId` is the per-source
        // identity that can drift.
        if (verified.manifest.providerId != source.providerId) {
            throw DiscoveryTrustError.ProviderManifestInvalid(
                "providerId ${verified.manifest.providerId} does not match the pinned source " +
                    "${source.providerId} — the URL now serves a different provider"
            )
        }
        // §1/§9 surfacing: skipped descriptors and audience skips are
        // notes on the source, so a partly or wholly skipped manifest
        // never reads as a silently smaller (or empty) one.
        val notes = mutableListOf<String>()
        if (verified.skippedCatalogIndexes.isNotEmpty()) {
            notes.add("${verified.skippedCatalogIndexes.size} malformed catalog(s) were skipped")
        }
        if (verified.audienceSkippedCatalogIndexes.isNotEmpty()) {
            notes.add("${verified.audienceSkippedCatalogIndexes.size} non-public catalog(s) were skipped")
        }

        val entries = mutableListOf<AttributedCatalogEntry>()
        val accepted = source.acceptedCatalogs.toMutableMap()
        for (catalog in verified.catalogs) {
            val snapshotRaw = fetcher.fetch(catalog.snapshot, DiscoveryProfile.MAX_SNAPSHOT_BYTES)
            // §8 retained state: last accepted digest + sequence, and
            // the previous manifest's policy declaration for the §4.2
            // transition grace. The §6 four-case comparison (no-op
            // refresh / successor / rollback / forward jump) lives in
            // DiscoveryTrust now.
            val previous = accepted[catalog.catalogId]?.let {
                AcceptedSnapshotRef(
                    digest = it.digest,
                    sequence = it.sequence,
                    previousPolicyDigest = it.policyDigest,
                )
            }
            val result = DiscoveryTrust.verifySnapshot(
                raw = snapshotRaw,
                manifest = verified,
                previous = previous,
                now = now(),
            )
            accepted[catalog.catalogId] = AcceptedCatalogSnapshot(
                digest = result.digest,
                sequence = result.snapshot.sequence,
                acceptedAt = now().toString(),
                policyDigest = catalog.policy,
            )
            when (val outcome = result.outcome) {
                is SnapshotChainOutcome.ForwardJumpWithNote -> notes.add(
                    "catalog ${catalog.catalogId}: ${outcome.missed} intermediate " +
                        "publication(s) could not be verified"
                )
                else -> Unit
            }
            if (result.policyTransition) {
                notes.add("catalog ${catalog.catalogId}: the provider is transitioning to a new policy")
            }
            if (result.skippedEntryIndexes.isNotEmpty()) {
                // §9 result_incomplete: the shrunken set must never be
                // presented as the whole catalog.
                notes.add(
                    "catalog ${catalog.catalogId}: ${result.skippedEntryIndexes.size} " +
                        "listing(s) could not be read and were skipped"
                )
            }
            result.entries.mapTo(entries) { entry ->
                AttributedCatalogEntry(
                    entry = entry,
                    attribution = SourceAttribution(
                        providerId = verified.manifest.providerId,
                        sourceLabel = source.label,
                        catalogId = catalog.catalogId,
                        snapshotDigest = result.digest,
                        relationship = entry.relationship,
                        placement = entry.placement,
                    ),
                )
            }
        }
        return SourceRefreshResult(
            source = source.copy(acceptedCatalogs = accepted),
            entries = entries,
            notes = notes,
        )
    }

    // ─── internals ────────────────────────────────────────────────

    /** Shipped defaults minus explicit removals, appended after the
     *  persisted sources (persisted config wins on providerId). */
    private fun mergeDefaultsLocked(
        persisted: DiscoverySourcesConfiguration,
    ): DiscoverySourcesConfiguration {
        val known = persisted.sources.map { it.providerId }.toSet()
        val missingDefaults = defaultSources.filter {
            it.providerId !in known &&
                it.providerId !in persisted.removedDefaultProviderIds
        }
        if (missingDefaults.isEmpty()) return persisted
        return persisted.copy(sources = persisted.sources + missingDefaults)
    }

    /** Common path for every source mutator. Promotes
     *  [DiscoverySourcesConfiguration.hasUserInteracted] to `true`
     *  and persists atomically with the in-memory publish. */
    private suspend fun applyConfigurationLocked(updated: DiscoverySourcesConfiguration) {
        val withFlag = if (updated.hasUserInteracted) updated
                       else updated.copy(hasUserInteracted = true)
        store.saveConfiguration(withFlag)
        _snapshots.value = _snapshots.value.copy(configuration = withFlag)
    }

    private companion object {
        fun defaultLabelFor(url: String): String =
            try { URI(url).host ?: url } catch (_: Exception) { url }
    }
}
