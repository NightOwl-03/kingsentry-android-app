package com.example.kingsentry.models

import androidx.compose.ui.graphics.Color
import com.example.kingsentry.data.TreatmentRecommendation
import com.example.kingsentry.ui.theme.RiskHigh
import com.example.kingsentry.ui.theme.RiskLow
import com.example.kingsentry.ui.theme.RiskMedium

data class AnalysisResult(
    val diseaseName: String,
    val confidence: Float,
    val riskLevel: String,
    val riskColor: Color,
    val recommendations: List<String> = emptyList(), // Backward compatibility or simple list
    val recommendationsDetail: List<TreatmentRecommendation> = emptyList(),
    val description: String = "",
    val symptoms: List<String> = emptyList(),
    val symptomsRaw: String = "", // Raw string from DB
    val causes: List<String> = emptyList(),
    val causesRaw: String = "", // Raw string from DB
    val prevention: List<String> = emptyList(),
    val preventionRaw: String = "", // Raw string from DB
    val referenceName: String = "",
    val referenceUrl: String = "",
    val referenceNotes: String = ""
)

object MockResults {
    val healthy = AnalysisResult(
        diseaseName = "Healthy Leaf",
        confidence = 0.98f,
        riskLevel = "Low Risk",
        riskColor = RiskLow,
        description = "The leaf shows no signs of infection or nutrient deficiency. It exhibits a uniform green color and a smooth texture, indicating optimal health and proper orchard management.",
        symptoms = emptyList(),
        causes = emptyList(),
        prevention = emptyList(),
        recommendations = listOf(
            "Maintain current watering schedule.",
            "Ensure adequate sunlight exposure.",
            "Regularly inspect for early signs of pests.",
            "Apply balanced fertilizer as scheduled."
        )
    )

    val leafSpot = AnalysisResult(
        diseaseName = "Durian Leaf Spot",
        confidence = 0.85f,
        riskLevel = "High Risk",
        riskColor = RiskHigh,
        description = "Caused by fungal pathogens, leading to necrotic spots on the foliage.",
        symptoms = listOf("Small brown spots with yellow halos", "Premature leaf fall", "Reduced photosynthesis"),
        causes = listOf("High humidity", "Poor air circulation", "Infected planting material"),
        prevention = listOf("Use resistant varieties", "Maintain proper spacing between trees", "Remove fallen leaves"),
        recommendations = listOf(
            "Prune and destroy infected leaves immediately.",
            "Improve air circulation around the tree.",
            "Apply copper-based fungicide during the rainy season.",
            "Avoid overhead irrigation to keep leaves dry."
        )
    )

    val leafBlight = AnalysisResult(
        diseaseName = "Durian Leaf Blight",
        confidence = 0.78f,
        riskLevel = "Medium Risk",
        riskColor = RiskMedium,
        description = "A rapid browning and death of leaf tissues often starting from the tips.",
        symptoms = listOf("Water-soaked lesions", "Quick browning of leaf tips", "Web-like fungal growth in humid conditions"),
        causes = listOf("Rhizoctonia solani fungus", "Excessive rain", "Poor soil drainage"),
        prevention = listOf("Improve drainage", "Avoid dense planting", "Clean pruning tools"),
        recommendations = listOf(
            "Remove and burn blighted leaves.",
            "Apply appropriate fungicides.",
            "Reduce nitrogen fertilizer during outbreaks."
        )
    )
}
