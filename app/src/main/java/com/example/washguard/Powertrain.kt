package com.example.washguard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What fills this car up. Everything the Pit Wall says about energy (battery or fuel, charge or refuel,
 * charger or fuel station) comes from here, so an EV and a combustion car each hear their own words.
 */
enum class Powertrain(
    val label: String,
    /** The tank: 배터리 / 연료. */
    val energy: String,
    /** Filling it: 충전 / 주유. */
    val refuel: String,
    /** Where: 충전소 / 주유소. */
    val place: String,
    val tile: String,
    val icon: String,
    /** Highest % worth filling to in one stop (EVs charge slowly above 80%). */
    val capPercent: Int
) {
    EV("전기차", "배터리", "충전", "충전소", "BATTERY", "⚡", 80),
    ICE("내연기관", "연료", "주유", "주유소", "FUEL", "⛽", 100),
    HYBRID("하이브리드", "연료", "주유", "주유소", "FUEL", "⛽", 100),
    /** No energy reading yet: speak as for an EV (the emulator is one) until the car says otherwise. */
    UNKNOWN("확인 중", "배터리", "충전", "충전소", "BATTERY", "⚡", 80);

    val usesFuel: Boolean get() = this == ICE || this == HYBRID
    /** "충전이" / "주유가" — the right particle after the word. */
    val refuelSubject: String get() = if (usesFuel) "주유가" else "충전이"
    val searchKind: NativeSearchKind get() = if (usesFuel) NativeSearchKind.FUEL else NativeSearchKind.CHARGE
    val searchRoute: String get() = if (usesFuel) "NAVI_FUEL" else "NAVI_CHARGE"

    companion object {
        /** From what the car actually sent: an EV battery level, a fuel level, or both (plug-in hybrid). */
        fun detect(evSeen: Boolean, fuelSeen: Boolean): Powertrain = when {
            evSeen && fuelSeen -> HYBRID
            evSeen -> EV
            fuelSeen -> ICE
            else -> UNKNOWN
        }
    }
}

/** The driver's choice in SETTINGS: follow the car, or fix the type (for cars that report both, and for demos). */
enum class PowertrainChoice(val label: String) { AUTO("자동 인식"), EV("전기차"), ICE("내연기관") }

class PowertrainSetting(context: Context) {
    private val prefs = context.getSharedPreferences("powertrain", Context.MODE_PRIVATE)
    var choice by mutableStateOf(runCatching { PowertrainChoice.valueOf(prefs.getString("choice", "AUTO")!!) }.getOrDefault(PowertrainChoice.AUTO))
        private set

    fun set(c: PowertrainChoice) { choice = c; prefs.edit().putString("choice", c.name).apply() }

    fun resolve(detected: Powertrain): Powertrain = when (choice) {
        PowertrainChoice.AUTO -> detected
        PowertrainChoice.EV -> Powertrain.EV
        PowertrainChoice.ICE -> Powertrain.ICE
    }
}
