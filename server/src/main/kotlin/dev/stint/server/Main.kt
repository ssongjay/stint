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
            // 개인용 소형 서버의 자원 상한을 예측 가능하게 유지하는 보수적인 Netty 풀 크기다.
            connectionGroupSize = 1
            workerGroupSize = 2
            callGroupSize = 2
        },
    ) {
        stintModule(config)
    }.start(wait = true)
}
