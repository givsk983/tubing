package com.stealth.rootexecutor

/** A newer launch invalidates cleanup belonging to the previous execution. */
internal class QuickExitRequest {
    private var pending: String? = null
    fun complete(id: String, shortcut: Boolean, enabled: Boolean) {
        pending = id.takeIf { shortcut && enabled }
    }
    fun cancel() { pending = null }
    fun matches(id: String?) = id != null && pending == id
    fun canExit(id: String?, enabled: Boolean, running: Boolean, preparing: Boolean, serviceActive: Boolean) =
        matches(id) && enabled && !running && !preparing && !serviceActive
}
