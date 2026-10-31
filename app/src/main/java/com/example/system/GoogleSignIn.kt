package com.example.system

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** "Sign in with Google" through Android's Credential Manager. */
object GoogleSignIn {
    class Cancelled : Exception("Sign-in cancelled")

    /** Shows Google's account picker and returns the chosen account's ID token. */
    suspend fun idToken(activity: Activity): String {
        // default_web_client_id comes from google-services.json (the project's web OAuth client).
        // It only exists once Google sign-in is switched on in the Firebase console, so it is looked up at run time.
        val resId = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (resId == 0) throw IllegalStateException("Google sign-in isn't set up for this build yet.")
        val option = GetSignInWithGoogleOption.Builder(activity.getString(resId)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            throw Cancelled()
        }
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw IllegalStateException("Google did not return an account.")
    }
}
