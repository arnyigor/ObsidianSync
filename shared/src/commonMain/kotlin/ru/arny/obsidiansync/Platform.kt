package ru.arny.obsidiansync

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform