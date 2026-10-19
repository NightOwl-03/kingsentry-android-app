package com.example.kingsentry.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "Users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val username: String,
    @ColumnInfo(name = "password_hash")
    val passwordHash: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "Orchards")
data class Orchard(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "orchard_name")
    val orchardName: String,
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Entity(tableName = "RiskLevels")
data class RiskLevel(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "risk_name")
    val riskName: String // e.g., Low, Medium, High
)

@Entity(
    tableName = "DiseaseDictionary",
    foreignKeys = [
        ForeignKey(
            entity = RiskLevel::class,
            parentColumns = ["id"],
            childColumns = ["risk_id_fk"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class DiseaseDictionary(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "disease_name")
    val diseaseName: String,

    val description: String,

    val symptoms: String,

    @ColumnInfo(name = "possible_causes")
    val possibleCauses: String = "",

    @ColumnInfo(name = "prevention_tips")
    val preventionTips: String = "",

    @ColumnInfo(name = "reference_name")
    val referenceName: String,

    @ColumnInfo(name = "reference_url")
    val referenceUrl: String,

    @ColumnInfo(name = "risk_id_fk")
    val riskIdFk: Int
)

@Entity(
    tableName = "TreatmentRecommendations",
    foreignKeys = [
        ForeignKey(
            entity = DiseaseDictionary::class,
            parentColumns = ["id"],
            childColumns = ["disease_id_fk"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TreatmentRecommendation(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "disease_id_fk")
    val diseaseIdFk: Int,
    @ColumnInfo(name = "treatment_action")
    val treatmentAction: String,
    @ColumnInfo(name = "expert_reference_name")
    val expertReferenceName: String,
    @ColumnInfo(name = "article_source_url")
    val articleSourceUrl: String
)

@Entity(
    tableName = "ScanResultData",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["user_id_fk"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Orchard::class,
            parentColumns = ["id"],
            childColumns = ["orchard_id_fk"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DiseaseDictionary::class,
            parentColumns = ["id"],
            childColumns = ["disease_id_fk"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ScanResultData(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "user_id_fk")
    val userIdFk: Int,
    @ColumnInfo(name = "orchard_id_fk")
    val orchardIdFk: Int,
    @ColumnInfo(name = "disease_id_fk")
    val diseaseIdFk: Int,
    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Float,
    @ColumnInfo(name = "image_uri_path")
    val imageUriPath: String,
    @ColumnInfo(name = "scan_timestamp")
    val scanTimestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false
)
