package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MediaOverride::class], version = 2, exportSchema = false)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun mediaOverrideDao(): MediaOverrideDao

    companion object {
        @Volatile
        private var INSTANCE: GalleryDatabase? = null

        /** Edits became real file copies, so the per-photo adjustment columns are gone. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE media_overrides_new (mediaId TEXT NOT NULL PRIMARY KEY, " +
                        "isFavorite INTEGER NOT NULL, isHidden INTEGER NOT NULL, customAlbum TEXT)"
                )
                db.execSQL(
                    "INSERT INTO media_overrides_new SELECT mediaId, isFavorite, isHidden, customAlbum FROM media_overrides"
                )
                db.execSQL("DROP TABLE media_overrides")
                db.execSQL("ALTER TABLE media_overrides_new RENAME TO media_overrides")
            }
        }

        fun getDatabase(context: Context): GalleryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GalleryDatabase::class.java,
                    "gallery_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
