package com.personal.hebpdf

import android.content.Context
import android.graphics.Typeface

object Fonts {
    private val cache = HashMap<String, Typeface>()

    fun list(ctx: Context): List<String> =
        (ctx.assets.list("fonts") ?: emptyArray()).filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }.sorted()

    fun displayName(file: String) = file.substringBeforeLast('.').replace('-', ' ')

    fun typeface(ctx: Context, file: String): Typeface =
        cache.getOrPut(file) { runCatching { Typeface.createFromAsset(ctx.assets, "fonts/$file") }.getOrDefault(Typeface.DEFAULT) }
}
