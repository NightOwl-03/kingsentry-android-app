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
