package com.agentcontrolcenter.app.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentcontrolcenter.app.core.database.AppDatabase
import com.agentcontrolcenter.app.core.database.entity.WorkflowRunEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v4.9.0: WorkflowRunDao 插入/查询/更新测试。
 * 使用内存数据库，不触发 migration。
 */
@RunWith(AndroidJUnit4::class)
class WorkflowRunDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkflowRunDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.workflowRunDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_and_getRunById() = runTest {
        val run = WorkflowRunEntity(
            id = "run-1",
            workflowId = "wf-1",
            workflowName = "翻译链",
            input = "Hello",
            startedAt = 1000L,
            completedAt = null,
            status = "RUNNING",
            error = null
        )
        dao.insert(run)

        val loaded = dao.getRunById("run-1")
        assertNotNull(loaded)
        assertEquals("翻译链", loaded?.workflowName)
        assertEquals("RUNNING", loaded?.status)
        assertNull(loaded?.completedAt)
    }

    @Test
    fun updateStatus_changesRunningToCompleted() = runTest {
        val run = WorkflowRunEntity(
            id = "run-2",
            workflowId = "wf-1",
            workflowName = "翻译链",
            input = "Hello",
            startedAt = 1000L,
            completedAt = null,
            status = "RUNNING",
            error = null
        )
        dao.insert(run)

        dao.updateStatus(
            id = "run-2",
            status = "COMPLETED",
            completedAt = 2000L,
            output = "你好",
            error = null,
            logsJson = """["log1","log2"]""",
            failedNodeIdsJson = "[]"
        )

        val loaded = dao.getRunById("run-2")
        assertEquals("COMPLETED", loaded?.status)
        assertEquals(2000L, loaded?.completedAt)
        assertEquals("你好", loaded?.output)
    }

    @Test
    fun updateStatus_changesRunningToFailed() = runTest {
        val run = WorkflowRunEntity(
            id = "run-3",
            workflowId = "wf-1",
            workflowName = "翻译链",
            input = "Hello",
            startedAt = 1000L,
            completedAt = null,
            status = "RUNNING",
            error = null
        )
        dao.insert(run)

        dao.updateStatus(
            id = "run-3",
            status = "FAILED",
            completedAt = 1500L,
            output = "",
            error = "Agent timeout",
            logsJson = """["error"]""",
            failedNodeIdsJson = """["node-2"]"""
        )

        val loaded = dao.getRunById("run-3")
        assertEquals("FAILED", loaded?.status)
        assertEquals("Agent timeout", loaded?.error)
    }

    @Test
    fun getRecentRunsFlow_returnsSortedByStartedAtDesc() = runTest {
        dao.insert(
            WorkflowRunEntity(
                id = "old", workflowId = "wf", workflowName = "Old",
                input = "", startedAt = 1000L, completedAt = 1100L, status = "COMPLETED",
                error = null
            )
        )
        dao.insert(
            WorkflowRunEntity(
                id = "new", workflowId = "wf", workflowName = "New",
                input = "", startedAt = 2000L, completedAt = 2100L, status = "COMPLETED",
                error = null
            )
        )

        val runs = dao.getRecentRunsFlow(10).first()
        assertEquals(2, runs.size)
        // 按时间倒序：最新的在前
        assertEquals("new", runs[0].id)
        assertEquals("old", runs[1].id)
    }

    @Test
    fun getRunsForWorkflowFlow_filtersByWorkflowId() = runTest {
        dao.insert(
            WorkflowRunEntity(
                id = "r1", workflowId = "wf-a", workflowName = "A",
                input = "", startedAt = 1000L, completedAt = 1100L, status = "COMPLETED",
                error = null
            )
        )
        dao.insert(
            WorkflowRunEntity(
                id = "r2", workflowId = "wf-b", workflowName = "B",
                input = "", startedAt = 2000L, completedAt = 2100L, status = "COMPLETED",
                error = null
            )
        )

        val wfA = dao.getRunsForWorkflowFlow("wf-a").first()
        assertEquals(1, wfA.size)
        assertEquals("r1", wfA[0].id)

        val wfB = dao.getRunsForWorkflowFlow("wf-b").first()
        assertEquals(1, wfB.size)
        assertEquals("r2", wfB[0].id)
    }

    @Test
    fun deleteOlderThan_removesOldRuns() = runTest {
        dao.insert(
            WorkflowRunEntity(
                id = "old", workflowId = "wf", workflowName = "Old",
                input = "", startedAt = 1000L, completedAt = 1100L, status = "COMPLETED",
                error = null
            )
        )
        dao.insert(
            WorkflowRunEntity(
                id = "new", workflowId = "wf", workflowName = "New",
                input = "", startedAt = 5000L, completedAt = 5100L, status = "COMPLETED",
                error = null
            )
        )

        val deleted = dao.deleteOlderThan(3000L)
        assertEquals(1, deleted)

        val remaining = dao.getRecentRunsFlow(10).first()
        assertEquals(1, remaining.size)
        assertEquals("new", remaining[0].id)
    }

    @Test
    fun getRunCount_returnsTotalCount() = runTest {
        assertEquals(0, dao.getRunCount())
        dao.insert(
            WorkflowRunEntity(
                id = "r1", workflowId = "wf", workflowName = "A",
                input = "", startedAt = 1000L, completedAt = null, status = "RUNNING",
                error = null
            )
        )
        dao.insert(
            WorkflowRunEntity(
                id = "r2", workflowId = "wf", workflowName = "B",
                input = "", startedAt = 2000L, completedAt = 2100L, status = "COMPLETED",
                error = null
            )
        )
        assertEquals(2, dao.getRunCount())
    }

    @Test
    fun insert_replacesOnConflict() = runTest {
        val original = WorkflowRunEntity(
            id = "run-x", workflowId = "wf", workflowName = "Original",
            input = "in", startedAt = 1000L, completedAt = null, status = "RUNNING",
            error = null
        )
        dao.insert(original)

        val replacement = original.copy(workflowName = "Replaced", status = "COMPLETED", completedAt = 2000L)
        dao.insert(replacement)

        val loaded = dao.getRunById("run-x")
        assertEquals("Replaced", loaded?.workflowName)
        assertEquals("COMPLETED", loaded?.status)
        assertEquals(2000L, loaded?.completedAt)
    }
}
