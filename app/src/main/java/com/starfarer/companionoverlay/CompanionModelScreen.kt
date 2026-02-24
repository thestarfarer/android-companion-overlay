package com.starfarer.companionoverlay

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.starfarer.companionoverlay.repository.SettingsRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Model selector for Android Auto: ListTemplate with radio-style rows.
 */
class CompanionModelScreen(
    carContext: CarContext,
    private val onModelChanged: () -> Unit
) : Screen(carContext), KoinComponent {

    private val settings: SettingsRepository by inject()

    override fun onGetTemplate(): Template {
        val currentModel = settings.model

        val listBuilder = ItemList.Builder()
        for (i in PromptSettings.MODEL_IDS.indices) {
            val modelId = PromptSettings.MODEL_IDS[i]
            val modelName = PromptSettings.MODEL_NAMES[i]
            val isSelected = modelId == currentModel

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(modelName)
                    .addText(if (isSelected) "● Active" else "")
                    .setOnClickListener {
                        settings.model = modelId
                        onModelChanged()
                        screenManager.pop()
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("Select Model")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}
