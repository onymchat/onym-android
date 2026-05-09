package chat.onym.android.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Tyranny `#[contracterror]` enum mapping so a silent drift
 * between this client and `sep-tyranny/src/lib.rs` shows up as a
 * test failure rather than a confusing user-facing error.
 *
 * The reference relayer body shape comes from issue #121:
 * `HostError: Error(Contract, #7)` for the failing `create_group`
 * call against contract `CAFX4A2K…`.
 */
class SorobanContractErrorTest {

    @Test
    fun parses_invalidProof_fromRelayerBody() {
        val body = """{"accepted":false,"message":"❌ error: transaction simulation failed: HostError: Error(Contract, #7)"}"""
        val match = parseSorobanContractError(body, SepGroupType.TYRANNY)
        assertEquals(7, match?.code)
        assertEquals("InvalidProof", match?.name)
    }

    @Test
    fun parses_otherTyrannyCodes() {
        listOf(
            1 to "NotInitialized",
            2 to "AlreadyInitialized",
            4 to "GroupAlreadyExists",
            5 to "GroupNotFound",
            8 to "InvalidTier",
            10 to "PublicInputsMismatch",
            11 to "InvalidEpoch",
            12 to "ProofReplay",
            13 to "TierGroupLimitReached",
            14 to "AdminOnly",
            15 to "InvalidCommitmentEncoding",
        ).forEach { (code, expectedName) ->
            val match = parseSorobanContractError("Error(Contract, #$code)", SepGroupType.TYRANNY)
            assertEquals("code", code, match?.code)
            assertEquals("name for #$code", expectedName, match?.name)
        }
    }

    @Test
    fun unknownTyrannyCode_returnsNullName() {
        // Code 99 isn't in the enum — surface the number, drop the
        // name so callers don't pretend they know what it is.
        val match = parseSorobanContractError("Error(Contract, #99)", SepGroupType.TYRANNY)
        assertEquals(99, match?.code)
        assertNull(match?.name)
    }

    @Test
    fun returnsNull_whenNoContractErrorPattern() {
        assertNull(parseSorobanContractError("garden-variety transport error", SepGroupType.TYRANNY))
        assertNull(parseSorobanContractError("HTTP 502 boom", SepGroupType.TYRANNY))
    }

    @Test
    fun nonTyranny_contractTypes_preserveCode_butLeaveNameNull() {
        // No name table for these yet — the helper should still
        // surface the integer so operators can cross-reference.
        listOf(SepGroupType.ANARCHY, SepGroupType.ONE_ON_ONE).forEach { type ->
            val match = parseSorobanContractError("Error(Contract, #7)", type)
            assertEquals("code for $type", 7, match?.code)
            assertNull("name for $type", match?.name)
        }
    }

    @Test
    fun decorateContractErrorMessage_prependsName_forKnownCode() {
        val raw = """contract call returned HTTP 502: {"message":"HostError: Error(Contract, #7)"}"""
        val decorated = decorateContractErrorMessage(raw, SepGroupType.TYRANNY)
        assertTrue("expected name prefix in: $decorated", decorated.startsWith("contract returned InvalidProof (#7);"))
        assertTrue("expected raw body preserved in: $decorated", decorated.contains(raw))
    }

    @Test
    fun decorateContractErrorMessage_passthrough_whenUnknownContractType() {
        val raw = "Error(Contract, #7)"
        // No name table for ANARCHY today → no decoration.
        val decorated = decorateContractErrorMessage(raw, SepGroupType.ANARCHY)
        assertEquals(raw, decorated)
    }

    @Test
    fun decorateContractErrorMessage_passthrough_whenNoMatch() {
        val raw = "Couldn't reach the relayer: timeout"
        val decorated = decorateContractErrorMessage(raw, SepGroupType.TYRANNY)
        assertEquals(raw, decorated)
    }
}
