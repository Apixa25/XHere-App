package world.xhere.shared.http

import io.ktor.client.engine.HttpClientEngine

/** Ktor engine for the platform. iOS uses Darwin; Android uses OkHttp; JVM tests and tools use CIO. */
internal expect fun platformHttpEngine(): HttpClientEngine
