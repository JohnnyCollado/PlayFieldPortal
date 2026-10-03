package com.playfieldportal.feature.launcher.kb

import com.playfieldportal.core.data.kb.LegacyEmulatorIdRewriter
import com.playfieldportal.core.data.kb.PlatformKnowledgeApplier
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKb
import com.playfieldportal.feature.launcher.EmulatorAutoConfigService
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class EmulatorKnowledgeRefresherTest {

    private val store = mockk<EmulatorKnowledgeStore>()
    private val applier = mockk<PlatformKnowledgeApplier>()
    private val autoConfig = mockk<EmulatorAutoConfigService>()
    private val profiles = mockk<EmulatorProfileRepository>()
    private val rewriter = mockk<LegacyEmulatorIdRewriter>()
    private val refresher = EmulatorKnowledgeRefresher(store, applier, autoConfig, profiles, rewriter)

    init {
        coEvery { store.current() } returns EffectiveKb(emptyList(), emptyList(), officialApplied = false)
        coEvery { applier.apply(any()) } returns emptyList()
        coEvery { profiles.getAllPersistedProfiles() } returns emptyList()
        coEvery { profiles.bundledProfileIds() } returns setOf("winlator", "gamehub")
        coEvery { rewriter.run(any(), any()) } returns 0
    }

    @Test fun `bundled shortcut ids are never rewritten`() = runBlocking<Unit> {
        coEvery { autoConfig.runOnStartup() } returns Unit

        refresher.run()

        coVerify { rewriter.run(any(), match { it.containsAll(listOf("winlator", "gamehub")) }) }
    }

    @Test fun `concurrent runs never overlap`() = runBlocking<Unit> {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        coEvery { autoConfig.runOnStartup() } coAnswers {
            peak.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(30)
            active.decrementAndGet()
        }

        List(4) { async(kotlinx.coroutines.Dispatchers.Default) { refresher.run() } }.awaitAll()

        assertEquals(1, peak.get())
    }
}
