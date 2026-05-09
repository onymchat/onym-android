package chat.onym.android.chain

/**
 * Decodes the `Error(Contract, #N)` token that Soroban embeds in
 * relayer error bodies (e.g. an HTTP 502 with a `transaction
 * simulation failed: HostError: Error(Contract, #7)` payload) and
 * maps the integer code to the matching contract-error variant name
 * for the [SepGroupType] in question.
 *
 * The code → name table mirrors the `#[contracterror]` enums in the
 * `onym-contracts` repo (`plonk/sep-tyranny/src/lib.rs` for the
 * Tyranny table). Keep it in sync with that enum; an unknown code
 * returns the code with a `null` name and the call-site falls back
 * to the raw relayer body.
 */
data class SorobanContractErrorMatch(val code: Int, val name: String?)

private val CONTRACT_ERROR_PATTERN = Regex("""Error\(Contract,\s*#(\d+)\)""")

private val TYRANNY_ERROR_NAMES: Map<Int, String> = mapOf(
    1 to "NotInitialized",
    2 to "AlreadyInitialized",
    4 to "GroupAlreadyExists",
    5 to "GroupNotFound",
    7 to "InvalidProof",
    8 to "InvalidTier",
    10 to "PublicInputsMismatch",
    11 to "InvalidEpoch",
    12 to "ProofReplay",
    13 to "TierGroupLimitReached",
    14 to "AdminOnly",
    15 to "InvalidCommitmentEncoding",
)

private fun errorNameTable(contractType: SepGroupType): Map<Int, String>? = when (contractType) {
    SepGroupType.TYRANNY -> TYRANNY_ERROR_NAMES
    // Anarchy / 1-on-1 / democracy / oligarchy: name tables can be
    // added here when their `#[contracterror]` enums stabilise. Until
    // then we still surface the numeric code (with a `null` name) so
    // operators can cross-reference manually.
    else -> null
}

/**
 * Scans [message] for the first `Error(Contract, #N)` occurrence and
 * resolves the variant name for [contractType]. Returns `null` if
 * the pattern is absent.
 */
fun parseSorobanContractError(
    message: String,
    contractType: SepGroupType,
): SorobanContractErrorMatch? {
    val match = CONTRACT_ERROR_PATTERN.find(message) ?: return null
    val code = match.groupValues[1].toIntOrNull() ?: return null
    val name = errorNameTable(contractType)?.get(code)
    return SorobanContractErrorMatch(code = code, name = name)
}

/**
 * Prepends a short summary of the matched contract error (when one
 * is found and named) to the raw relayer message. The raw body is
 * preserved verbatim so QA can still cross-reference event logs.
 */
fun decorateContractErrorMessage(rawMessage: String, contractType: SepGroupType): String {
    val match = parseSorobanContractError(rawMessage, contractType) ?: return rawMessage
    val name = match.name ?: return rawMessage
    return "contract returned $name (#${match.code}); $rawMessage"
}
