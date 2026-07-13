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
            install(Realtime)
        }
    }
}
