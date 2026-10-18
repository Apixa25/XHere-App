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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
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
 * Button-flow Google sign-in. The nonce sent to Google is the SHA-256 of the
 * raw nonce; Supabase receives the raw nonce and hashes it the same way.
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
        val rawNonce = GoogleNonce.raw()
        val option = GetSignInWithGoogleOption.Builder(config.googleWebClientId)
            .setNonce(GoogleNonce.hash(rawNonce))
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
                GoogleSignInAttempt.Success(idToken = token, rawNonce = rawNonce)
            } else {
                GoogleSignInAttempt.Failed(context.getString(R.string.auth_failed))
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInAttempt.Cancelled
        } catch (e: NoCredentialException) {
            Log.i(TAG, "Google sign-in: no credential")
            GoogleSignInAttempt.Failed(context.getString(R.string.auth_failed))
        } catch (e: GoogleIdTokenParsingException) {
            Log.i(TAG, "Google sign-in: token parse failed")
            GoogleSignInAttempt.Failed(context.getString(R.string.auth_failed))
        } catch (e: GetCredentialException) {
            Log.i(TAG, "Google sign-in failed: ${e.javaClass.simpleName}")
            GoogleSignInAttempt.Failed(context.getString(R.string.auth_failed))
        }
    }

    private companion object {
        const val TAG = "XHereAuth"
    }
}

internal object GoogleNonce {
    fun raw(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
