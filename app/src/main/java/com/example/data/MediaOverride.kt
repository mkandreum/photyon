package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_overrides")
data class MediaOverride(
    @PrimaryKey val mediaId: String,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val customAlbum: String? = null
)
