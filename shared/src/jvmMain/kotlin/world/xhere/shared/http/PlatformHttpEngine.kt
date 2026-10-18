package world.xhere.shared.http

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

internal actual fun platformHttpEngine(): HttpClientEngine = CIO.create()
