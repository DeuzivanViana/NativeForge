package io.nativeforge.core.project

import kotlinx.serialization.json.Json

/** Shared JSON configuration: tolerant to unknown keys so configs stay forward compatible. */
val ProjectJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
