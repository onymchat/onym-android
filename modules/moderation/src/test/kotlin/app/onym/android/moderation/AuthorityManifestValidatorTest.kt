package app.onym.android.moderation

import app.onym.android.moderation.support.ModerationFixtures
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The consent-time validity conditions (Moderation.md §5.2), run
 * before enrollment and signing so an invalid manifest can never end
 * up pinned by a signed mandate. */
class AuthorityManifestValidatorTest {
    private val now: Instant = Instant.parse("2026-08-08T12:00:00Z")

    private fun signed(manifest: AuthorityManifest): SignedManifest =
        SignedManifest.forFixtures(
            manifest,
            ModerationJson.json.encodeToString(AuthorityManifest.serializer(), manifest)
                .encodeToByteArray(),
        )

    private fun expectRefusal(manifest: AuthorityManifest, needle: String) {
        try {
            AuthorityManifestValidator.validateForConsent(signed(manifest), now)
            fail("expected a refusal mentioning $needle")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains(needle))
        }
    }

    @Test
    fun `the fixture manifest validates`() {
        AuthorityManifestValidator.validateForConsent(
            ModerationFixtures.signedManifest(),
            now,
        )
    }

    @Test
    fun `a lapsed validUntil refuses consent`() {
        expectRefusal(
            ModerationFixtures.manifest(validUntil = "2020-01-01T00:00:00Z"),
            "validity",
        )
    }

    @Test
    fun `an unreadable validUntil refuses consent`() {
        expectRefusal(
            ModerationFixtures.manifest(validUntil = "sometime next year"),
            "validUntil",
        )
    }

    @Test
    fun `a manifest without violation classes refuses consent`() {
        expectRefusal(
            ModerationFixtures.manifest().copy(violationClasses = emptyList()),
            "classes",
        )
    }

    /** This client implements exactly one enforcement profile; a
     * manifest naming another must not be consented under it. */
    @Test
    fun `a foreign moderation profile refuses consent`() {
        expectRefusal(
            ModerationFixtures.manifest().copy(
                moderationProfileId = "onym:moderation-enforcement-profile:apple-devicecheck-v1",
            ),
            "profile",
        )
    }

    /** §5.2 constraint 2: a permanent class requires an external
     * appellate authority. */
    @Test
    fun `a permanent class without an external appellate refuses consent`() {
        val manifest = ModerationFixtures.manifest()
        val permanent = manifest.copy(
            violationClasses = listOf(manifest.violationClasses.first().copy(banTerm = "permanent")),
            appellate = null,
        )
        expectRefusal(permanent, "appellate")

        expectRefusal(permanent.copy(appellate = "self"), "appellate")

        // With a real appellate it validates.
        AuthorityManifestValidator.validateForConsent(
            signed(permanent.copy(appellate = "onym:component:appeals-court")),
            now,
        )
    }
}
