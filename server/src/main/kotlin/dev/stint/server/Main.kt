package dev.stint.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.netty.Netty

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(
        factory = Netty,
        configure = {
            connectors.add(EngineConnectorBuilder().apply {
                host = config.host
                port = config.port
            })
            connectionGroupSize = 1
            workerGroupSize = 2
            callGroupSize = 2
        },
    ) {
        stintModule(config)
    }.start(wait = true)
}
