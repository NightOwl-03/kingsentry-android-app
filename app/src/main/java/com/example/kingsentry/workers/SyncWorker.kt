package com.example.kingsentry.workers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.kingsentry.data.KingSentryDao
import com.example.kingsentry.data.KingSentryDatabase
import com.example.kingsentry.data.ScanResultData
import com.example.kingsentry.data.remote.IdMap
import com.example.kingsentry.data.remote.OrchardDto
import com.example.kingsentry.data.remote.ScanResultDto
import com.example.kingsentry.data.remote.SupabaseClientModule
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [CoroutineWorker] that uploads scan images to Supabase Storage
 * and synchronizes local Room scan records with Supabase PostgreSQL.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started execution.")

        val supabaseClient = SupabaseClientModule.client

        // 1. Get Owner ID from Supabase Auth
        val ownerId = supabaseClient.auth.currentUserOrNull()?.id
        if (ownerId == null) {
            Log.w(TAG, "No authenticated user found in Supabase Auth. Postponing sync.")
            return Result.retry()
        }
        Log.d(TAG, "Supabase user = $ownerId")

        val dao = KingSentryDatabase.getDatabase(applicationContext).kingSentryDao()

        // One-time Migration: Reset is_synced = 0 for existing scans so their images get uploaded to Supabase Storage
        runOneTimeSyncMigration(dao)

        return try {
            // 2. Query unsynced scans from Room DAO
            val unsyncedScans = dao.getUnsyncedScans()
            if (unsyncedScans.isEmpty()) {
                Log.d(TAG, "No unsynced scan records found in Room.")
                return Result.success()
            }
            Log.d(TAG, "Found ${unsyncedScans.size} unsynced scan(s).")

            // 3. Process Orchards referenced by unsynced scans
            val localOrchards = dao.getAllOrchardsList()
            val referencedOrchardIds = unsyncedScans.map { it.orchardIdFk }.toSet()
            val orchardsToSync = localOrchards.filter { referencedOrchardIds.contains(it.id) }

            val orchardDtos = orchardsToSync.map { orchard ->
                OrchardDto(
                    sourceLocalId = orchard.id,
                    ownerId = ownerId,
                    orchardName = orchard.orchardName,
                    latitude = orchard.latitude,
                    longitude = orchard.longitude,
                )
            }

            if (orchardDtos.isNotEmpty()) {
                Log.d(TAG, "Upserting ${orchardDtos.size} orchard(s) to Supabase...")
                supabaseClient.from("orchards").upsert(orchardDtos) {
                    onConflict = "owner_id,source_local_id"
                }
            }

            // Fetch orchard UUID mappings for owner
            val fetchedOrchards = supabaseClient.from("orchards")
                .select(Columns.list("id", "source_local_id"))
                .decodeList<IdMap>()
            val orchardMap: Map<Int, String> = fetchedOrchards.associate { it.sourceLocalId to it.id }
            Log.d(TAG, "orchardMap size = ${orchardMap.size}")

            // 4. Fetch Disease Dictionary mappings
            val diseaseRows = supabaseClient.from("disease_dictionary")
                .select(Columns.list("id", "source_local_id"))
                .decodeList<IdMap>()
            val diseaseMap: Map<Int, String> = diseaseRows.associate { it.sourceLocalId to it.id }
            Log.d(TAG, "diseaseMap size = ${diseaseMap.size}")

            // 5. Build draft ScanResultDto payloads
            val validUploads = mutableListOf<Pair<ScanResultData, ScanResultDto>>()

            for (scan in unsyncedScans) {
                val diseaseUuid = diseaseMap[scan.diseaseIdFk]
                if (diseaseUuid == null) {
                    Log.w(TAG, "Skipped scan local_id=${scan.id}: disease_id_fk=${scan.diseaseIdFk} not found in Supabase disease_dictionary.")
                    continue
                }

                val orchardUuid = orchardMap[scan.orchardIdFk]
                val isoTimestamp = formatIsoTimestamp(scan.scanTimestamp)

                val dto = ScanResultDto(
                    sourceLocalId = scan.id,
                    ownerId = ownerId,
                    orchardId = orchardUuid,
                    diseaseId = diseaseUuid,
                    confidenceScore = scan.confidenceScore,
                    imageUriPath = null, // Will be updated during image upload step
                    scanTimestamp = isoTimestamp,
                )

                validUploads.add(scan to dto)
            }

            if (validUploads.isEmpty()) {
                Log.w(TAG, "No valid scan results ready for upload after disease UUID validation.")
                return Result.success()
            }

            Log.d(TAG, "Prepared ${validUploads.size} valid scan(s) for image upload & row upsert.")

            // 6. Process uploads in chunks of 20
            val chunks = validUploads.chunked(20)
            val storageBucket = supabaseClient.storage.from("scan-images")
            var totalSynced = 0

            for (chunk in chunks) {
                val chunkDtos = mutableListOf<ScanResultDto>()
                val chunkLocalIds = mutableListOf<Int>()

                // Step A: Upload images for the chunk
                for (pair in chunk) {
                    val scan = pair.first
                    val draftDto = pair.second

                    var finalStoragePath: String? = null

                    if (!scan.imageUriPath.isBlank()) {
                        val imageFile = File(scan.imageUriPath)
                        if (imageFile.exists() && imageFile.isFile) {
                            val storagePath = "$ownerId/${scan.id}.jpg"
                            Log.d(TAG, "Starting image upload for scan local_id=${scan.id} -> $storagePath...")

                            try {
                                val imageBytes = processImageBytes(scan.imageUriPath)
                                if (imageBytes != null) {
                                    storageBucket.upload(storagePath, imageBytes) {
                                        upsert = true
                                    }
                                    finalStoragePath = storagePath
                                    Log.d(TAG, "Image upload successful for scan local_id=${scan.id} -> $storagePath")
                                } else {
                                    Log.w(TAG, "Image file unreadable for scan local_id=${scan.id} at ${scan.imageUriPath}")
                                }
                            } catch (e: HttpRequestTimeoutException) {
                                Log.w(TAG, "Network timeout during image upload for scan local_id=${scan.id}, retrying chunk...", e)
                                throw e
                            } catch (e: IOException) {
                                Log.w(TAG, "Network IO error during image upload for scan local_id=${scan.id}, retrying chunk...", e)
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Non-network image upload failure for scan local_id=${scan.id}: ${e.message}. Proceeding with image_uri_path = null.", e)
                                finalStoragePath = null
                            }
                        } else {
                            Log.w(TAG, "Local image file missing at ${scan.imageUriPath} for scan local_id=${scan.id}. Proceeding with image_uri_path = null.")
                            finalStoragePath = null
                        }
                    }

                    chunkDtos.add(draftDto.copy(imageUriPath = finalStoragePath))
                    chunkLocalIds.add(scan.id)
                }

                // Step B: Upsert scan rows
                Log.d(TAG, "Upserting chunk of ${chunkDtos.size} scan result(s) to Supabase...")
                supabaseClient.from("scan_results").upsert(chunkDtos) {
                    onConflict = "owner_id,source_local_id"
                }

                // Step C: Mark synced in Room ONLY after scan rows upsert succeeds
                dao.markSynced(chunkLocalIds)
                totalSynced += chunkLocalIds.size
                setProgress(androidx.work.workDataOf("done" to totalSynced, "total" to validUploads.size))
                com.example.kingsentry.data.sync.SyncStatusManager.reportWorkerProgress(totalSynced, validUploads.size)
                Log.d(TAG, "Marked ${chunkLocalIds.size} scan(s) as synced in Room.")
            }

            com.example.kingsentry.data.sync.SyncStatusManager.reportWorkerSuccess()
            Log.d(TAG, "SyncWorker finished successfully. Total synced: $totalSynced.")
            Result.success()
        } catch (e: HttpRequestTimeoutException) {
            com.example.kingsentry.data.sync.SyncStatusManager.reportWorkerRetry()
            Log.w(TAG, "Network timeout during sync operation, scheduling retry: ${e.message}", e)
            Result.retry()
        } catch (e: IOException) {
            com.example.kingsentry.data.sync.SyncStatusManager.reportWorkerRetry()
            Log.w(TAG, "IO / Network exception during sync operation, scheduling retry: ${e.message}", e)
            Result.retry()
        } catch (e: Exception) {
            com.example.kingsentry.data.sync.SyncStatusManager.reportWorkerFailure(e.message ?: "Sync failed")
            Log.e(TAG, "Error during scan synchronization: ${e.message}", e)
            Result.failure()
        }
    }

    private fun runOneTimeSyncMigration(dao: KingSentryDao) {
        try {
            val prefs = applicationContext.getSharedPreferences("kingsentry_sync_prefs", Context.MODE_PRIVATE)
            val isMigrationDone = prefs.getBoolean("migration_re_sync_images_v1", false)
            if (!isMigrationDone) {
                Log.d(TAG, "Running one-time migration: resetting is_synced = 0 for existing scans so images are uploaded to Supabase Storage...")
                kotlinx.coroutines.runBlocking {
                    dao.resetAllScansSyncStatus()
                }
                prefs.edit {
                    putBoolean("migration_re_sync_images_v1", true)
                }
                Log.d(TAG, "One-time sync migration complete.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "One-time sync migration check encountered an error: ${e.message}", e)
        }
    }

    private fun processImageBytes(imagePath: String): ByteArray? {
        val file = File(imagePath)
        if (!file.exists() || !file.isFile) return null

        val fileSizeBytes = file.length()
        if (fileSizeBytes <= 1_500_000L) {
            return file.readBytes()
        }

        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imagePath, boundsOptions)

            var inSampleSize = 1
            val maxDimension = 1280
            while (boundsOptions.outWidth / inSampleSize > maxDimension || boundsOptions.outHeight / inSampleSize > maxDimension) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            val bitmap = BitmapFactory.decodeFile(imagePath, decodeOptions) ?: return file.readBytes()

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val bytes = outputStream.toByteArray()
            bitmap.recycle()
            Log.d(TAG, "Compressed image $imagePath from ${fileSizeBytes / 1024} KB to ${bytes.size / 1024} KB")
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "Downscaling image at $imagePath failed, sending raw file bytes: ${e.message}")
            file.readBytes()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_WORK_NAME = "sync_scans"
        const val UNIQUE_PERIODIC_WORK_NAME = "periodic_sync_scans"

        /**
         * Enqueues a one-time sync task with exponential backoff and network requirement.
         */
        fun enqueueOneTime(context: Context, replaceExisting: Boolean = false) {
            Log.d("SyncTrigger", "Enqueuing OneTime SyncWorker (replaceExisting=$replaceExisting)...")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                workRequest
            )
        }

        /**
         * Enqueues a periodic sync task running every 15 minutes as a safety net.
         */
        fun enqueuePeriodic(context: Context) {
            Log.d("SyncTrigger", "Enqueuing Periodic SyncWorker (15m)...")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }

        private fun formatIsoTimestamp(epochMillis: Long): String {
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            return formatter.format(java.util.Date(epochMillis))
        }
    }
}
