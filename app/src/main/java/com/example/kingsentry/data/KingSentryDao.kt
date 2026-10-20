package com.example.kingsentry.data

import androidx.room.*
import com.example.kingsentry.models.AnalysisResult
import com.example.kingsentry.ui.theme.RiskHigh
import com.example.kingsentry.ui.theme.RiskLow
import com.example.kingsentry.ui.theme.RiskMedium
import kotlinx.coroutines.flow.Flow

@Dao
interface KingSentryDao {

    // --- Insert Functions ---
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrchard(orchard: Orchard): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRiskLevel(riskLevel: RiskLevel): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDisease(disease: DiseaseDictionary): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTreatment(treatment: TreatmentRecommendation): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: ScanResultData): Long

    @Query("SELECT * FROM Users WHERE username = :username LIMIT 1")
    suspend fun getUserByUsername(username: String): User?

    @Query("SELECT COUNT(*) FROM Users")
    suspend fun getUserCount(): Int

    @Query("SELECT id FROM DiseaseDictionary WHERE disease_name LIKE '%' || :name || '%' LIMIT 1")
    suspend fun getDiseaseIdByName(name: String): Int?

    // --- Specialized Query ---

    /**
     * Requirement: Fetch a complete, readable Scan History list using INNER JOIN.
     * Joins ScanResultData, User, Orchard, DiseaseDictionary, and RiskLevel.
     */
    @Query("""
        SELECT 
            s.id AS scanId,
            u.id AS userId,
            u.username AS userName,
            o.orchard_name AS orchardName,
            d.disease_name AS diseaseName,
            r.risk_name AS riskLevelName,
            s.confidence_score AS confidenceScore,
            s.image_uri_path AS imagePath,
            s.scan_timestamp AS timestamp
        FROM ScanResultData s
        INNER JOIN Users u ON s.user_id_fk = u.id
        INNER JOIN Orchards o ON s.orchard_id_fk = o.id
        INNER JOIN DiseaseDictionary d ON s.disease_id_fk = d.id
        INNER JOIN RiskLevels r ON d.risk_id_fk = r.id
        WHERE u.id = :userId
        ORDER BY s.scan_timestamp DESC
    """)
    fun getFullScanHistory(userId: Int): Flow<List<ScanHistoryDetail>>

    // --- Basic Queries ---

    @Query("SELECT * FROM Orchards")
    fun getAllOrchards(): Flow<List<Orchard>>

    @Query("SELECT * FROM Orchards")
    suspend fun getAllOrchardsList(): List<Orchard>

    @Query("SELECT * FROM DiseaseDictionary ORDER BY disease_name ASC")
    fun getAllDiseases(): Flow<List<DiseaseDictionary>>

    @Query("SELECT * FROM DiseaseDictionary ORDER BY disease_name ASC")
    suspend fun getAllDiseasesList(): List<DiseaseDictionary>

    @Query("SELECT * FROM TreatmentRecommendations")
    suspend fun getAllTreatmentsList(): List<TreatmentRecommendation>

    @Transaction
    @Query("SELECT * FROM DiseaseDictionary WHERE disease_name LIKE '%' || :name || '%' LIMIT 1")
    suspend fun getFullDiseaseDetailByName(name: String): FullDiseaseDetail?

    @Transaction
    @Query("SELECT * FROM DiseaseDictionary WHERE id = :id LIMIT 1")
    suspend fun getFullDiseaseDetailById(id: Int): FullDiseaseDetail?

    @Delete
    suspend fun deleteScan(scan: ScanResultData)

    @Query("DELETE FROM ScanResultData")
    suspend fun deleteAllScans()

    // --- Synchronization Queries ---

    @Query("SELECT COUNT(*) FROM ScanResultData WHERE is_synced = 0")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT * FROM ScanResultData WHERE is_synced = 0")
    suspend fun getUnsyncedScans(): List<ScanResultData>

    @Query("UPDATE ScanResultData SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markScansAsSynced(ids: List<Int>)

    @Query("UPDATE ScanResultData SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Int>)

    @Query("UPDATE ScanResultData SET is_synced = 0 WHERE is_synced = 1")
    suspend fun resetAllScansSyncStatus()

    @Query("UPDATE ScanResultData SET is_synced = :isSynced WHERE id = :id")
    suspend fun updateScanSyncedStatus(id: Int, isSynced: Boolean)
}

/**
 * Data class to hold the result of the joined scan history query.
 */
data class ScanHistoryDetail(
    val scanId: Int,
    val userId: Int,
    val userName: String,
    val orchardName: String,
    val diseaseName: String,
    val riskLevelName: String,
    val confidenceScore: Float,
    val imagePath: String,
    val timestamp: Long
)

/**
 * Combined data class for full disease information.
 */
data class FullDiseaseDetail(
    @Embedded val disease: DiseaseDictionary,
    @Relation(
        parentColumn = "risk_id_fk",
        entityColumn = "id"
    )
    val riskLevel: RiskLevel,
    @Relation(
        parentColumn = "id",
        entityColumn = "disease_id_fk"
    )
    val recommendations: List<TreatmentRecommendation>
)

/**
 * Extension function to convert database model to UI model.
 */
fun FullDiseaseDetail.toAnalysisResult(confidence: Float): AnalysisResult {
    val riskColor = when {
        riskLevel.riskName.contains("High", ignoreCase = true) -> RiskHigh
        riskLevel.riskName.contains("Medium", ignoreCase = true) -> RiskMedium
        else -> RiskLow
    }

    return AnalysisResult(
        diseaseName = disease.diseaseName,
        confidence = confidence,
        riskLevel = riskLevel.riskName,
        riskColor = riskColor,
        recommendations = recommendations.map { it.treatmentAction },
        recommendationsDetail = recommendations,
        description = disease.description,
        symptoms = disease.symptoms.split(";", ".").map { it.trim() }.filter { it.isNotEmpty() },
        symptomsRaw = disease.symptoms,
        causes = disease.possibleCauses.split(";", ".").map { it.trim() }.filter { it.isNotEmpty() },
        causesRaw = disease.possibleCauses,
        prevention = disease.preventionTips.split(";", ".").map { it.trim() }.filter { it.isNotEmpty() },
        preventionRaw = disease.preventionTips,
        referenceName = disease.referenceName,
        referenceUrl = disease.referenceUrl
    )
}
