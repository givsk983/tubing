package com.stealth.rootexecutor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ExecutionRecord(
    val id: String,
    val kind: String,
    val name: String,
    val mode: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String = "正在运行",
    val exitCode: Int? = null
)

object ExecutionHistoryRecords {
    const val DEFAULT_LIMIT = 50

    fun start(records: List<ExecutionRecord>, record: ExecutionRecord, limit: Int = DEFAULT_LIMIT) =
        (records.filterNot { it.id == record.id } + record).takeLast(limit)

    fun finish(records: List<ExecutionRecord>, id: String, status: String, exitCode: Int?, endedAt: Long) =
        records.map { if (it.id == id) it.copy(endedAt = endedAt, status = status, exitCode = exitCode) else it }
}

/** Service-owned history includes normal runs and quick shortcuts, even with logging disabled. */
object ExecutionHistory {
    private fun preferences(context: Context) = context.getSharedPreferences("execution_history", Context.MODE_PRIVATE)

    fun limit(context: Context) = preferences(context).getInt("limit", ExecutionHistoryRecords.DEFAULT_LIMIT).coerceAtLeast(1)

    @Synchronized fun setLimit(context: Context, limit: Int) {
        require(limit > 0)
        preferences(context).edit().putInt("limit", limit).apply()
        save(context, read(context))
    }

    @Synchronized fun read(context: Context): List<ExecutionRecord> = try {
        val array = JSONArray(preferences(context).getString("records", "[]"))
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            ExecutionRecord(item.getString("id"), item.getString("kind"), item.getString("name"),
                item.getString("mode"), item.getLong("startedAt"),
                if (item.isNull("endedAt")) null else item.getLong("endedAt"), item.getString("status"),
                if (item.isNull("exitCode")) null else item.getInt("exitCode"))
        }.takeLast(limit(context))
    } catch (_: Exception) { emptyList() }

    private fun save(context: Context, records: List<ExecutionRecord>) {
        val array = JSONArray()
        records.takeLast(limit(context)).forEach { record ->
            array.put(JSONObject().put("id", record.id).put("kind", record.kind).put("name", record.name)
                .put("mode", record.mode).put("startedAt", record.startedAt)
                .put("endedAt", record.endedAt ?: JSONObject.NULL).put("status", record.status)
                .put("exitCode", record.exitCode ?: JSONObject.NULL))
        }
        preferences(context).edit().putString("records", array.toString()).apply()
    }

    @Synchronized fun start(context: Context, record: ExecutionRecord) = save(context, ExecutionHistoryRecords.start(read(context), record, limit(context)))

    @Synchronized fun finish(context: Context, id: String, status: String, exitCode: Int?) {
        save(context, ExecutionHistoryRecords.finish(read(context), id, status, exitCode, System.currentTimeMillis()))
    }

    @Synchronized fun clear(context: Context) { preferences(context).edit().remove("records").apply() }
}
