package dev.daniel.jigsaw

import org.json.JSONArray

/** Reads every element with [read], which gets the index and this array as receiver: `array.map { getString(it) }`. */
internal inline fun <T> JSONArray.map(read: JSONArray.(Int) -> T): List<T> = (0 until length()).map { read(it) }
