/**
 * NavArgs.kt
 * Responsibility : JSON serialisation helpers for passing ML result payloads through
 *                  Navigation Compose arguments. Navigation only supports primitive types
 *                  natively — complex result objects are JSON-encoded into the URL.
 *
 * Strategy used:
 *   - FaceResult, ObjectInferResult, SegmentResult are stored in NavResultHolder (in-memory singleton)
 *     rather than URL-encoding large payloads. The route argument is just a String key.
 *   - NormBbox (small, < 50 chars) is URL-safe-encoded directly in the route.
 *
 * Rationale: SegmentResult can be hundreds of KB (PNG bytes). Navigation route strings
 * have a ~64KB URL limit; storing in memory avoids that constraint.
 */
package com.artgrid.mobile.ui.navigation

import android.net.Uri
import com.artgrid.mobile.domain.ml.model.FaceResult
import com.artgrid.mobile.domain.ml.model.NormBbox
import com.artgrid.mobile.domain.ml.model.ObjectInferResult
import com.artgrid.mobile.domain.ml.model.SegmentResult
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ── In-memory result holder ───────────────────────────────────────────────────

/**
 * Holds the latest ML results keyed by a unique token. Cleared when the app process is killed.
 * This is intentionally simple — there is only ever one active "pending result" per type in the demo.
 */
object NavResultHolder {
    private val faceMap:    ConcurrentHashMap<String, FaceResult>        = ConcurrentHashMap()
    private val objectsMap: ConcurrentHashMap<String, ObjectInferResult> = ConcurrentHashMap()
    private val segmentMap: ConcurrentHashMap<String, SegmentResult>     = ConcurrentHashMap()

    fun putFace(key: String, result: FaceResult)               { faceMap[key]    = result }
    fun getFace(key: String): FaceResult?                      = faceMap[key]
    fun putObjects(key: String, result: ObjectInferResult)     { objectsMap[key] = result }
    fun getObjects(key: String): ObjectInferResult?            = objectsMap[key]
    fun putSegment(key: String, result: SegmentResult)         { segmentMap[key] = result }
    fun getSegment(key: String): SegmentResult?                = segmentMap[key]
}

// ── NormBbox serialisation (small, embedded in route) ────────────────────────

fun NormBbox.encodeToNav(): String = Uri.encode("$xNorm,$yNorm,$wNorm,$hNorm")

fun String.decodeNavBbox(): NormBbox? = runCatching {
    val decoded = Uri.decode(this)
    val parts   = decoded.split(",")
    NormBbox(
        xNorm = parts[0].toFloat(),
        yNorm = parts[1].toFloat(),
        wNorm = parts[2].toFloat(),
        hNorm = parts[3].toFloat(),
    )
}.getOrNull()
