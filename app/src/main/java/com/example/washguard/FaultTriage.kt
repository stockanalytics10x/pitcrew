package com.example.washguard

/**
 * Pit crew fault triage: turns warning lights and vehicle readings into "what it is, how urgent,
 * what to do now, can I keep driving, when/where to fix it". Pure Kotlin, unit tested.
 *
 * Wording follows the IONIQ 5 owner's manual (현대자동차 아이오닉 5 취급설명서 · 경고등 및 표시등).
 * Where the manual excerpt has no entry, the card says so and points to the manual instead of inventing advice.
 */
enum class FaultLevel(val label: String, val rank: Int) {
    STOP_NOW("즉시 정차", 0),
    SERVICE("정비소 점검", 1),
    CHARGE("즉시 충전", 2),
    SELF_FIX("직접 조치", 3),
    INFO("바로 해결", 4)
}

/** Where on the car a crew member stands for a check. */
enum class CrewSpot(val label: String) {
    TIRE_FL("앞 왼쪽 타이어"), TIRE_FR("앞 오른쪽 타이어"), TIRE_RL("뒤 왼쪽 타이어"), TIRE_RR("뒤 오른쪽 타이어"),
    HOOD("보닛"), DASH("계기판"), CHARGE_PORT("충전구"), BODY("문·트렁크")
}

enum class SpotStatus { OK, WARN, UNKNOWN }

data class FaultCard(
    val id: String,
    val title: String,
    val spot: CrewSpot,
    val level: FaultLevel,
    /** What the light or reading means. */
    val what: String,
    /** What to do right now. */
    val now: String,
    /** Whether and how to keep driving. */
    val drive: String,
    /** When and where the Pit Wall suggests fixing it. */
    val plan: String,
    val source: String,
    /** Where to get it fixed: opens a Hyundai navi search. Null when nothing needs a place. */
    val fix: FixPlace? = null
)

/** A place search that solves the problem, e.g. a tyre shop for low pressure. */
data class FixPlace(val button: String, val kind: NativeSearchKind, val note: String,
    /** What the PIT SERVICE list should look for; null keeps the navi search only (e.g. chargers). */
    val need: ServiceNeed? = null)

/** Readings the crew can check. Null means "not received" (shown as ?), never "fine". */
data class HealthInput(
    /** Telltale name (TelltaleType.name) → blinking? Present only when ON or BLINK. Null when telltales were not received. */
    val telltales: Map<String, Boolean>?,
    /** Tyre pressure per wheel, and the car's own low-pressure threshold per wheel. */
    val tirePressure: Map<CrewSpot, Float?> = emptyMap(),
    val tireLowThreshold: Map<CrewSpot, Float?> = emptyMap(),
    /** OilLevelState name: CRITICALLY_LOW / LOW / NORMAL / HIGH / ERROR. */
    val brakeFluid: String? = null,
    val engineOil: String? = null,
    val washerLow: Boolean? = null,
    val powerDown: Boolean? = null,
    val driving: Boolean = false,
    /** Which car: decides 충전소/주유소 in the "when and where" lines. */
    val powertrain: Powertrain = Powertrain.UNKNOWN
)

data class CrewCheck(val cards: List<FaultCard>, val spots: Map<CrewSpot, SpotStatus>) {
    val worst: FaultLevel? get() = cards.minByOrNull { it.level.rank }?.level
    val allClear: Boolean get() = cards.isEmpty()
}

object FaultTriage {
    const val MANUAL = "아이오닉 5 취급설명서 · 경고등 및 표시등"
    private const val SIGNAL = "차량 신호"

    val TIRES = listOf(CrewSpot.TIRE_FL, CrewSpot.TIRE_FR, CrewSpot.TIRE_RL, CrewSpot.TIRE_RR)

    fun check(input: HealthInput): CrewCheck {
        val t = input.telltales
        val cards = mutableListOf<FaultCard>()
        fun on(name: String) = t?.containsKey(name) == true
        fun blink(name: String) = t?.get(name) == true

        // ── brakes
        val fluidLow = input.brakeFluid == "LOW" || input.brakeFluid == "CRITICALLY_LOW"
        if (fluidLow || (on("PARKING_BRAKE") && input.driving)) cards += FaultCard(
            "brake", "브레이크 경고", CrewSpot.HOOD, FaultLevel.STOP_NOW,
            what = if (fluidLow) "브레이크액이 부족해요. 브레이크 시스템에 이상이 있을 수 있어요." else "주차 브레이크가 걸려 있거나 브레이크액 부족·브레이크 시스템 이상이에요.",
            now = "급제동을 피하고 서서히 감속해 안전한 곳에 정차하세요. 제동이 더디면 페달을 평소보다 강하게 밟으세요.",
            drive = "정차 후 상태를 확인하세요. 주행을 이어 가지 마세요.",
            plan = "브레이크액 보충 후 정비소 점검. 긴급출동을 부르는 것도 방법이에요.",
            source = MANUAL, fix = FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "현대차 정비 협력점(블루핸즈)이나 서비스센터에서 점검받을 수 있어요.", ServiceNeed.REPAIR))
        else if (on("PARKING_BRAKE")) cards += FaultCard(
            "parking", "주차 브레이크 체결", CrewSpot.DASH, FaultLevel.INFO,
            what = "주차 브레이크가 걸려 있어요.", now = "출발 전에 해제하세요.", drive = "해제하면 바로 출발할 수 있어요.",
            plan = "출발할 때", source = MANUAL)

        if (on("LOW_VOLT_BATTERY")) cards += FaultCard(
            "12v", "12V 보조 배터리 충전 경고", CrewSpot.HOOD, FaultLevel.SERVICE,
            what = "충전 장치에 이상이 있어요.",
            now = "불필요한 전기 장치를 끄고, 꺼지지 않으면 정비소에서 점검받으세요.",
            drive = "계속 달리면 배터리가 방전될 수 있어요. 가까운 정비소까지만 이동하세요.",
            plan = "오늘 안에 가까운 정비소로", source = MANUAL, fix = FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "현대차 정비 협력점(블루핸즈)이나 서비스센터에서 점검받을 수 있어요.", ServiceNeed.REPAIR))

        if (on("RBS")) cards += FaultCard(
            "rbs", "회생 제동 경고", CrewSpot.DASH, FaultLevel.SERVICE,
            what = "브레이크 시스템 이상으로 회생 제동이 작동하지 않아요.",
            now = "페달이 깊게 느껴지거나 제동 거리가 길어질 수 있어요. 차간 거리를 넉넉히 두세요.",
            drive = "안전에 유의하며 정비소까지 이동할 수 있어요.",
            plan = "목적지 도착 후 가까운 정비소", source = MANUAL, fix = FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "현대차 정비 협력점(블루핸즈)이나 서비스센터에서 점검받을 수 있어요.", ServiceNeed.REPAIR))

        if (on("AIRBAG")) cards += FaultCard(
            "airbag", "에어백 경고", CrewSpot.DASH, FaultLevel.SERVICE,
            what = "에어백 또는 안전벨트 프리텐셔너에 이상이 있어요.",
            now = "시동 후 몇 초 뒤에도 꺼지지 않으면 정비소에서 점검받으세요.",
            drive = "주행은 가능하지만 사고 때 보호가 안 될 수 있어요.",
            plan = "가능한 빨리 정비소", source = MANUAL, fix = FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "현대차 정비 협력점(블루핸즈)이나 서비스센터에서 점검받을 수 있어요.", ServiceNeed.REPAIR))

        if (on("MDPS")) cards += FaultCard(
            "mdps", "전동 파워 스티어링 경고", CrewSpot.DASH, FaultLevel.SERVICE,
            what = "전동 파워 스티어링 기능에 이상이 있어요. 핸들이 무거워질 수 있어요.",
            now = "속도를 줄이고 안전에 유의하세요.",
            drive = "안전에 유의하며 정비소까지 이동하세요.",
            plan = "가능한 빨리 정비소", source = MANUAL, fix = FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "현대차 정비 협력점(블루핸즈)이나 서비스센터에서 점검받을 수 있어요.", ServiceNeed.REPAIR))

        if (on("ABS")) cards += FaultCard(
            "abs", "ABS 경고", CrewSpot.DASH, FaultLevel.SELF_FIX,
            what = "ABS(바퀴 잠김 방지 장치)에 이상이 있어요.",
            now = "안전한 곳에 정차해 시동을 껐다 켜 보세요.",
            drive = "다시 켠 뒤에도 계속 켜져 있으면 정비소에서 점검받으세요.",
            plan = "다음 피트(정차)에서 재시동 확인", source = MANUAL, fix = FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "현대차 정비 협력점(블루핸즈)이나 서비스센터에서 점검받을 수 있어요.", ServiceNeed.REPAIR))

        if (on("EV_SYSTEM")) cards += FaultCard(
            "service", "서비스 경고(EV 시스템)", CrewSpot.CHARGE_PORT, FaultLevel.SERVICE,
            what = "전기차 제어 시스템의 센서나 구동 장치, 에어컨 압축기에 이상이 있어요.",
            now = "주행 중 켜지거나 시동 후에도 꺼지지 않으면 정비소에서 점검받으세요.",
            drive = if (on("POWER_DOWN") || input.powerDown == true) "출력이 제한될 수 있어요. 바로 충전하고 정비소로 가세요." else "안전에 유의하며 정비소까지 이동하세요.",
            plan = "가능한 빨리 정비소", source = MANUAL, fix = FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "현대차 정비 협력점(블루핸즈)이나 서비스센터에서 점검받을 수 있어요.", ServiceNeed.REPAIR))

        if ((on("POWER_DOWN") || input.powerDown == true) && !on("EV_SYSTEM")) cards += FaultCard(
            "powerdown", "출력 제한(파워 다운)", CrewSpot.CHARGE_PORT, FaultLevel.CHARGE,
            what = "고전압 배터리 충전량 부족·전압 강하·온도 이상으로 출력을 줄인 상태예요. 단독으로 켜진 것은 고장이 아니에요.",
            now = "주행 속도가 제한되고 언덕이 어려울 수 있어요. 바로 충전하세요.",
            drive = "가까운 충전소까지 천천히 이동하세요.",
            plan = "다음 충전소", source = MANUAL, fix = FixPlace("가까운 충전소 찾기", NativeSearchKind.CHARGE, "주변 충전소를 내비에서 바로 찾아요."))

        if (on("LOW_EV_BATTERY")) cards += FaultCard(
            "lowev", "배터리 충전량 부족", CrewSpot.CHARGE_PORT, FaultLevel.CHARGE,
            what = "고전압 배터리 충전량이 부족해요. 주행 가능 거리가 약 30–40km 이내예요.",
            now = "바로 충전하세요.", drive = "가까운 충전소까지만 이동하세요.",
            plan = "지금 경로의 가장 가까운 충전소", source = MANUAL, fix = FixPlace("가까운 충전소 찾기", NativeSearchKind.CHARGE, "주변 충전소를 내비에서 바로 찾아요."))

        // ── tyres: per-wheel readings first, the TPMS light as a fallback
        val lowWheels = TIRES.filter { s ->
            val p = input.tirePressure[s]; val low = input.tireLowThreshold[s]
            p != null && low != null && p < low
        }
        if (lowWheels.isNotEmpty() || on("TPMS")) {
            val where = if (lowWheels.isEmpty()) "타이어 중 하나 이상" else lowWheels.joinToString(", ") { it.label }
            val faulty = blink("TPMS")
            cards += FaultCard(
                "tpms", if (faulty) "타이어 공기압 감지 장치 이상" else "타이어 공기압 낮음",
                lowWheels.firstOrNull() ?: CrewSpot.TIRE_FL, if (faulty) FaultLevel.SERVICE else FaultLevel.SELF_FIX,
                what = if (faulty) "경고등이 깜빡여요. 공기압 감지 장치(TPMS)에 이상이 있어요." else "$where 공기압이 현저히 낮아요.",
                now = if (faulty) "정비소에서 점검받으세요." else "공기압을 확인해 규정 공기압까지 보충하세요. 보충 후에도 켜지면 정비소를 방문하세요.",
                drive = "급한 핸들 조작과 고속 주행을 피하세요.",
                plan = if (faulty) "가까운 정비소" else "다음 피트(${input.powertrain.place}·휴게소 공기 주입기)에서 보충",
                source = MANUAL,
                fix = if (faulty) FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "감지 장치 점검은 정비소에서 받아야 해요.", ServiceNeed.REPAIR)
                else FixPlace("공기 넣을 곳 찾기", NativeSearchKind.TIRE, "타이어 전문점·정비소에서 공기를 넣을 수 있어요. 고속도로 휴게소에도 공기 주입기가 있는 곳이 많아요.", ServiceNeed.AIR))
        }

        // ── fluids and body
        if (input.washerLow == true) cards += FaultCard(
            "washer", "워셔액 부족", CrewSpot.HOOD, FaultLevel.SELF_FIX,
            what = "워셔액이 부족하다는 신호가 왔어요.", now = "주차한 뒤 보닛을 열고 워셔액을 보충하세요.",
            drive = "주행에는 지장이 없어요. 시야가 흐려지면 안전한 곳에서 보충하세요.",
            plan = "다음 피트(${input.powertrain.refuel}하는 동안)", source = SIGNAL, fix = FixPlace("워셔액 살 곳 찾기", NativeSearchKind.FUEL, "주유소에서 워셔액을 파는 경우가 많아요. 없으면 편의점·대형마트 자동차용품 코너를 찾아보세요.", ServiceNeed.WASHER))

        if (input.engineOil == "LOW" || input.engineOil == "CRITICALLY_LOW") cards += FaultCard(
            "oil", "오일 레벨 낮음", CrewSpot.HOOD, if (input.engineOil == "CRITICALLY_LOW") FaultLevel.SERVICE else FaultLevel.SELF_FIX,
            what = "오일 레벨이 낮다는 신호가 왔어요.", now = "취급설명서의 점검 방법을 확인하고, 직접 하기 어려우면 정비소에서 점검받으세요.",
            drive = if (input.engineOil == "CRITICALLY_LOW") "장거리 주행은 피하세요." else "주행은 가능해요.",
            plan = "가까운 정비소 또는 다음 정기 점검", source = SIGNAL, fix = FixPlace("엔진오일 넣을 곳 찾기", NativeSearchKind.OIL, "엔진오일 교환점이나 정비소에서 보충·교환할 수 있어요.", ServiceNeed.OIL))

        if (on("SEAT_BELT")) cards += FaultCard(
            "belt", "안전벨트 미착용", CrewSpot.BODY, FaultLevel.INFO,
            what = "안전벨트를 매지 않은 좌석이 있어요.", now = "안전벨트를 착용하세요.", drive = "착용하면 꺼져요.",
            plan = "지금", source = MANUAL)

        if (on("DOOR_OPEN") || on("TRUNK_OPEN")) cards += FaultCard(
            "door", if (on("TRUNK_OPEN")) "트렁크 열림" else "문 열림", CrewSpot.BODY, FaultLevel.INFO,
            what = "닫히지 않은 곳이 있어요.", now = "출발 전에 닫아 주세요.", drive = "닫으면 꺼져요.",
            plan = "지금", source = SIGNAL)

        // Lights the manual excerpt does not cover: say so, do not invent.
        listOf("ESC" to "차체 자세 제어(ESC) 경고", "MASTER_WARNING" to "통합 경고", "ENGINE" to "엔진 경고",
            "ENGINE_OIL_PRESSURE" to "엔진오일 압력 경고", "ENGINE_TEMPERATURE" to "엔진 온도 경고")
            .filter { on(it.first) }
            .forEach { (name, title) ->
                cards += FaultCard(
                    name.lowercase(), title, CrewSpot.DASH, FaultLevel.SERVICE,
                    what = "$title 등이 켜졌어요.",
                    now = if (name == "MASTER_WARNING") "계기판 정보 화면에서 내용을 확인하고, 센서 주변 이물질을 치워 보세요. 계속 켜지면 정비소를 방문하세요."
                    else "안전한 곳에 정차한 뒤 취급설명서에서 이 경고등 항목을 확인하세요.",
                    drive = "원인을 확인하기 전에는 무리한 주행을 피하세요.",
                    plan = "가까운 정비소에서 원인 확인",
                    source = if (name == "MASTER_WARNING") MANUAL else "${MANUAL}에서 확인 필요",
                    fix = FixPlace("가까운 블루핸즈 찾기", NativeSearchKind.SERVICE, "현대차 정비 협력점(블루핸즈)이나 서비스센터에서 점검받을 수 있어요.", ServiceNeed.REPAIR))
            }

        val sorted = cards.sortedBy { it.level.rank }
        return CrewCheck(sorted, spots(input, sorted))
    }

    private fun spots(input: HealthInput, cards: List<FaultCard>): Map<CrewSpot, SpotStatus> {
        val warn = cards.map { it.spot }.toMutableSet()
        if (cards.any { it.id == "tpms" }) {
            val low = TIRES.filter { s -> val p = input.tirePressure[s]; val l = input.tireLowThreshold[s]; p != null && l != null && p < l }
            if (low.isEmpty()) warn += TIRES else warn += low
        }
        val telltalesKnown = input.telltales != null
        return CrewSpot.entries.associateWith { s ->
            when {
                s in warn -> SpotStatus.WARN
                s in TIRES -> if (input.tirePressure[s] != null || telltalesKnown) SpotStatus.OK else SpotStatus.UNKNOWN
                s == CrewSpot.HOOD -> if (input.brakeFluid != null || input.washerLow != null || input.engineOil != null || telltalesKnown) SpotStatus.OK else SpotStatus.UNKNOWN
                else -> if (telltalesKnown) SpotStatus.OK else SpotStatus.UNKNOWN
            }
        }
    }

    /** One short radio line for a stop-now card while driving. */
    fun radioLine(card: FaultCard): String = "Box, box. ${card.title}이에요. ${card.now.substringBefore(". ")}."
}
