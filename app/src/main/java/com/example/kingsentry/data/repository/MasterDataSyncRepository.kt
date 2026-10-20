package com.example.kingsentry.data.repository

import android.util.Log
import com.example.kingsentry.data.DiseaseDictionary
import com.example.kingsentry.data.KingSentryDao
import com.example.kingsentry.data.TreatmentRecommendation
import com.example.kingsentry.data.remote.DiseaseDictionaryDto
import com.example.kingsentry.data.remote.IdMap
import com.example.kingsentry.data.remote.SupabaseClientModule
import com.example.kingsentry.data.remote.TreatmentRecommendationDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result data class for master reference data synchronization.
 */
data class MasterSyncResult(
    val diseaseMap: Map<Int, String>,
    val diseaseCount: Int,
    val treatmentCount: Int,
)

/**
 * Repository responsible for synchronizing local Room reference master data
 * ([DiseaseDictionary] and [TreatmentRecommendation]) with Supabase PostgreSQL tables,
 * and resolving local integer disease IDs to Supabase UUIDs.
 */
class MasterDataSyncRepository(
    private val dao: KingSentryDao,
    private val supabaseClient: SupabaseClient = SupabaseClientModule.client,
) {

    companion object {
        private const val TAG = "MasterDataSync"
    }

    /**
     * Synchronizes local reference data (diseases and treatment recommendations) to Supabase,
     * returning a [MasterSyncResult] containing complete mapping from local disease IDs to Supabase UUIDs.
     * Throws an exception if any upsert fails or if any disease/treatment mapping is incomplete.
     */
    suspend fun syncMasterDataAndGetDiseaseMap(): MasterSyncResult = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch local diseases from Room
            val localDiseases = dao.getAllDiseasesList()
            var upsertedDiseaseCount = 0

            if (localDiseases.isNotEmpty()) {
                val diseaseDtos = localDiseases.map { d ->
                    DiseaseDictionaryDto(
                        sourceLocalId = d.id,
                        diseaseName = d.diseaseName,
                        description = d.description,
                        symptoms = d.symptoms,
                        possibleCauses = d.possibleCauses,
                        preventionTips = d.preventionTips,
                        referenceName = d.referenceName,
                        referenceUrl = d.referenceUrl,
                    )
                }

                Log.d(TAG, "Upserting ${diseaseDtos.size} local disease dictionary record(s) to Supabase...")
                try {
                    supabaseClient.from("disease_dictionary").upsert(diseaseDtos) {
                        onConflict = "source_local_id"
                    }
                    upsertedDiseaseCount = diseaseDtos.size
                } catch (e: Exception) {
                    Log.e(TAG, "Disease dictionary upsert failed: ${e.message}", e)
                    throw e
                }
            }

            // 2. Fetch Supabase disease UUID mappings
            val diseaseRows = supabaseClient.from("disease_dictionary")
                .select(Columns.list("id", "source_local_id"))
                .decodeList<IdMap>()
            val diseaseMap: Map<Int, String> = diseaseRows.associate { it.sourceLocalId to it.id }
            Log.d(TAG, "Loaded ${diseaseMap.size} disease UUID mapping(s) from Supabase.")

            // Verify ALL local diseases are mapped to a Supabase UUID
            val localDiseaseIds = localDiseases.map { it.id }.toSet()
            val missingDiseaseIds = localDiseaseIds - diseaseMap.keys
            if (missingDiseaseIds.isNotEmpty()) {
                val msg = "Master data sync incomplete: Supabase is missing disease UUIDs for local disease IDs: $missingDiseaseIds"
                Log.e(TAG, msg)
                throw IllegalStateException(msg)
            }

            // 3. Sync treatment recommendations
            val localTreatments = dao.getAllTreatmentsList()
            var upsertedTreatmentCount = 0

            if (localTreatments.isNotEmpty()) {
                val unmappedTreatments = localTreatments.filter { diseaseMap[it.diseaseIdFk] == null }
                if (unmappedTreatments.isNotEmpty()) {
                    val msg = "Master data sync failed: ${unmappedTreatments.size} treatment(s) skipped due to unmapped disease ID"
                    Log.e(TAG, msg)
                    throw IllegalStateException(msg)
                }

                val treatmentDtos = localTreatments.map { t ->
                    val diseaseUuid = diseaseMap[t.diseaseIdFk]!!
                    TreatmentRecommendationDto(
                        sourceLocalId = t.id,
                        diseaseId = diseaseUuid,
                        treatmentAction = t.treatmentAction,
                        expertReferenceName = t.expertReferenceName,
                        articleSourceUrl = t.articleSourceUrl,
                    )
                }

                Log.d(TAG, "Upserting ${treatmentDtos.size} treatment recommendation record(s) to Supabase...")
                try {
                    supabaseClient.from("treatment_recommendations").upsert(treatmentDtos) {
                        onConflict = "source_local_id"
                    }
                    upsertedTreatmentCount = treatmentDtos.size
                } catch (e: Exception) {
                    Log.e(TAG, "Treatment recommendations upsert failed: ${e.message}", e)
                    throw e
                }
            }

            MasterSyncResult(
                diseaseMap = diseaseMap,
                diseaseCount = upsertedDiseaseCount,
                treatmentCount = upsertedTreatmentCount,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Master reference data synchronization failed: ${e.message}", e)
            throw e
        }
    }
}
