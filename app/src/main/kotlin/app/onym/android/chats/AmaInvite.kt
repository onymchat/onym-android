package app.onym.android.chats

/**
 * The Onym AMA group's invite — the same capability the iOS build
 * ships, copied byte for byte from
 * onym-ios/Packages/OnymChatsUI/Sources/OnymChatsUI/ChatsView.swift.
 *
 * A plain invite, not a special channel: the empty chats screen opens
 * it the way a scanned code would, so joining still goes through the
 * join confirmation (rules, name, Send) and the host's approval. If it
 * ever stops decoding, the test beside this file fails rather than the
 * screen quietly losing its only way in.
 */
internal object AmaInvite {
    const val LINK: String =
        "https://onym.app/join?c=eyJncm91cF9pZCI6Ik5oNkVUZEN3MW9xUW1NaFY1dk9kUVNvUGxNdk5YN1E3dnh6QldsTHZlY1E9IiwiZ3JvdXBfbmFtZSI6Ik9ueW0gQU1BIiwiaW50cm9fcHViIjoieWM2TmVsUXFSNjMyUFdsdXZaWjllaUNpOUFjUnFOcHhiUStUZjlTakNGMD0iLCJydWxlcyI6ItCe0YLQstC10YLRgdGC0LLQtdC90L3QvtGB0YLRjDog0LXRgdC70Lgg0JLQsNC8INC30LDQtNCw0LvQuCDQstC-0L_RgNC-0YEsINCS0Ysg0L7QsdGP0LfRg9C10YLQtdGB0Ywg0L7RgtCy0LXRgtC40YLRjC4g0J7RgtCy0LXRgiDCq9C90LUg0LfQvdCw0Y7CuyDQtNC-0L_Rg9GB0YLQuNC8LiJ9"
}
