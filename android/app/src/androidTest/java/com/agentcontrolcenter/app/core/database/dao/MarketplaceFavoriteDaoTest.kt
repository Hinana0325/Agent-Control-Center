package com.agentcontrolcenter.app.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentcontrolcenter.app.core.database.AppDatabase
import com.agentcontrolcenter.app.core.database.entity.MarketplaceFavoriteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v4.9.0: MarketplaceFavoriteDao 收藏 CRUD 测试。
 * 使用内存数据库，不触发 migration。
 */
@RunWith(AndroidJUnit4::class)
class MarketplaceFavoriteDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MarketplaceFavoriteDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.marketplaceFavoriteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun favorite(
        id: String,
        name: String = "Agent $id",
        addedAt: Long = System.currentTimeMillis()
    ) = MarketplaceFavoriteEntity(
        agentId = id,
        name = name,
        description = "desc",
        type = "Hermes",
        serverUrl = "wss://example.com",
        author = "Tester",
        tagsJson = """["tag1","tag2"]""",
        addedAt = addedAt
    )

    @Test
    fun upsert_and_getAllFlow() = runTest {
        dao.upsert(favorite("a1", addedAt = 1000L))
        dao.upsert(favorite("a2", addedAt = 2000L))

        val all = dao.getAllFlow().first()
        assertEquals(2, all.size)
        // 按时间倒序：a2 在前
        assertEquals("a2", all[0].agentId)
        assertEquals("a1", all[1].agentId)
    }

    @Test
    fun upsert_replacesExistingAgentId() = runTest {
        dao.upsert(favorite("a1", name = "Original"))
        dao.upsert(favorite("a1", name = "Replaced"))

        val all = dao.getAllFlow().first()
        assertEquals(1, all.size)
        assertEquals("Replaced", all[0].name)
    }

    @Test
    fun delete_removesFavorite() = runTest {
        dao.upsert(favorite("a1"))
        dao.upsert(favorite("a2"))

        dao.delete("a1")
        // Room @Query DELETE 不返回受影响行数；断言通过剩余集合验证
        val remaining = dao.getAllFlow().first()
        assertEquals(1, remaining.size)
        assertEquals("a2", remaining[0].agentId)
    }

    @Test
    fun isFavorite_reflectsState() = runTest {
        assertFalse(dao.isFavorite("a1"))
        dao.upsert(favorite("a1"))
        assertTrue(dao.isFavorite("a1"))
        dao.delete("a1")
        assertFalse(dao.isFavorite("a1"))
    }

    @Test
    fun getFavoriteIdsFlow_returnsAllIds() = runTest {
        dao.upsert(favorite("a1"))
        dao.upsert(favorite("a2"))
        dao.upsert(favorite("a3"))

        val ids = dao.getFavoriteIdsFlow().first()
        assertEquals(3, ids.size)
        assertTrue(ids.contains("a1"))
        assertTrue(ids.contains("a2"))
        assertTrue(ids.contains("a3"))
    }

    @Test
    fun getAllOnce_returnsListSnapshot() = runTest {
        dao.upsert(favorite("a1"))
        dao.upsert(favorite("a2"))

        val snapshot = dao.getAllOnce()
        assertEquals(2, snapshot.size)
    }
}
