package world.xhere.app.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import world.xhere.app.R
import world.xhere.app.config.ClientConfig

/** ID token from Credential Manager, or a test double. The shared module exchanges it with Supabase. */
interface GoogleIdTokenSource {
    suspend fun request(): GoogleSignInAttempt
}

sealed interface GoogleSignInAttempt {
    data class Success(val idToken: String, val rawNonce: String) : GoogleSignInAttempt
    data object Cancelled : GoogleSignInAttempt
    data class Failed(val message: String) : GoogleSignInAttempt
}

/**
 * Button-flow Google sign-in.
 *
 * One [GoogleNoncePair] is created inside this call and is not stored on the
 * class, so a retry or a new ViewModel cannot reuse it or mint a second one
 * before Supabase sees the first. Google's `setNonce` receives the lowercase
 * hex SHA-256. Supabase receives the raw value and hashes it the same way
 * (`fmt.Sprintf("%x", sha256.Sum256(raw))`).
 */
class CredentialManagerGoogleSignIn(
    private val context: Context,
    private val config: ClientConfig,
    private val credentials: CredentialManager = CredentialManager.create(context),
) : GoogleIdTokenSource {
    override suspend fun request(): GoogleSignInAttempt {
        if (!config.googleReady) {
            return GoogleSignInAttempt.Failed(context.getString(R.string.auth_google_unconfigured))
        }
        val bound = bindGoogleNonce()
        val option = GetSignInWithGoogleOption.Builder(config.googleWebClientId)
            .setNonce(bound.googleSetNonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return try {
            val result = credentials.getCredential(context, request)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                GoogleSignInAttempt.Success(idToken = token, rawNonce = bound.supabaseNonce)
            } else {
                GoogleSignInAttempt.Failed(context.getString(R.string.auth_failed))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInAttempt.Cancelled
        } catch (e: NoCredentialException) {
            Log.i(TAG, "Google sign-in: no credential")
            GoogleSignInAttempt.Failed(context.getString(R.string.auth_google_no_account))
        } catch (e: GoogleIdTokenParsingException) {
            Log.i(TAG, "Google sign-in: token parse failed")
            GoogleSignInAttempt.Failed(context.getString(R.string.auth_failed))
        } catch (e: GetCredentialException) {
            when (classifyCredentialFailure(e.type, e.message.orEmpty())) {
                GoogleSignInProblem.Cancelled -> GoogleSignInAttempt.Cancelled
                GoogleSignInProblem.NoAccount ->
                    GoogleSignInAttempt.Failed(context.getString(R.string.auth_google_no_account))
                GoogleSignInProblem.Network ->
                    GoogleSignInAttempt.Failed(context.getString(R.string.auth_network))
                GoogleSignInProblem.Failed -> {
                    Log.i(TAG, "Google sign-in failed: ${e.type}")
                    GoogleSignInAttempt.Failed(context.getString(R.string.auth_failed))
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Google sign-in: network")
            GoogleSignInAttempt.Failed(context.getString(R.string.auth_network))
        } catch (e: Exception) {
            Log.i(TAG, "Google sign-in failed: ${e.javaClass.simpleName}")
            GoogleSignInAttempt.Failed(context.getString(R.string.auth_failed))
        }
    }

    private companion object {
        const val TAG = "XHereAuth"
    }
}

/**
 * Values for one attempt. [googleSetNonce] is what Credential Manager's
 * `setNonce` receives. [supabaseNonce] is what `signInWith(IDToken)` receives.
 * They come from the same pair: hex SHA-256(raw) and raw. Neither is hashed again.
 */
internal data class BoundGoogleNonce(
    val googleSetNonce: String,
    val supabaseNonce: String,
)

internal fun bindGoogleNonce(pair: GoogleNoncePair = GoogleNonce.fresh()): BoundGoogleNonce =
    BoundGoogleNonce(
        googleSetNonce = pair.hashed,
        supabaseNonce = pair.raw,
    )

/**
 * [raw] goes to Supabase. [hashed] is the lowercase hex SHA-256 of [raw] and
 * goes to Google. Constructing the pair hashes once.
 */
internal data class GoogleNoncePair(
    val raw: String,
    val hashed: String,
) {
    init {
        require(hashed == GoogleNonce.hash(raw)) {
            "Google setNonce must be the hex SHA-256 of the raw Supabase nonce"
        }
    }
}

internal object GoogleNonce {
    /** A new pair. Call once per button press. Do not cache it across retries. */
    fun fresh(): GoogleNoncePair {
        val raw = raw()
        return GoogleNoncePair(raw = raw, hashed = hash(raw))
    }

    fun raw(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Lowercase hex SHA-256 of the raw nonce's UTF-8 bytes.
     * Matches GoTrue: `fmt.Sprintf("%x", sha256.Sum256([]byte(rawNonce)))`.
     * Each byte is masked so a value over 127 stays two digits.
     */
    fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}

internal enum class GoogleSignInProblem {
    Cancelled,
    NoAccount,
    Network,
    Failed,
}

/** Credential Manager type and message, when the exception class itself is generic. */
internal fun classifyCredentialFailure(type: String, message: String): GoogleSignInProblem {
    val kind = type.lowercase()
    val text = message.lowercase()
    return when {
        "user_canceled" in kind || "cancelled" in text || "canceled" in text -> GoogleSignInProblem.Cancelled
        "no_credential" in kind ||
            "no credential" in text ||
            "no google account" in text ||
            "no eligible" in text -> GoogleSignInProblem.NoAccount
        "network" in kind || connectivityText(text) -> GoogleSignInProblem.Network
        else -> GoogleSignInProblem.Failed
    }
}

internal fun connectivityText(message: String?): Boolean {
    val text = message?.lowercase().orEmpty()
    if (text.isEmpty()) return false
    return "network" in text ||
        "timeout" in text ||
        "timed out" in text ||
        "unable to resolve" in text ||
        "failed to connect" in text ||
        "connection refused" in text ||
        "offline" in text ||
        "unreachable" in text
}

internal fun Throwable.isConnectivityFailure(): Boolean {
    val seen = HashSet<Int>()
    var current: Throwable? = this
    while (current != null && seen.add(System.identityHashCode(current))) {
        if (current is IOException) return true
        val name = current.javaClass.name
        if (name.endsWith("HttpRequestTimeoutException") ||
            name.endsWith("ConnectTimeoutException") ||
            name.contains("NetworkError")
        ) {
            return true
        }
        if (connectivityText(current.message)) return true
        current = current.cause
    }
    return false
}

internal fun Throwable.mentionsNonceMismatch(): Boolean {
    val seen = HashSet<Int>()
    var current: Throwable? = this
    while (current != null && seen.add(System.identityHashCode(current))) {
        val text = current.message?.lowercase().orEmpty()
        if ("nonces mismatch" in text || "invalid nonce" in text) return true
        current = current.cause
    }
    return false
}
