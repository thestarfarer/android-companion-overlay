package com.starfarer.companionoverlay

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

/**
 * Shows Claude's response text on the car screen.
 * MessageTemplate is scrollable and handles long text well.
 */
class CompanionResponseScreen(
    carContext: CarContext,
    private val mainScreen: CompanionMainScreen,
    private val responseText: String
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder(responseText)
            .setTitle("Senni")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Talk Again")
                    .setOnClickListener {
                        screenManager.pop()
                    }
                    .build()
            )
            .build()
    }
}
