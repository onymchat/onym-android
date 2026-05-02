package chat.onym.android

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Registers the BouncyCastle JCE provider once at process start. We use
 * BC for Curve25519 (Ed25519 + X25519) raw-key APIs that the JDK doesn't
 * expose — see [chat.onym.android.identity.IdentityRepository].
 *
 * BC must be installed BEFORE any provider lookup that picks the first
 * match (some JCA APIs cache providers per-thread). Doing it in
 * Application.onCreate() guarantees ordering.
 */
class OnymApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Insert at position 1 so BC is preferred for algorithms it
        // implements (X25519, Ed25519 raw-key params). The Android
        // platform's Conscrypt provider stays first for everything else.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
    }
}
