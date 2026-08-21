package app.onym.android.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import app.onym.android.design.LocalOnymTokens
import app.onym.android.group.JoinRequestApprover
import app.onym.android.strings.R

/**
 * What the thread needs to render one pending join request. Built by
 * the thread's ViewModel from `JoinRequestApprover.PendingRequest` plus
 * the approver's in-flight/error state, so the row stays a dumb
 * renderer — the same split [ChatSenderDisplay] uses for bubbles.
 */
data class ChatJoinRequestDisplay(
    /** `PendingRequest.id` — the key Accept / Decline dispatch on. */
    val requestId: String,
    val alias: String,
    /**
     * Short hex of the joiner's inbox pubkey. Shown so the founder can
     * confirm out-of-band who is actually asking: the alias is
     * self-asserted by the joiner and nothing stops them typing someone
     * else's name.
     */
    val fingerprint: String,
    /** A call is in flight: spinner on, both buttons disabled. */
    val isInFlight: Boolean,
    /** Whether this joiner agreed to the group's rules, already decided
     *  by [app.onym.android.group.JoinRequestApprover] against the
     *  group's own copy of the text. The row renders it and nothing
     *  more — a screen is the wrong place to be verifying signatures. */
    val agreement: JoinRequestApprover.RulesAgreement =
        JoinRequestApprover.RulesAgreement.NOT_REQUIRED,
    /** This request's last failure, if any. */
    val errorText: String? = null,
)

/**
 * Founder-only, in-thread prompt: "Alice wants to join" with Accept /
 * Decline.
 *
 * This replaces the separate "Join requests" screen. The request is
 * already cryptographically founder-only — it arrives sealed to an
 * intro key only the inviting device holds — so nothing here is hidden
 * from anyone who could otherwise see it; the row simply never has
 * content to render on a non-founder's device.
 *
 * Mirrors `ChatJoinRequestCell` in onym-ios.
 */
@Composable
fun ChatJoinRequestRow(
    display: ChatJoinRequestDisplay,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .testTag("chat_thread.join_request.${display.requestId}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_join_request_wants_to_join, display.alias),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Text(
            text = stringResource(R.string.chat_join_request_inbox, display.fingerprint),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Above the buttons on purpose: it is one of the two things this
        // decision turns on, and a founder who has already tapped Accept
        // is not helped by finding out underneath it.
        AgreementLine(display.agreement)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onDecline(display.requestId) },
                enabled = !display.isInFlight,
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_thread.join_request.decline.${display.requestId}"),
            ) {
                Text(stringResource(R.string.decline))
            }
            Button(
                onClick = { onAccept(display.requestId) },
                enabled = !display.isInFlight,
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_thread.join_request.accept.${display.requestId}"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (display.isInFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(
                        if (display.isInFlight) {
                            stringResource(R.string.chat_join_request_anchoring)
                        } else {
                            stringResource(R.string.chat_join_request_accept)
                        }
                    )
                }
            }
        }
        if (display.isInFlight) {
            // The on-chain admit is a PLONK proof plus a relayer
            // round-trip plus a Stellar confirmation — several seconds
            // of apparent nothing. Say so rather than letting it read
            // as a hung tap.
            Text(
                text = stringResource(R.string.approve_generating_proof),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val errorText = display.errorText
        if (errorText != null) {
            Text(
                text = errorText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(
                    "chat_thread.join_request.error.${display.requestId}"
                ),
            )
        }
    }
}

/**
 * The rules verdict, or nothing at all.
 *
 * Nothing when the group has no rules — there is nothing to report about
 * an agreement nobody was asked for, and an "n/a" line would be noise on
 * every request in every group without rules.
 *
 * The three failing cases read differently because they *are* different,
 * and the founder's next move differs with them: an unsigned request is
 * usually an older app and asking again fixes it; an other-rules
 * signature is someone who agreed to a previous wording; a signature
 * that doesn't verify is neither, and is the only one of the three that
 * should give a founder pause about the request itself.
 */
@Composable
private fun AgreementLine(agreement: JoinRequestApprover.RulesAgreement) {
    val tokens = LocalOnymTokens.current
    val text: String
    val color: Color
    when (agreement) {
        JoinRequestApprover.RulesAgreement.NOT_REQUIRED -> return
        JoinRequestApprover.RulesAgreement.AGREED -> {
            text = stringResource(R.string.chat_join_request_rules_agreed)
            color = tokens.green
        }
        JoinRequestApprover.RulesAgreement.AGREED_TO_OTHER_RULES -> {
            text = stringResource(R.string.chat_join_request_rules_other_version)
            color = tokens.amber
        }
        JoinRequestApprover.RulesAgreement.NOT_SIGNED -> {
            text = stringResource(R.string.chat_join_request_rules_unsigned)
            color = tokens.amber
        }
        JoinRequestApprover.RulesAgreement.INVALID -> {
            text = stringResource(R.string.chat_join_request_rules_invalid)
            color = MaterialTheme.colorScheme.error
        }
    }
    Text(
        text = text,
        fontSize = 12.sp,
        color = color,
        modifier = Modifier.testTag("chat_thread.join_request.rules"),
    )
}
