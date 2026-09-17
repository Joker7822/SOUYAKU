package com.epic.souyaku

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Direction-specific translation clients. Opposite directions can download/prepare in parallel,
 * while each individual ML Kit Translator remains serialized for predictable ordering.
 */
class SouyakuTranslationPool : Closeable {
    private data class Slot(
        val engine: SouyakuTranslationEngine = SouyakuTranslationEngine(),
        val mutex: Mutex = Mutex(),
    )

    private val slots = ConcurrentHashMap<String, Slot>()

    suspend fun prepare(source: InterpreterLanguage, target: InterpreterLanguage, wifiOnly: Boolean = false) {
        val slot = slot(source, target)
        slot.mutex.withLock {
            slot.engine.prepare(source, target, wifiOnly)
        }
    }

    suspend fun prepareBidirectional(a: InterpreterLanguage, b: InterpreterLanguage, wifiOnly: Boolean = false) = coroutineScope {
        require(a != b) { "Languages must differ" }
        val forward = async { prepare(a, b, wifiOnly) }
        val reverse = async { prepare(b, a, wifiOnly) }
        forward.await()
        reverse.await()
    }

    suspend fun translate(source: InterpreterLanguage, target: InterpreterLanguage, text: String): String {
        val slot = slot(source, target)
        return slot.mutex.withLock {
            slot.engine.prepare(source, target)
            slot.engine.translate(text)
        }
    }

    override fun close() {
        slots.values.forEach { runCatching { it.engine.close() } }
        slots.clear()
    }

    private fun slot(source: InterpreterLanguage, target: InterpreterLanguage): Slot {
        require(source != target) { "Source and target languages must differ" }
        return slots.getOrPut("${source.speechTag}>${target.speechTag}") { Slot() }
    }
}
