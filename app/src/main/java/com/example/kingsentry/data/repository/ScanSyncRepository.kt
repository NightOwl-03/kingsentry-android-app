package com.example.kingsentry.data.repository

import com.example.kingsentry.data.KingSentryDao
import com.example.kingsentry.data.remote.SupabaseClientModule
import io.github.jan.supabase.SupabaseClient

/**
 * Repository for managing scan synchronization.
 */
class ScanSyncRepository(
    private val dao: KingSentryDao,
    private val supabaseClient: SupabaseClient = SupabaseClientModule.client,
) {
    suspend fun markSynced(ids: List<Int>) {
        dao.markSynced(ids)
    }
}
