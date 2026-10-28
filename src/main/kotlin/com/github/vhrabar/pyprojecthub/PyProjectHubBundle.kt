package com.github.vhrabar.pyprojecthub

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.PyProjectHubBundle"

object PyProjectHubBundle : DynamicBundle(PyProjectHubBundle::class.java, BUNDLE) {
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)

    @Nls
    operator fun get(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
    ): String = getMessage(key)
}
