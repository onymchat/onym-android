package app.onym.android.discovery

/**
 * Loader for the shared Discovery conformance fixtures under
 * `src/test/resources/fixtures/`, copied byte-for-byte from
 * `onym-discovery/tests/fixtures/` (the Rust reference
 * implementation). NOTE: this is the first byte-fixture convention in
 * this repo — earlier modules pin constants inline; Discovery's
 * cross-language byte-agreement gate (Rust ↔ Swift ↔ Kotlin all
 * verifying the same `canonical-bytes.bin`) needs the exact files.
 */
object DiscoveryFixtures {

    /** The operator key hex the fixtures were signed with. */
    const val OPERATOR_KEY_HEX =
        "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c"

    fun load(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing test fixture: fixtures/$name"
        }.use { it.readBytes() }
}
