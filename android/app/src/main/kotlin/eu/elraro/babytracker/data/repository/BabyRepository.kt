package eu.elraro.babytracker.data.repository

import androidx.room.withTransaction
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.local.BabyEntity
import eu.elraro.babytracker.data.sync.SyncRequester
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
        private val sync: SyncRequester,
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
            sync.requestSync()
            return entity.toDomain()
        }

        /** Changes name, date of birth or notes; only what changed is queued. */
        suspend fun update(baby: Baby) {
            val changed =
                db.withTransaction {
                    val old = db.babyDao().get(baby.id)?.takeIf { it.deletedAt == null } ?: return@withTransaction false
                    val new =
                        old.copy(
                            name = baby.name.trim(),
                            dateOfBirth = baby.dateOfBirth.toString(),
                            notes = baby.notes?.trim()?.ifEmpty { null },
                        )
                    val fields = new.changedFields(old)
                    if (new.name.isEmpty() || fields.isEmpty()) return@withTransaction false
                    db.babyDao().upsert(new)
                    db.pendingMutationDao().insert(
                        pendingMutation("baby", new.id, "upsert", fields, old.version, time.now()),
                    )
                    true
                }
            if (changed) sync.requestSync()
        }
    }
