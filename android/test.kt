fun main() {
    val r = runCatching {
        listOf(1).let {
            return@let "found"
        }
        "empty"
    }.getOrDefault("error")
    println(r)
}
