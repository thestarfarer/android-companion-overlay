package com.starfarer.companionoverlay

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

/**
 * Model selector for Android Auto — ListTemplate with radio-style rows.
 */
class CompanionModelScreen(
    carContext: CarContext,
    private val mainScreen: CompanionMainScreen
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val currentModel = PromptSettings.getModel(carContext)

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
                        PromptSettings.setModel(carContext, modelId)
                        mainScreen.onModelChanged()
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
