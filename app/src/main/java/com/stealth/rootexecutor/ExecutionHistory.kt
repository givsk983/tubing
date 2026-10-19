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
    fun start(records: List<ExecutionRecord>, record: ExecutionRecord) =
        (records.filterNot { it.id == record.id } + record).takeLast(200)

    fun finish(records: List<ExecutionRecord>, id: String, status: String, exitCode: Int?, endedAt: Long) =
        records.map { if (it.id == id) it.copy(endedAt = endedAt, status = status, exitCode = exitCode) else it }
}

/** Service-owned history includes normal runs and quick shortcuts, even with logging disabled. */
object ExecutionHistory {
    private fun preferences(context: Context) = context.getSharedPreferences("execution_history", Context.MODE_PRIVATE)

    @Synchronized fun read(context: Context): List<ExecutionRecord> = try {
        val array = JSONArray(preferences(context).getString("records", "[]"))
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            ExecutionRecord(item.getString("id"), item.getString("kind"), item.getString("name"),
                item.getString("mode"), item.getLong("startedAt"),
                if (item.isNull("endedAt")) null else item.getLong("endedAt"), item.getString("status"),
                if (item.isNull("exitCode")) null else item.getInt("exitCode"))
        }
    } catch (_: Exception) { emptyList() }

    private fun save(context: Context, records: List<ExecutionRecord>) {
        val array = JSONArray()
        records.takeLast(200).forEach { record ->
            array.put(JSONObject().put("id", record.id).put("kind", record.kind).put("name", record.name)
                .put("mode", record.mode).put("startedAt", record.startedAt)
                .put("endedAt", record.endedAt ?: JSONObject.NULL).put("status", record.status)
                .put("exitCode", record.exitCode ?: JSONObject.NULL))
        }
        preferences(context).edit().putString("records", array.toString()).apply()
    }

    @Synchronized fun start(context: Context, record: ExecutionRecord) = save(context, ExecutionHistoryRecords.start(read(context), record))

    @Synchronized fun finish(context: Context, id: String, status: String, exitCode: Int?) {
        save(context, ExecutionHistoryRecords.finish(read(context), id, status, exitCode, System.currentTimeMillis()))
    }

    @Synchronized fun clear(context: Context) { preferences(context).edit().remove("records").apply() }
}
