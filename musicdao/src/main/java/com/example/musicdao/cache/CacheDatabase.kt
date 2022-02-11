package com.example.musicdao.cache

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.musicdao.cache.entities.AlbumEntity
import com.example.musicdao.cache.parser.Converters

@Database(
    entities = [AlbumEntity::class],
    version = 4
)
@TypeConverters(Converters::class)
abstract class CacheDatabase : RoomDatabase() {
    abstract val dao: CacheDao
}
