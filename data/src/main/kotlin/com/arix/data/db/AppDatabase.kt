package com.arix.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.arix.data.dao.ApiConfigDao
import com.arix.data.dao.CharacterCardDao
import com.arix.data.dao.ConversationDao
import com.arix.data.dao.TagDao
import com.arix.data.dao.MemoryDao
import com.arix.data.entity.ApiConfigEntity
import com.arix.data.entity.CharacterCardEntity
import com.arix.data.entity.ConversationEntity
import com.arix.data.entity.ConversationTagCrossRef
import com.arix.data.entity.TagEntity
import com.arix.data.entity.MemoryEntity
import com.arix.data.entity.MemoryTagEntity
import com.arix.data.entity.MemoryTagCrossRef
import com.arix.data.search.ChatSearchIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ApiConfigEntity::class,
        CharacterCardEntity::class,
        ConversationEntity::class,
        TagEntity::class,
        ConversationTagCrossRef::class,
        MemoryEntity::class,
        MemoryTagEntity::class,
        MemoryTagCrossRef::class
    ],
    version = 22,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun apiConfigDao(): ApiConfigDao
    abstract fun characterCardDao(): CharacterCardDao
    abstract fun conversationDao(): ConversationDao
    abstract fun tagDao(): TagDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        const val DB_NAME = "arix.db"

        /** 关闭并释放单例（全量恢复覆盖 DB 文件前必须调用，否则文件被占用）。下次 getInstance 会重新打开。 */
        fun closeInstance() = synchronized(this) {
            // 先摘掉索引器：它持有 openHelper 句柄并在后台写库，DB 文件正要被整个覆盖掉。
            try { ChatSearchIndex.detach() } catch (_: Exception) {}
            try { INSTANCE?.close() } catch (_: Exception) {}
            INSTANCE = null
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS conversations (configId INTEGER NOT NULL, messagesJson TEXT NOT NULL, PRIMARY KEY(configId))")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS conversations")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS character_cards (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        characterSetting TEXT NOT NULL DEFAULT '',
                        openingStatement TEXT NOT NULL DEFAULT '',
                        avatarPath TEXT NOT NULL DEFAULT '',
                        attachedTagIds TEXT NOT NULL DEFAULT '',
                        isDefault INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        characterCardId INTEGER,
                        configId INTEGER,
                        title TEXT NOT NULL DEFAULT '新对话',
                        messagesJson TEXT NOT NULL DEFAULT '[]',
                        isPinned INTEGER NOT NULL DEFAULT 0,
                        isArchived INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY (characterCardId) REFERENCES character_cards(id) ON DELETE SET NULL,
                        FOREIGN KEY (configId) REFERENCES api_configs(id) ON DELETE SET NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_characterCardId ON conversations (characterCardId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_configId ON conversations (configId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        color TEXT NOT NULL DEFAULT '#45475A'
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS conversation_tag_cross_ref (
                        conversationId INTEGER NOT NULL,
                        tagId INTEGER NOT NULL,
                        PRIMARY KEY(conversationId, tagId),
                        FOREIGN KEY (conversationId) REFERENCES conversations(id) ON DELETE CASCADE,
                        FOREIGN KEY (tagId) REFERENCES tags(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_tag_cross_ref_tagId ON conversation_tag_cross_ref (tagId)")
                db.execSQL("INSERT INTO character_cards (name, description, characterSetting, openingStatement, avatarPath, attachedTagIds, isDefault, createdAt, updatedAt) VALUES ('无角色 / 通用助手', '', '', '', '', '', 1, ${System.currentTimeMillis()}, ${System.currentTimeMillis()})")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE api_configs ADD COLUMN purpose TEXT NOT NULL DEFAULT 'chat'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE character_cards ADD COLUMN tone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE character_cards ADD COLUMN length TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE character_cards ADD COLUMN language TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE character_cards ADD COLUMN waifuEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE character_cards ADD COLUMN waifuDelayMs INTEGER NOT NULL DEFAULT 250")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE api_configs ADD COLUMN supportsVision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN supportsAudio INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN supportsVideo INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        source TEXT NOT NULL DEFAULT 'user_input',
                        importance REAL NOT NULL DEFAULT 0.5,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_title ON memories (title)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_updatedAt ON memories (updatedAt)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_tags_name ON memory_tags (name)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_tag_cross_ref (
                        memoryId INTEGER NOT NULL,
                        tagId INTEGER NOT NULL,
                        PRIMARY KEY(memoryId, tagId)
                    )
                """)
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN characterCardId INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_characterCardId ON memories (characterCardId)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE api_configs ADD COLUMN temperature REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN topP REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN maxTokens INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN frequencyPenalty REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN presencePenalty REAL DEFAULT NULL")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE api_configs ADD COLUMN customHeaders TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE character_cards ADD COLUMN worldBook TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN type TEXT NOT NULL DEFAULT 'fact'")
                db.execSQL("ALTER TABLE memories ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN lastAccessedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN relatedIds TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN source TEXT NOT NULL DEFAULT 'chat'")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            }
        }

        // 会话分支树：nullable 列，老对话为 NULL(线性)，不破坏任何现有读取。
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN branchesJson TEXT")
            }
        }

        // 语义记忆：memories 加 embedding 列（nullable，老记忆 NULL=未建索引，回退关键词检索）。
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN embedding TEXT")
            }
        }

        // 记忆关联边加 type/weight：新列 relatedMeta（JSON 边元数据）。老边缺项 → related/1.0（向后兼容），relatedIds 仍是边存在与否的源真值。
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN relatedMeta TEXT NOT NULL DEFAULT ''")
            }
        }

        // 记忆加用户自建文件夹分组：新列 folder（空串=未分组，向后兼容老记忆）。
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            }
        }

        // 记忆加「断言时间」+「置信度」：
        // assertedAt = 这条事实发生/被断言的时间（0=未知，读取方回退 createdAt），没有它就没法判断「新事实推翻旧事实」；
        // confidence = 0~1 置信度（0.8=不区分，等同老数据行为），检索打分与冲突消解都要用。
        // 两列都给 NOT NULL + DEFAULT，老行自动补默认值，行为与升级前一致。
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN assertedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN confidence REAL NOT NULL DEFAULT 0.8")
            }
        }

        // 跨对话聊天记录的全文索引（FTS4 + bigram）。这两张表**不是 Room 实体**，由 ChatSearchIndex
        // 用裸 SQL 建/读/写，Room 不认识也就不会做 schema 校验——虚拟表的 CREATE 语句和 Room 生成的
        // 差一个字就会在开库时抛异常 = 启动即崩，为一个"锦上添花"的搜索索引不值得冒这个险。
        // 这里仍然走正式迁移（而不是只靠运行时的 IF NOT EXISTS 兜底），是为了升级时一次建好、
        // 不把建表推迟到用户第一次搜索。
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(ChatSearchIndex.CREATE_INDEX_SQL)
                db.execSQL(ChatSearchIndex.CREATE_STATE_SQL)
                // 老数据不在这里回填：迁移跑在开库的主路径上，几百个会话的 JSON 解析会把启动拖死。
                // 由 ChatSearchIndex.ensureBackfill() 后台分批补建（可中断、断点续跑）。
            }
        }

        // 会话锁定：新列 isLocked（NOT NULL DEFAULT 0，老会话一律未锁定，向后兼容）。
        // 只挡删除与批量清理，不影响任何现有查询/排序——纯增量列，见 ConversationDao 的 ORDER BY 注释。
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arix.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // 防御：种子插入**绝不能**让全新安装崩在启动（否则连崩溃报告页都进不去，无限闪退）。
                            // worldBook 必须写入：fresh 时 Room 建表该列 NOT NULL 且无 SQL 默认值，漏列 NULL 约束崩溃
                            // (仅升级 DB 因 MIGRATION_11_12 带 DEFAULT '' 才幸免)。即便如此仍整体 try/catch 兜底。
                            try {
                                db.execSQL("INSERT INTO character_cards (name, description, characterSetting, worldBook, openingStatement, avatarPath, attachedTagIds, isDefault, createdAt, updatedAt, tone, length, language, waifuEnabled, waifuDelayMs) VALUES ('无角色 / 通用助手', '', '', '', '', '', '', 1, ${System.currentTimeMillis()}, ${System.currentTimeMillis()}, '', '', '', 0, 250)")
                            } catch (e: Exception) {
                                android.util.Log.e("AppDatabase", "种子默认角色卡失败(不致命，继续启动): ${e.message}")
                            }
                            // 全新安装走的是 onCreate 而非迁移链，聊天索引表得在这里建（同样绝不能崩启动）。
                            try {
                                db.execSQL(ChatSearchIndex.CREATE_INDEX_SQL)
                                db.execSQL(ChatSearchIndex.CREATE_STATE_SQL)
                            } catch (e: Exception) {
                                android.util.Log.e("AppDatabase", "建聊天索引表失败(不致命，搜索会退回全扫): ${e.message}")
                            }
                        }
                    })
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { INSTANCE = it; ChatSearchIndex.attach(it) }
            }
        }
    }
}
