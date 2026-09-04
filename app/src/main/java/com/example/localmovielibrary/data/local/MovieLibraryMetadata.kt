package com.example.localmovielibrary.data.local

data class MovieMetadataList(
    val items: List<String>
)

data class MovieActorMetadataList(
    val movieId: Long,
    val actors: List<String>
)

data class MovieMetadataText(
    val value: String?
)
