package ru.arny.obsidiansync.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val ObsideltaJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun SyncManifest.toJson(): String = ObsideltaJson.encodeToString(this)
fun syncManifestFromJson(json: String): SyncManifest = ObsideltaJson.decodeFromString(json)
