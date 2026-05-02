package chat.onym.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import chat.onym.android.identity.IdentityBootstrapScreen
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore

class MainActivity : ComponentActivity() {
    private val repository: IdentityRepository by lazy {
        IdentityRepository(
            store = IdentitySecretStore(applicationContext)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IdentityBootstrapScreen(repository = repository)
                }
            }
        }
    }
}
