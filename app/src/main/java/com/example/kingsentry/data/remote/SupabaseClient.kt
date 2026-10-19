package com.example.kingsentry.data.remote

import android.util.Log
import com.example.kingsentry.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/**
 * Singleton module for providing and initializing the Supabase client
 * using credentials loaded safely from BuildConfig (configured via local.properties).
 */
object SupabaseClientModule {

    /**
     * Lazily initialized [SupabaseClient] configured with [Postgrest] and [Auth] modules
     * with 60-second request timeout and auto-persisted session storage.
     */
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            requestTimeout = 60.seconds
            install(Postgrest)
            install(Auth) {
                autoSaveToStorage = true
                alwaysAutoRefresh = true
            }
            install(io.github.jan.supabase.storage.Storage)
        }
    }

    /**
     * Attempts to sign in to Supabase Auth using the provided [emailInput] and [passwordInput].
     * Uses the email AS-IS without domain modification. If sign-in fails, falls back to sign-up.
     * Never crashes or logs passwords. Returns true if an authenticated session exists.
     */
    suspend fun signInToSupabase(emailInput: String, passwordInput: String): Boolean {
        val targetEmail = emailInput.trim().lowercase()
        if (targetEmail.isBlank() || passwordInput.isBlank()) {
            Log.w("SupabaseAuth", "Email or password is blank. Cannot authenticate with Supabase.")
            return false
        }

        // 1. Attempt Sign-In
        try {
            Log.d("SupabaseAuth", "Attempting sign-in for $targetEmail...")
            client.auth.signInWith(Email) {
                email = targetEmail
                password = passwordInput
            }
            val user = client.auth.currentUserOrNull()
            if (user != null) {
                Log.d("SupabaseAuth", "Signed in: ${user.id}")
                return true
            }
        } catch (e: Exception) {
            Log.w("SupabaseAuth", "Sign-in failed for $targetEmail (${e.message}), attempting sign-up...")
        }

        // 2. Fallback: Attempt Sign-Up
        return try {
            client.auth.signUpWith(Email) {
                email = targetEmail
                password = passwordInput
                data = buildJsonObject {
                    put("username", targetEmail.substringBefore("@"))
                }
            }
            val user = client.auth.currentUserOrNull()
            val success = user != null
            if (success) {
                Log.d("SupabaseAuth", "Signed up: ${user.id}")
            } else {
                Log.w("SupabaseAuth", "Sign-up executed for $targetEmail but currentUser is null.")
            }
            success
        } catch (e: Exception) {
            Log.e("SupabaseAuth", "Sign-up also failed for $targetEmail: ${e.message}", e)
            false
        }
    }
}
