// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySupabaseProvider.kt
package com.nuvio.app.features.watchparty

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime

/**
 * Separate Supabase project just for watch parties (spec decision) — never reuse
 * the main [com.nuvio.app.core.network.SupabaseConfig] credentials here.
 */
object WatchPartySupabaseProvider {
    val isConfigured: Boolean
        get() = WatchPartySupabaseConfig.URL.isNotBlank() && WatchPartySupabaseConfig.ANON_KEY.isNotBlank()

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = WatchPartySupabaseConfig.URL,
            supabaseKey = WatchPartySupabaseConfig.ANON_KEY,
        ) {
            // No Auth module installed, so REST fallbacks (e.g. broadcast while the
            // channel is not SUBSCRIBED) would send only the apikey header. Kong's
            // legacy transform then forwards a bare JWT as Authorization, which
            // self-hosted Realtime rejects with a 500. Providing the anon key as
            // access token makes those calls carry "Authorization: Bearer <jwt>".
            accessToken = { WatchPartySupabaseConfig.ANON_KEY }
            install(Realtime)
        }
    }
}
