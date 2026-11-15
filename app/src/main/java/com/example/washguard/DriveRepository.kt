package com.example.washguard

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class DriveAccess { UNKNOWN, RESTRICTED, PARKED }
data class DriveSegment(val name: String, val similarity: Int, val smoothness: Int, val efficiency: Int)
data class DriveSession(
    val demo: Boolean = true,
    val completed: Boolean = false,
    val similarity: Int? = null,
    val smoothness: Int? = null,
    val efficiency: Int? = null,
    val flow: List<Float> = emptyList(),
    val segments: List<DriveSegment> = emptyList(),
    val telemetry: SpeedFlow? = null
)
/** Scores are optional: unsupported SDK signals must remain null, never inferred as real data.
 * Production implementations own capability checks, freshness and session persistence.
 */
interface DriveRepository {
    val session: StateFlow<DriveSession>
    fun finish(access: DriveAccess)
    fun restart(access: DriveAccess)
}
class DemoDriveRepository : DriveRepository {
    private val sample = DriveSession(similarity = 87, smoothness = 92, efficiency = 84,
        flow = listOf(.48f,.52f,.50f,.57f,.55f,.61f,.58f,.60f,.57f,.62f,.60f,.64f),
        segments = listOf(DriveSegment("01 · 도심",83,89,80), DriveSegment("02 · 간선",90,94,88), DriveSegment("03 · 도착",88,93,84)))
    private val mutable = MutableStateFlow(sample)
    override val session: StateFlow<DriveSession> = mutable
    override fun finish(access: DriveAccess) { if (access == DriveAccess.PARKED) mutable.value = mutable.value.copy(completed = true) }
    override fun restart(access: DriveAccess) { if (access == DriveAccess.PARKED) mutable.value = sample }
}

/** Read-only boundary. Route rendering and turn-by-turn guidance stay in Hyundai navigation. */
interface DriveNavigationBoundary { fun destinationLabel(): String? }
class NaviHelperDriveBoundary(private val client: NaviClient?) : DriveNavigationBoundary {
    override fun destinationLabel(): String? = client?.state?.destination?.name
}
