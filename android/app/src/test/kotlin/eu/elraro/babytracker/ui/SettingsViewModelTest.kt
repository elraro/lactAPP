package eu.elraro.babytracker.ui

import eu.elraro.babytracker.FakeTime
import eu.elraro.babytracker.R
import eu.elraro.babytracker.TestBackend
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.sync.SyncMonitor
import eu.elraro.babytracker.data.sync.SyncOutcome
import eu.elraro.babytracker.data.sync.SyncRunner
import eu.elraro.babytracker.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val backend = TestBackend()
    private val runner =
        object : SyncRunner {
            override suspend fun sync() = SyncOutcome.SUCCESS

            override suspend fun discardFailed() = Unit
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runBlocking { backend.signedIn() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel() =
        SettingsViewModel(
            backend.auth,
            SyncMonitor(backend.database),
            runner,
            backend.ai,
            BabyRepository(backend.database, FakeTime()) {},
        )

    private fun status(enabled: Boolean) =
        """{"configured":true,"enabled":$enabled,"provider":"anthropic","model":"m","remaining_today":20}"""

    @Test
    fun `consent is sent to the server and the switch shows what the server stored`() =
        runBlocking {
            backend.enqueueJson(200, status(enabled = false))
            val vm = viewModel()
            assertEquals(false, withTimeout(10_000) { vm.aiStatus.first { it != null } }!!.enabled)
            assertEquals(
                "/api/v1/ai/status",
                backend.server
                    .takeRequest()
                    .url.encodedPath,
            )

            backend.enqueueJson(200, """{"family":{}}""")
            backend.enqueueJson(200, status(enabled = true))
            vm.setAiEnabled(true)

            assertEquals(true, withTimeout(10_000) { vm.aiStatus.first { it?.enabled == true } }!!.enabled)
            val patch = backend.server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("/api/v1/family", patch.url.encodedPath)
            assertEquals("""{"ai_enabled":true}""", patch.body!!.utf8())
        }

    @Test
    fun `offline the AI setting is unknown rather than shown as off`() =
        runBlocking {
            backend.server.close()
            val vm = viewModel()
            vm.setAiEnabled(true) // cannot reach the server: nothing changes, nothing is queued
            assertNull(vm.aiStatus.value)
        }

    @Test
    fun `an invitation code is requested from the server and shown, or the failure explained`() =
        runBlocking {
            backend.enqueueJson(200, status(enabled = false))
            val vm = viewModel()
            withTimeout(10_000) { vm.aiStatus.first { it != null } }
            backend.server.takeRequest()

            backend.enqueueJson(201, """{"code":"K7QF-2M9X-PD4T","expires_at":"2026-10-10T12:00:00Z"}""")
            vm.createInvite()
            val shown = withTimeout(10_000) { vm.invite.first { it?.code != null } }!!
            assertEquals("K7QF-2M9X-PD4T", shown.code)
            assertEquals(
                "/api/v1/auth/invites",
                backend.server
                    .takeRequest()
                    .url.encodedPath,
            )

            vm.dismissInvite()
            assertNull(vm.invite.value)

            backend.server.close()
            vm.createInvite()
            val failed = withTimeout(10_000) { vm.invite.first { it?.error != null } }!!
            assertEquals(R.string.error_network, failed.error)
        }
}
