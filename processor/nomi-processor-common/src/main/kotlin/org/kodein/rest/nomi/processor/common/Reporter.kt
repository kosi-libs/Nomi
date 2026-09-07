package org.kodein.rest.nomi.processor.common

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode

/**
 * The seam between analysis and `KSPLogger`: every check in this module reports through a
 * [Reporter] rather than a `KSPLogger` directly, so the checks stay testable without a live KSP
 * round (see [RecordingReporter]).
 */
public abstract class Reporter {
    public var errorCount: Int = 0
        private set

    public fun error(at: KSNode?, message: String) {
        errorCount++
        emit(at, message)
    }

    protected abstract fun emit(at: KSNode?, message: String)
}

public class LoggingReporter(private val logger: KSPLogger) : Reporter() {
    override fun emit(at: KSNode?, message: String) {
        logger.error(message, at)
    }
}

public class RecordingReporter : Reporter() {
    public data class Entry(val message: String, val at: KSNode?)

    private val _entries = mutableListOf<Entry>()
    public val entries: List<Entry> get() = _entries

    override fun emit(at: KSNode?, message: String) {
        _entries += Entry(message, at)
    }
}
