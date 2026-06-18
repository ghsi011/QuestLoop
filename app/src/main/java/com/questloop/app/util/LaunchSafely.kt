package com.questloop.app.util

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Launches [block] on [viewModelScope] but never lets a failure escape. A store
 * error (Room/DataStore throwing) inside a bare `viewModelScope.launch` reaches
 * the scope's uncaught-exception handler and crashes the whole app; here it's
 * caught and routed to [onError] instead, so the screen degrades rather than dies.
 *
 * Cancellation is rethrown so structured concurrency (scope clears on
 * onCleared()) keeps working. The default handler logs; the [Log] call is wrapped
 * so plain-JVM unit tests (where android.util.Log isn't mocked) don't fail.
 */
fun ViewModel.launchSafely(
    onError: (Throwable) -> Unit = { runCatching { Log.e("QuestLoop", "ViewModel coroutine failed", it) } },
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        onError(t)
    }
}
