package app.onym.android.chats

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.onym.android.design.LocalOnymTokens
import app.onym.android.group.GroupRulesStanding
import app.onym.android.strings.R

/**
 * The mark beside a member's name, and the same words on their proof
 * sheet.
 *
 * A small presentation type rather than a function on one of the two
 * screens that need it: a shared vocabulary living inside one of its
 * callers is a vocabulary waiting to be copied. On iOS it lived on the
 * members screen until the proof sheet needed it too.
 *
 * The failing cases read differently because they *are* different, and
 * the reader's next move differs with them: an unsigned request is
 * usually an older app; a signature over rules this device doesn't hold
 * can't be checked either way, so it claims nothing and is coloured
 * neutrally; a signature that fails against rules we *do* hold is
 * neither, and is the only one that should give anyone pause.
 */
data class GroupRulesMark(
    val icon: ImageVector,
    val text: String,
    val color: Color,
)

/**
 * `null` where the standing has nothing to report — see
 * [GroupRulesStanding.hasSomethingToShow]. "Not applicable" on every row
 * of every group without rules is noise, and the row keeps the BLS
 * prefix it always showed.
 */
@Composable
@ReadOnlyComposable
fun groupRulesMark(standing: GroupRulesStanding): GroupRulesMark? {
    val tokens = LocalOnymTokens.current
    return when (standing) {
        GroupRulesStanding.NO_RULES, GroupRulesStanding.NOT_COLLECTED -> null
        GroupRulesStanding.AUTHOR -> GroupRulesMark(
            Icons.Outlined.Edit,
            stringResource(R.string.rules_standing_author),
            tokens.text2,
        )
        GroupRulesStanding.SIGNED -> GroupRulesMark(
            Icons.Outlined.Verified,
            stringResource(R.string.rules_standing_signed),
            tokens.green,
        )
        GroupRulesStanding.SIGNED_EARLIER_VERSION -> GroupRulesMark(
            Icons.Outlined.Schedule,
            stringResource(R.string.rules_standing_earlier_version),
            tokens.text2,
        )
        GroupRulesStanding.DID_NOT_SIGN -> GroupRulesMark(
            Icons.Outlined.RemoveCircleOutline,
            stringResource(R.string.rules_standing_did_not_sign),
            tokens.amber,
        )
        GroupRulesStanding.UNKNOWN_RULES -> GroupRulesMark(
            Icons.Outlined.HelpOutline,
            stringResource(R.string.rules_standing_unknown_rules),
            tokens.text2,
        )
        GroupRulesStanding.DOES_NOT_VERIFY -> GroupRulesMark(
            Icons.Filled.Warning,
            stringResource(R.string.rules_standing_does_not_verify),
            tokens.red,
        )
    }
}

/**
 * What the mark means for this member, in a sentence.
 *
 * Written without a subject pronoun. These strings are read on your
 * *own* row as often as on anyone else's — the member checking their own
 * agreement is the case this screen exists for, and it sits next to a
 * "(you)" pill — so a third-person vocabulary said "their signature
 * doesn't check out" about the reader. The alternative was a
 * second-person variant of every line behind an `isSelf` flag: twice the
 * strings for translators and a branch to get wrong.
 */
@Composable
@ReadOnlyComposable
fun groupRulesExplanation(standing: GroupRulesStanding): String? = when (standing) {
    // Unreachable: `groupRulesMark` is null for both, so those rows get
    // no chevron and this sheet has no way to open on them. Strings
    // nothing can display are strings a translator still has to render.
    GroupRulesStanding.NO_RULES, GroupRulesStanding.NOT_COLLECTED -> null
    GroupRulesStanding.AUTHOR -> stringResource(R.string.rules_explanation_author)
    GroupRulesStanding.SIGNED -> stringResource(R.string.rules_explanation_signed)
    GroupRulesStanding.SIGNED_EARLIER_VERSION ->
        stringResource(R.string.rules_explanation_earlier_version)
    GroupRulesStanding.DID_NOT_SIGN -> stringResource(R.string.rules_explanation_did_not_sign)
    GroupRulesStanding.UNKNOWN_RULES -> stringResource(R.string.rules_explanation_unknown_rules)
    GroupRulesStanding.DOES_NOT_VERIFY ->
        stringResource(R.string.rules_explanation_does_not_verify)
}
