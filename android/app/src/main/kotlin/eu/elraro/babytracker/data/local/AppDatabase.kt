package eu.elraro.babytracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FamilyEntity::class,
        BabyEntity::class,
        EventEntity::class,
        PendingMutationEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun familyDao(): FamilyDao

    abstract fun babyDao(): BabyDao

    abstract fun eventDao(): EventDao

    abstract fun pendingMutationDao(): PendingMutationDao

    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val NAME = "babytracker.db"
    }
}
