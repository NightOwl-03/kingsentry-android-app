package com.example.kingsentry.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data Transfer Object representing a record in Supabase `scan_results` table.
 */
@Serializable
data class ScanResultDto(
    @SerialName("source_local_id")
    val sourceLocalId: Int,

    @SerialName("owner_id")
    val ownerId: String,

    @SerialName("orchard_id")
    val orchardId: String? = null,

    @SerialName("disease_id")
    val diseaseId: String,

    @SerialName("confidence_score")
    val confidenceScore: Float,

    @SerialName("image_uri_path")
    val imageUriPath: String? = null,

    @SerialName("scan_timestamp")
    val scanTimestamp: String,
)

/**
 * Data Transfer Object representing a record in Supabase `orchards` table.
 */
@Serializable
data class OrchardDto(
    @SerialName("source_local_id")
    val sourceLocalId: Int,

    @SerialName("owner_id")
    val ownerId: String,

    @SerialName("orchard_name")
    val orchardName: String,

    @SerialName("latitude")
    val latitude: Double? = null,

    @SerialName("longitude")
    val longitude: Double? = null,
)

/**
 * DTO for decoding returning rows with UUID and local integer ID mappings.
 */
@Serializable
data class IdMap(
    @SerialName("id")
    val id: String,

    @SerialName("source_local_id")
    val sourceLocalId: Int,
)

/**
 * Data Transfer Object representing a record in Supabase `disease_dictionary` table.
 */
@Serializable
data class DiseaseDictionaryDto(
    @SerialName("source_local_id")
    val sourceLocalId: Int,

    @SerialName("disease_name")
    val diseaseName: String,

    @SerialName("description")
    val description: String,

    @SerialName("symptoms")
    val symptoms: String,

    @SerialName("possible_causes")
    val possibleCauses: String = "",

    @SerialName("prevention_tips")
    val preventionTips: String = "",

    @SerialName("reference_name")
    val referenceName: String,

    @SerialName("reference_url")
    val referenceUrl: String,
)

/**
 * Data Transfer Object representing a record in Supabase `treatment_recommendations` table.
 */
@Serializable
data class TreatmentRecommendationDto(
    @SerialName("source_local_id")
    val sourceLocalId: Int,

    @SerialName("disease_id")
    val diseaseId: String,

    @SerialName("treatment_action")
    val treatmentAction: String,

    @SerialName("expert_reference_name")
    val expertReferenceName: String,

    @SerialName("article_source_url")
    val articleSourceUrl: String,
)
