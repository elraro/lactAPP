package eu.elraro.babytracker.data.repository

import androidx.room.withTransaction
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.local.BabyEntity
import eu.elraro.babytracker.domain.Baby
import eu.elraro.babytracker.domain.Ids
import eu.elraro.babytracker.domain.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BabyRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val time: TimeSource,
    ) {
        fun observeAll(): Flow<List<Baby>> = db.babyDao().observeAll().map { rows -> rows.map { it.toDomain() } }

        /** Saves the baby locally and queues it for upload in the same transaction. */
        suspend fun create(
            name: String,
            dateOfBirth: LocalDate,
        ): Baby {
            val now = time.now()
            val entity =
                BabyEntity(
                    id = Ids.uuidV7(now),
                    name = name.trim(),
                    dateOfBirth = dateOfBirth.toString(),
                    sex = null,
                    notes = null,
                    version = 0,
                    deletedAt = null,
                )
            db.withTransaction {
                db.babyDao().upsert(entity)
                db.pendingMutationDao().insert(
                    pendingMutation("baby", entity.id, "upsert", entity.createFields(), baseVersion = 0, now = now),
                )
            }
            return entity.toDomain()
        }
    }
