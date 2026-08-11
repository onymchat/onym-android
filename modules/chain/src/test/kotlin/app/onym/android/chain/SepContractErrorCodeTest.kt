package app.onym.android.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A contract refusal reaches the app as a Soroban diagnostic dump, not
 * a structured field. These pin the one thing we read out of it — the
 * `Error(Contract, #N)` number — because that is what turns "the chain
 * said no" into something the UI can explain.
 *
 * Mirrors `SEPContractErrorCodeTests.swift` in onym-ios.
 */
class SepContractErrorCodeTest {

    /**
     * The real body from a founder who approved a joiner seconds after
     * creating the group, before `create_group` had been included in a
     * ledger. Abridged in the middle; the shape around the marker is
     * verbatim.
     */
    private val groupNotFoundBody = """
        {"accepted":false,"message":"❌ error: transaction simulation failed: HostError:
        Error(Contract, #5)\n\nEvent log (newest first):\n   0: [Diagnostic Event]
        contract:CAFX4A2KLOK7RE5QSPTDQ3UBCWOUSBPPFC2PU7M6ZP53GPGRCL67HNR3,
        topics:[error, Error(Contract, #5)], data:\"escalating Ok(ScErrorType::Contract)
        frame-exit to Err\"\n   1: [Diagnostic Event] topics:[fn_call, update_commitment]"}
    """.trimIndent()

    @Test
    fun readsTheContractErrorNumberOutOfADiagnosticDump() {
        assertEquals(
            SepContractErrorCode.GROUP_NOT_FOUND.code,
            SepContractErrorCode.parse(groupNotFoundBody),
        )
    }

    @Test
    fun theCodeIsReachableThroughTheTransportError() {
        val error = SepContractError.BadStatus(code = 502, body = groupNotFoundBody)
        assertEquals(5u, error.contractErrorCode)
    }

    /** The numbers are a wire contract with `onym-contracts`; a silent
     *  renumbering here would mis-explain every failure. */
    @Test
    fun codesMatchTheContractsEnum() {
        assertEquals(1u, SepContractErrorCode.NOT_INITIALIZED.code)
        assertEquals(4u, SepContractErrorCode.GROUP_ALREADY_EXISTS.code)
        assertEquals(5u, SepContractErrorCode.GROUP_NOT_FOUND.code)
        assertEquals(7u, SepContractErrorCode.INVALID_PROOF.code)
        assertEquals(10u, SepContractErrorCode.PUBLIC_INPUTS_MISMATCH.code)
        assertEquals(12u, SepContractErrorCode.PROOF_REPLAY.code)
        assertEquals(14u, SepContractErrorCode.ADMIN_ONLY.code)
    }

    @Test
    fun multiDigitCodesAreNotTruncated() {
        assertEquals(15u, SepContractErrorCode.parse("HostError: Error(Contract, #15) x"))
    }

    /** Text-matching a diagnostic is best-effort by nature. A miss must
     *  answer null so the caller falls back to showing the raw message
     *  rather than confidently mislabelling it. */
    @Test
    fun aBodyWithoutTheMarkerYieldsNothing() {
        assertNull(SepContractErrorCode.parse("connection reset by peer"))
        assertNull(SepContractErrorCode.parse(""))
        assertNull(SepContractErrorCode.parse("Error(Contract, #)"))
    }

    @Test
    fun nonStatusErrorsCarryNoContractCode() {
        assertNull(SepContractError.MalformedResponse(RuntimeException("x")).contractErrorCode)
    }
}
