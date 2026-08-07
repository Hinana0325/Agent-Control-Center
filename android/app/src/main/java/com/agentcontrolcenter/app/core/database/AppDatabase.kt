package com.agentcontrolcenter.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentcontrolcenter.app.core.database.dao.ActivityDao
import com.agentcontrolcenter.app.core.database.dao.AgentConfigDao
import com.agentcontrolcenter.app.core.database.dao.MarketplaceFavoriteDao
import com.agentcontrolcenter.app.core.database.dao.MessageDao
import com.agentcontrolcenter.app.core.database.dao.PluginDao
import com.agentcontrolcenter.app.core.database.dao.SessionDao
import com.agentcontrolcenter.app.core.database.dao.TaskDao
import com.agentcontrolcenter.app.core.database.dao.WorkflowRunDao
import com.agentcontrolcenter.app.core.database.entity.ActivityLogEntity
import com.agentcontrolcenter.app.core.database.entity.AgentConfigEntity
import com.agentcontrolcenter.app.core.database.entity.MarketplaceFavoriteEntity
import com.agentcontrolcenter.app.core.database.entity.MessageEntity
import com.agentcontrolcenter.app.core.database.entity.PluginEntity
import com.agentcontrolcenter.app.core.database.entity.SessionEntity
import com.agentcontrolcenter.app.core.database.entity.TaskEntity
import com.agentcontrolcenter.app.core.database.entity.WorkflowRunEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        AgentConfigEntity::class,
        ActivityLogEntity::class,
        PluginEntity::class,
        TaskEntity::class,
        WorkflowRunEntity::class,
        MarketplaceFavoriteEntity::class
    ],
    version = 11,
    // 启用 schema 导出以便迁移测试。
    // TODO: 需在 app/build.gradle 的 ksp / kapt 配置中添加
    //   ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    //   并将 schemas 目录纳入版本控制，否则编译期会发出警告。
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun agentConfigDao(): AgentConfigDao
    abstract fun activityDao(): ActivityDao
    abstract fun pluginDao(): PluginDao
    abstract fun taskDao(): TaskDao
    abstract fun workflowRunDao(): WorkflowRunDao
    abstract fun marketplaceFavoriteDao(): MarketplaceFavoriteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_timestamp ON messages (sessionId, timestamp)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToId TEXT DEFAULT NULL")
            }
        }

        /**
         * Phase 2.4: 为 activity_log.timestamp / messages.timestamp / messages.role
         * 添加单列索引，避免分页查询和角色过滤时全表扫描。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_log_timestamp ON activity_log (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_role ON messages (role)")
            }
        }

        /**
         * Phase 4.2: 新增 tasks 表，支持 TaskManager 持久化。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        agentId TEXT NOT NULL,
                        sessionId TEXT,
                        type TEXT NOT NULL,
                        input TEXT NOT NULL,
                        status TEXT NOT NULL,
                        result TEXT,
                        createdAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        error TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_agentId ON tasks (agentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_createdAt ON tasks (createdAt)")
            }
        }

        /**
         * 跨端 schema 对齐：agent_configs 表新增 protocolType 列，对应
         * [com.agentcontrolcenter.app.agent.model.AgentProtocol.rawValue]，
         * 默认 "WebSocket"（与 iOS AgentConfigEntity 默认值一致）。
         *
         * 现有行通过 ALTER TABLE ADD COLUMN 填充 DEFAULT 值，所有存量 Agent
         * 自动获得 WebSocket 协议类型，与 TransportFactory 旧实现按 AgentType
         * 路由时的实际协议一致。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE agent_configs ADD COLUMN protocolType TEXT NOT NULL DEFAULT 'WebSocket'"
                )
            }
        }

        /**
         * v4.9.0: 新增 workflow_runs 表，支持工作流执行历史持久化。
         * 对应 protocol/schemas/workflow-schema.json 中的 WorkflowRunRecord 契约。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workflow_runs (
                        id TEXT NOT NULL PRIMARY KEY,
                        workflowId TEXT NOT NULL,
                        workflowName TEXT NOT NULL,
                        input TEXT NOT NULL DEFAULT '',
                        output TEXT NOT NULL DEFAULT '',
                        startedAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        status TEXT NOT NULL,
                        failedNodeIdsJson TEXT NOT NULL DEFAULT '[]',
                        error TEXT,
                        logsJson TEXT NOT NULL DEFAULT '[]'
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_runs_workflowId ON workflow_runs (workflowId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_runs_startedAt ON workflow_runs (startedAt)")
            }
        }

        /**
         * v4.9.0: 新增 marketplace_favorites 表，支持 Marketplace 收藏持久化。
         * 冗余 name/type/serverUrl 等字段，便于从收藏列表直接安装。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS marketplace_favorites (
                        agentId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        type TEXT NOT NULL,
                        serverUrl TEXT NOT NULL,
                        author TEXT NOT NULL,
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        addedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_marketplace_favorites_addedAt ON marketplace_favorites (addedAt)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agentcontrolcenter.db"
                )
                    // 不使用 fallbackToDestructiveMigration()：缺少迁移时会抛
                    // IllegalStateException，优先于静默丢失用户数据。
                    // 当前已提供 MIGRATION_4_5 / _5_6 / _6_7 / _7_8 / _8_9；后续新增版本时
                    // 必须显式补齐对应的 Migration。
                    .addMigrations(
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                        MIGRATION_10_11
                    )
                    .build().also { INSTANCE = it }
            }
        }
    }
}
