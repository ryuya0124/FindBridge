package dev.findbridge.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object MapStyles {
    fun create(context: Context, mode: MapMode, dark: Boolean): String {
        val style =
            JSONObject(context.assets.open("map-style.json").bufferedReader().use { it.readText() })
        val layers = style.getJSONArray("layers")
        if (dark)
            for (index in 0 until layers.length()) {
                val layer = layers.getJSONObject(index)
                val id = layer.optString("id").lowercase()
                val paint = layer.optJSONObject("paint") ?: continue
                when (layer.optString("type")) {
                    "background" -> paint.put("background-color", "#14171c")
                    "fill" ->
                        when {
                            "water" in id -> paint.put("fill-color", "#0b2537")
                            "building" in id ->
                                paint
                                    .put("fill-color", "#2a2d32")
                                    .put("fill-outline-color", "#363a41")
                            listOf("park", "landcover", "forest", "grass", "wood").any {
                                it in id
                            } -> paint.put("fill-color", "#1b2d23")
                            else -> paint.put("fill-color", "#1c2025")
                        }
                    "line" ->
                        paint.put(
                            "line-color",
                            when {
                                "casing" in id -> "#202329"
                                "motorway" in id || "trunk" in id -> "#706044"
                                "water" in id -> "#173c51"
                                else -> "#42464e"
                            },
                        )
                    "symbol" -> {
                        if (paint.has("text-color"))
                            paint.put("text-color", if ("place" in id) "#d7d9df" else "#b2b8c1")
                        if (paint.has("text-halo-color")) paint.put("text-halo-color", "#171b21")
                    }
                    "raster" -> paint.put("raster-opacity", 0.0)
                }
            }
        if (mode != MapMode.STANDARD) {
            style
                .getJSONObject("sources")
                .put(
                    "gsi-aerial",
                    JSONObject()
                        .put("type", "raster")
                        .put("tileSize", 256)
                        .put("maxzoom", 18)
                        .put(
                            "tiles",
                            JSONArray()
                                .put(
                                    "https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/{z}/{x}/{y}.jpg"
                                ),
                        )
                        .put("attribution", "出典：国土地理院 / 地理院タイル"),
                )
            val aerial =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "background")
                            .put("type", "background")
                            .put(
                                "paint",
                                JSONObject()
                                    .put("background-color", if (dark) "#101318" else "#d8e5e8"),
                            )
                    )
                    .put(
                        JSONObject()
                            .put("id", "aerial-imagery")
                            .put("type", "raster")
                            .put("source", "gsi-aerial")
                            .put("paint", JSONObject().put("raster-fade-duration", 220))
                    )
            if (mode == MapMode.HYBRID)
                for (index in 0 until layers.length()) {
                    val layer = layers.getJSONObject(index)
                    if (layer.optString("type") == "symbol") {
                        layer.optJSONObject("paint")?.let { paint ->
                            if (paint.has("text-color")) paint.put("text-color", "#ffffff")
                            if (paint.has("text-halo-color"))
                                paint.put("text-halo-color", "#263238")
                        }
                        aerial.put(layer)
                    }
                }
            style.put("layers", aerial)
        }
        return style.toString()
    }
}
