package eu.elraro.babytracker.sync

import eu.elraro.babytracker.data.repository.SaveResult
import eu.elraro.babytracker.data.session.SessionState
import eu.elraro.babytracker.data.sync.SyncOutcome
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val server = FakeSyncServer()
    private val phoneA = TestDevice(server)
    private val phoneB = TestDevice(server)

    @After
    fun tearDown() {
        phoneA.close()
        phoneB.close()
        server.close()
    }

    private fun SaveResult.event(): Event = (this as SaveResult.Saved).event

    /** Phone A creates the baby and uploads it; returns the baby's ID. */
    private suspend fun babyOnServer(): String {
        val baby = phoneA.babies.create("Vera", LocalDate.of(2026, 7, 1))
        assertEquals(SyncOutcome.SUCCESS, phoneA.engine.sync())
        return baby.id
    }

    @Test
    fun `what was recorded offline is uploaded and confirmed`() =
        runBlocking {
            val baby = babyOnServer()
            val bottle = phoneA.events.logBottle(baby, 120).event()
            assertEquals(0, phoneA.event(bottle.id)!!.version)

            assertEquals(SyncOutcome.SUCCESS, phoneA.engine.sync())

            assertEquals(120, server.liveRows("event").single().int("amount_ml"))
            assertEquals(1, phoneA.event(bottle.id)!!.version)
            assertTrue(phoneA.queue().isEmpty())
            val state = phoneA.db.syncStateDao().get()!!
            assertNotNull(state.cursor)
            assertNotNull(state.lastSuccessAt)
            assertEquals(
                "Casa",
                phoneA.db
                    .familyDao()
                    .observe()
                    .first()
                    ?.name,
            )
        }

    @Test
    fun `an upload whose answer is lost is repeated identically and creates nothing twice`() =
        runBlocking {
            val baby = babyOnServer()
            phoneA.events.logBottle(baby, 120)
            // The server stores the bottle but the phone never hears back.
            server.failAfterProcessing("/sync/push", 503)

            assertEquals(SyncOutcome.RETRY, phoneA.engine.sync())
            assertEquals(1, phoneA.queue().size)
            assertTrue(phoneA.queue().single().sent)

            // More changes made before the retry must not alter what was already sent.
            val bottle =
                phoneA.db
                    .eventDao()
                    .observeLatest(baby, "bottle")
                    .first()!!
            phoneA.events.update(
                phoneA.events
                    .observeLatest(baby, EventType.BOTTLE)
                    .first()!!
                    .copy(amountMl = 150),
            )

            assertEquals(SyncOutcome.SUCCESS, phoneA.engine.sync())

            val pushes = server.pushes().takeLast(2)
            assertEquals(pushes[0].single(), pushes[1].first())
            assertEquals(150, server.liveRows("event").single().int("amount_ml"))
            assertEquals(1, server.liveRows("event").size)
            assertEquals(2, phoneA.event(bottle.id)!!.version)
            assertTrue(phoneA.queue().isEmpty())
        }

    @Test
    fun `offline edits are merged before upload and cancelled work is never sent`() =
        runBlocking {
            val baby = babyOnServer()
            val kept = phoneA.events.logBottle(baby, 120).event()
            phoneA.events.update(kept.copy(amountMl = 150))
            phoneA.events.update(kept.copy(amountMl = 150, notes = "sleepy"))
            val mistake = phoneA.events.logDiaper(baby, DiaperKind.WET).event()
            phoneA.events.delete(mistake.id)
            assertEquals(5, phoneA.queue().size)

            phoneA.engine.sync()

            val sent = server.pushes().last()
            assertEquals(1, sent.size)
            assertEquals(kept.id, sent.single().text("entity_id"))
            val row = server.liveRows("event").single()
            assertEquals(150, row.int("amount_ml"))
            assertEquals("sleepy", row.text("notes"))

            // Undo after the cancelled pair was dropped: the diaper is created after all.
            phoneA.events.restore(mistake.id)
            phoneA.engine.sync()
            assertEquals(2, server.liveRows("event").size)
            assertEquals(1, phoneA.event(mistake.id)!!.version)
        }

    @Test
    fun `two phones converge, keeping edits to different fields`() =
        runBlocking {
            val baby = babyOnServer()
            val bottle = phoneA.events.logBottle(baby, 120).event()
            phoneA.engine.sync()
            phoneB.engine.sync()
            assertEquals(
                "Vera",
                phoneB.babies
                    .observeAll()
                    .first()
                    .single()
                    .name,
            )
            val onB = phoneB.events.observeLatest(baby, EventType.BOTTLE).first()!!
            assertEquals(bottle.id, onB.id)

            // Both edit the same event while offline; each also records something new.
            phoneA.events.update(bottle.copy(amountMl = 150))
            phoneA.events.logDiaper(baby, DiaperKind.DIRTY)
            phoneB.events.update(onB.copy(notes = "half asleep"))
            phoneB.time.advance(Duration.ofMinutes(5))
            phoneB.events.startSleep(baby)

            phoneA.engine.sync()
            phoneB.engine.sync()
            phoneA.engine.sync()

            val a = phoneA.allEvents()
            assertEquals(3, a.size)
            assertEquals(a, phoneB.allEvents())
            val merged = phoneA.event(bottle.id)!!
            assertEquals(150, merged.amountMl)
            assertEquals("half asleep", merged.notes)
            assertTrue(phoneA.queue().isEmpty() && phoneB.queue().isEmpty())
        }

    @Test
    fun `an edit loses against a deletion made on the other phone`() =
        runBlocking {
            val baby = babyOnServer()
            val bottle = phoneA.events.logBottle(baby, 120).event()
            phoneA.engine.sync()
            phoneB.engine.sync()

            phoneA.events.delete(bottle.id)
            phoneA.engine.sync()
            phoneB.events.update(
                phoneB.events
                    .observeLatest(baby, EventType.BOTTLE)
                    .first()!!
                    .copy(amountMl = 200),
            )

            assertEquals(SyncOutcome.SUCCESS, phoneB.engine.sync())

            assertNull(phoneB.events.observeLatest(baby, EventType.BOTTLE).first())
            assertNotNull(phoneB.event(bottle.id)!!.deletedAt)
            // Resolved automatically: nothing is left queued or marked as failed.
            assertTrue(phoneB.queue().isEmpty())
            assertEquals(
                0,
                phoneB.db
                    .pendingMutationDao()
                    .observeFailedCount()
                    .first(),
            )
        }

    @Test
    fun `a rejected change does not block the rest and can be discarded`() =
        runBlocking {
            val baby = babyOnServer()
            val bottle = phoneA.events.logBottle(baby, 120).event()
            phoneA.engine.sync()

            server.rejectFields = { fields -> "validation_failed".takeIf { fields["amount_ml"]?.toString() == "999" } }
            phoneA.events.update(bottle.copy(amountMl = 999))
            val diaper = phoneA.events.logDiaper(baby, DiaperKind.WET).event()

            assertEquals(SyncOutcome.SUCCESS, phoneA.engine.sync())

            assertEquals(1, phoneA.event(diaper.id)!!.version)
            assertEquals(
                1,
                phoneA.db
                    .pendingMutationDao()
                    .observeFailedCount()
                    .first(),
            )
            assertEquals(
                "validation_failed",
                phoneA.db
                    .pendingMutationDao()
                    .failed()
                    .single()
                    .errorCode,
            )
            // The rejected mutation is not sent again by later cycles.
            val pushesBefore = server.pushes().size
            phoneA.engine.sync()
            assertEquals(pushesBefore, server.pushes().size)
            assertEquals(999, phoneA.event(bottle.id)!!.amountMl)

            phoneA.engine.discardFailed()
            phoneA.engine.sync()

            assertEquals(120, phoneA.event(bottle.id)!!.amountMl)
            assertEquals(1, phoneA.event(bottle.id)!!.version)
            assertEquals(
                0,
                phoneA.db
                    .pendingMutationDao()
                    .observeFailedCount()
                    .first(),
            )
        }

    @Test
    fun `a local change waiting for upload survives a download of the same row`() =
        runBlocking {
            val baby = babyOnServer()
            val bottle = phoneA.events.logBottle(baby, 120).event()
            phoneA.engine.sync()
            phoneB.engine.sync()

            // B changes the notes on the server; A has an amount change it cannot upload yet.
            phoneB.events.update(
                phoneB.events
                    .observeLatest(baby, EventType.BOTTLE)
                    .first()!!
                    .copy(notes = "from B"),
            )
            phoneB.engine.sync()
            phoneA.events.update(bottle.copy(amountMl = 150))
            server.failAfterProcessing("/sync/push", 503)
            phoneA.engine.sync() // upload reaches the server, the answer is lost, the download is skipped

            // The next cycle downloads the merged row while A's mutation is still queued.
            server.failAfterProcessing("/sync/push", 503)
            phoneA.engine.sync()
            assertEquals(150, phoneA.event(bottle.id)!!.amountMl)

            assertEquals(SyncOutcome.SUCCESS, phoneA.engine.sync())
            val row = phoneA.event(bottle.id)!!
            assertEquals(150, row.amountMl)
            assertEquals("from B", row.notes)
            assertTrue(phoneA.queue().isEmpty())
        }

    @Test
    fun `an expired access token is refreshed in the middle of a cycle`() =
        runBlocking {
            val baby = babyOnServer()
            phoneA.events.logBottle(baby, 120)
            server.failAfterProcessing("/sync/push", 401)

            assertEquals(SyncOutcome.SUCCESS, phoneA.engine.sync())

            assertEquals("refresh-new", phoneA.session.refreshToken())
            assertEquals(1, server.liveRows("event").size)
            assertTrue(phoneA.queue().isEmpty())
        }

    @Test
    fun `a rejected session stops syncing and keeps the queue for the next sign-in`() =
        runBlocking {
            val baby = babyOnServer()
            phoneA.events.logBottle(baby, 120)
            server.failAfterProcessing("/sync/push", 401)
            server.refreshTokenAccepted = false

            assertEquals(SyncOutcome.SIGNED_OUT, phoneA.engine.sync())

            assertTrue(phoneA.session.state.first() is SessionState.SignedOut)
            assertEquals(1, phoneA.queue().size)
            assertEquals(SyncOutcome.SIGNED_OUT, phoneA.engine.sync())
        }

    @Test
    fun `a failure between pages keeps what was stored and resumes from there`() =
        runBlocking {
            val baby = babyOnServer()
            repeat(5) { phoneA.events.logBottle(baby, 60 + it) }
            phoneA.engine.sync()

            // Pages of three rows: family, baby, bottle | three bottles | one bottle.
            server.pageSize = 3
            server.failAfterProcessing("/sync", FakeSyncServer.PASS, 503)

            assertEquals(SyncOutcome.RETRY, phoneB.engine.sync())

            assertEquals(1, phoneB.allEvents().size)
            val resumeFrom = phoneB.db.syncStateDao().get()!!
            assertNotNull(resumeFrom.cursor)
            // Not a completed sync yet.
            assertNull(resumeFrom.lastSuccessAt)

            val before = server.calls.size
            assertEquals(SyncOutcome.SUCCESS, phoneB.engine.sync())

            assertEquals(resumeFrom.cursor, server.calls[before].cursor)
            assertEquals(phoneA.allEvents(), phoneB.allEvents())
            assertNotNull(
                phoneB.db
                    .syncStateDao()
                    .get()!!
                    .lastSuccessAt,
            )

            // Nothing new: one request from the latest cursor, which does not move.
            val cursor =
                phoneB.db
                    .syncStateDao()
                    .get()!!
                    .cursor
            phoneB.engine.sync()
            assertEquals(cursor, server.calls.last().cursor)
            assertEquals(
                cursor,
                phoneB.db
                    .syncStateDao()
                    .get()!!
                    .cursor,
            )
        }

    @Test
    fun `after the server loses its history nothing recorded on the phone disappears`() =
        runBlocking {
            val baby = babyOnServer()
            val synced = phoneA.events.logBottle(baby, 120).event()
            phoneA.engine.sync()
            phoneB.engine.sync()

            // A edits offline; then the server is restored empty.
            phoneA.events.update(synced.copy(amountMl = 150))
            server.loseEverything()

            // Phone B has nothing unsent: its copies of rows the server no longer has are removed.
            assertEquals(SyncOutcome.SUCCESS, phoneB.engine.sync())
            assertTrue(phoneB.allEvents().isEmpty())

            // Phone A's edit of a row the server no longer knows is refused at first; on
            // discovering the reset, the phone uploads the row again as new, in its current state.
            assertEquals(SyncOutcome.SUCCESS, phoneA.engine.sync())

            assertEquals(150, phoneA.event(synced.id)!!.amountMl)
            assertEquals(1, phoneA.event(synced.id)!!.version)
            assertEquals(150, server.liveRows("event").single().int("amount_ml"))
            assertEquals(
                0,
                phoneA.db
                    .pendingMutationDao()
                    .observeFailedCount()
                    .first(),
            )
            // The baby the event belongs to was uploaded again first.
            assertEquals("Vera", server.liveRows("baby").single().text("name"))
            assertEquals(
                0,
                phoneA.db
                    .pendingMutationDao()
                    .observeFailedCount()
                    .first(),
            )
            assertTrue(phoneA.queue().isEmpty())

            // Phone B gets everything back from A through the server.
            phoneB.engine.sync()
            assertEquals(phoneA.allEvents(), phoneB.allEvents())
            assertEquals(
                "Vera",
                phoneB.babies
                    .observeAll()
                    .first()
                    .single()
                    .name,
            )
        }

    @Test
    fun `events recorded but never uploaded survive a server reset together with their baby`() =
        runBlocking {
            val baby = babyOnServer()
            server.loseEverything()
            // Recorded offline after the last successful sync.
            phoneA.events.logBottle(baby, 90)

            assertEquals(SyncOutcome.SUCCESS, phoneA.engine.sync())

            assertEquals(90, server.liveRows("event").single().int("amount_ml"))
            assertEquals(1, server.liveRows("baby").size)
            assertEquals(
                0,
                phoneA.db
                    .pendingMutationDao()
                    .observeFailedCount()
                    .first(),
            )
        }
}
