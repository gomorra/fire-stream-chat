package com.firestream.chat.data.remote.firebase

import com.google.android.gms.tasks.Task
import io.mockk.every

/** Makes a mocked [task] report success at once, so `await()` returns without a listener. Its result is null until stubbed. */
internal fun <T> completeImmediately(task: Task<T>) {
    every { task.isComplete } returns true
    every { task.isCanceled } returns false
    every { task.exception } returns null
    every { task.result } returns null
}
