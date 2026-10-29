package dev.stint.server

data class ServerConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val authToken: String,
    val host: String,
    val port: Int,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String): String = environment[name]
                ?.takeIf { it.isNotBlank() }
                ?: error("$name must be set")

            val token = required("STINT_AUTH_TOKEN")
            require(token.length >= 24) { "STINT_AUTH_TOKEN must be at least 24 characters" }
            return ServerConfig(
                dbUrl = required("STINT_DB_URL"),
                dbUser = required("STINT_DB_USER"),
                dbPassword = required("STINT_DB_PASSWORD"),
                authToken = token,
                host = environment["STINT_HOST"]?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = environment["STINT_PORT"]?.toIntOrNull() ?: 8787,
            )
        }
    }
}

fun interface Clock {
    fun nowEpochMs(): Long
}

object SystemClock : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
