package com.example.kingsentry.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        User::class,
        Orchard::class,
        RiskLevel::class,
        DiseaseDictionary::class,
        TreatmentRecommendation::class,
        ScanResultData::class
    ],
    version = 7, // Incremented version for is_synced column addition
    exportSchema = false
)
abstract class KingSentryDatabase : RoomDatabase() {

    abstract fun kingSentryDao(): KingSentryDao

    companion object {
        @Volatile
        private var INSTANCE: KingSentryDatabase? = null

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(
                database: SupportSQLiteDatabase
            ) {
                val cursor = database.query("PRAGMA table_info(DiseaseDictionary)")

                var hasPossibleCauses = false
                var hasPreventionTips = false

                while (cursor.moveToNext()) {
                    val columnName = cursor.getString(
                        cursor.getColumnIndexOrThrow("name")
                    )

                    if (columnName == "possible_causes") {
                        hasPossibleCauses = true
                    }

                    if (columnName == "prevention_tips") {
                        hasPreventionTips = true
                    }
                }

                cursor.close()

                if (!hasPossibleCauses) {
                    database.execSQL(
                        """
                ALTER TABLE DiseaseDictionary
                ADD COLUMN possible_causes TEXT NOT NULL DEFAULT ''
                """.trimIndent()
                    )
                }

                if (!hasPreventionTips) {
                    database.execSQL(
                        """
                ALTER TABLE DiseaseDictionary
                ADD COLUMN prevention_tips TEXT NOT NULL DEFAULT ''
                """.trimIndent()
                    )
                }

                database.execSQL(
                    """
    UPDATE DiseaseDictionary
    SET possible_causes = 'High humidity; prolonged leaf wetness; poor air circulation; infected planting materials.',
        prevention_tips = 'Maintain proper spacing between trees; improve air circulation; remove and properly dispose of infected leaves; use healthy planting materials.'
    WHERE disease_name = 'Durian Leaf Spot'
    """.trimIndent()
                )

                database.execSQL(
                    """
    UPDATE DiseaseDictionary
    SET possible_causes = 'High moisture and prolonged leaf wetness; poor drainage; humid conditions; infection associated with Phytophthora and other reported foliar pathogens.',
        prevention_tips = 'Improve drainage; avoid prolonged leaf wetness; maintain orchard sanitation; remove and properly dispose of severely infected plant parts.'
    WHERE disease_name = 'Durian Leaf Blight'
    """.trimIndent()
                )
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val cursor = database.query("PRAGMA table_info(ScanResultData)")
                var hasIsSynced = false
                while (cursor.moveToNext()) {
                    val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (columnName == "is_synced") {
                        hasIsSynced = true
                    }
                }
                cursor.close()

                if (!hasIsSynced) {
                    database.execSQL("ALTER TABLE ScanResultData ADD COLUMN is_synced INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        fun getDatabase(context: Context): KingSentryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KingSentryDatabase::class.java,
                    "kingsentry_database"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    .addCallback(DatabaseCallback())
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        seedDatabase(database.kingSentryDao())
                    }
                }
            }

            private suspend fun seedDatabase(dao: KingSentryDao) {
                // 1. Seed Risk Levels (Fixed IDs for consistency)
                dao.insertRiskLevel(RiskLevel(id = 1, riskName = "Low"))
                dao.insertRiskLevel(RiskLevel(id = 2, riskName = "Medium"))
                dao.insertRiskLevel(RiskLevel(id = 3, riskName = "High"))

                // 2. Seed Disease Dictionary & Recommendations
                
                // Healthy Leaf
                dao.insertDisease(DiseaseDictionary(
                    id = 1,
                    diseaseName = "Healthy Leaf",
                    description = "The leaf shows no signs of infection or nutrient deficiency. It exhibits a uniform green color and a smooth texture, indicating optimal health and proper orchard management.",
                    symptoms = "Vibrant green color; smooth texture; no visible lesions.",
                    possibleCauses = "Proper irrigation; balanced fertilization; regular monitoring.",
                    preventionTips = "Continue regular maintenance; ensure adequate spacing.",
                    referenceName = "Bureau of Plant Industry (BPI)",
                    referenceUrl = "http://bpi.da.gov.ph",
                    riskIdFk = 1
                ))
                
                dao.insertTreatment(TreatmentRecommendation(
                    diseaseIdFk = 1,
                    treatmentAction = "Maintain current watering schedule and fertilizer application.",
                    expertReferenceName = "DA Production Guide",
                    articleSourceUrl = "http://bpi.da.gov.ph"
                ))

                // Durian Leaf Spot
                dao.insertDisease(DiseaseDictionary(
                    id = 2,
                    diseaseName = "Durian Leaf Spot",
                    description = "A common fungal disease in durian caused by pathogens like Phyllosticta or Colletotrichum. It can reduce the photosynthetic capacity of the tree if left untreated.",
                    symptoms = "Small brown spots with yellow halos; premature leaf fall; necrotic lesions.",
                    possibleCauses = "High humidity; prolonged leaf wetness; poor air circulation; infected planting materials.",
                    preventionTips = "Maintain proper spacing between trees; improve air circulation; remove and properly dispose of infected leaves; use healthy planting materials.",
                    referenceName = "DA-RFO XI (Davao Region)",
                    referenceUrl = "https://davao.da.gov.ph",
                    riskIdFk = 3
                ))
                
                dao.insertTreatment(TreatmentRecommendation(
                    diseaseIdFk = 2,
                    treatmentAction = "Apply copper-based fungicide according to manufacturer instructions.",
                    expertReferenceName = "DA-BPI Plant Pathology",
                    articleSourceUrl = "http://bpi.da.gov.ph"
                ))

                // Durian Leaf Blight
                dao.insertDisease(DiseaseDictionary(
                    id = 3,
                    diseaseName = "Durian Leaf Blight",
                    description = "A rapid and destructive fungal infection caused by Rhizoctonia solani, characterized by irregular water-soaked lesions that quickly turn dark brown.",
                    symptoms = "Water-soaked lesions on tips; web-like fungal growth; rapid browning and death of leaf tissues.",
                    possibleCauses = "High moisture and prolonged leaf wetness; poor drainage; humid conditions; infection associated with Rhizoctonia pathogens.",
                    preventionTips = "Improve drainage; avoid prolonged leaf wetness; maintain orchard sanitation; remove and properly dispose of severely infected plant parts.",
                    referenceName = "Bureau of Plant Industry (BPI)",
                    referenceUrl = "http://bpi.da.gov.ph",
                    riskIdFk = 2
                ))
                
                dao.insertTreatment(TreatmentRecommendation(
                    diseaseIdFk = 3,
                    treatmentAction = "Remove and burn blighted leaves. Apply appropriate fungicides if needed.",
                    expertReferenceName = "BPI Durian Production Guide",
                    articleSourceUrl = "http://bpi.da.gov.ph"
                ))
            }
        }
    }
}
