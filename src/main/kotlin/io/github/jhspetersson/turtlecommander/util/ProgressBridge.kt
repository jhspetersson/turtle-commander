package io.github.jhspetersson.turtlecommander.util

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [body] under a cancellable background progress, handing it a live [ProgressIndicator]
 * for text / text2 / fraction updates.
 *
 * Why not `reportRawProgress`: it is a Kotlin `inline` function whose body gets compiled into
 * the caller, embedding references to `@ApiStatus.Internal` machinery
 * (`RawProgressReporterHandle` and friends) into this plugin's bytecode — which the Plugin
 * Verifier rightly flags. This helper composes only stable, non-inline platform API:
 * [withBackgroundProgress] (modern task UI; ties the operation to the calling scope, so a
 * project close cancels it), [coroutineToIndicator] (bridges the coroutine's progress and
 * cancellation to a thread-bound indicator), and [runBlockingCancellable] (re-enters suspend
 * code with cancellation linked to that indicator). The hop to [Dispatchers.IO] is
 * load-bearing: the bridged section blocks its thread for the whole operation, which would
 * starve the small Default pool.
 *
 * Cancellation (user cancel or project close) cancels [body] at its next suspension point;
 * operations that must finish their in-flight file and still run cleanup wrap their body in
 * `withContext(NonCancellable)` and poll [ProgressIndicator.isCanceled] instead — see
 * `FileTabOperations.runTransfer` for that contract.
 */
internal suspend fun <T> withIndicatorProgress(
    project: Project,
    title: String,
    body: suspend (ProgressIndicator) -> T,
): T = withBackgroundProgress(project, title, cancellable = true) {
    withContext(Dispatchers.IO) {
        coroutineToIndicator { indicator ->
            runBlockingCancellable {
                body(indicator)
            }
        }
    }
}
