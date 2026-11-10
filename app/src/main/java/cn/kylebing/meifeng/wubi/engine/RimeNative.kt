package cn.kylebing.meifeng.wubi.engine

import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** librime 会话只在这一条线程上跑，和 iOS 的 engineQueue 一样。 */
object RimeQueue {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rime-engine")
    }

    fun onThread(): Boolean = Thread.currentThread().name == "rime-engine"

    fun post(block: () -> Unit) {
        if (onThread()) block() else executor.execute(block)
    }

    fun <T> call(block: () -> T): T {
        if (onThread()) return block()
        return executor.submit(Callable(block)).get()
    }
}

class RimeCandidate(val text: String, val comment: String)

class RimeSnapshot(
    val commitText: String,
    val preedit: String,
    val texts: Array<String>,
    val comments: Array<String>,
    val composing: Boolean,
    val asciiMode: Boolean,
)

object RimeNative {
    init {
        System.loadLibrary("meifeng_rime")
    }

    external fun start(
        sharedDir: String,
        userDir: String,
        logDir: String,
        version: String,
        forceFull: Boolean,
    ): String?

    external fun shutdown()
    external fun isStarted(): Boolean
    external fun processKey(keyCode: Int, modifiers: Int): Boolean
    external fun selectCandidate(index: Int): Boolean
    external fun selectCandidateAbsolute(index: Int): Boolean
    external fun clearComposition()
    external fun rawInput(): String
    external fun context(takeCommit: Boolean): RimeSnapshot?
    external fun allCandidates(): Array<RimeCandidate>?
    external fun lookupTexts(code: String): Array<String>?
    external fun deploy(): Boolean
}
