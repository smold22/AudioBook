package com.yourapp.audiobook.source.extra

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import java.io.IOException

/**
 * Выполняет JS-скрипты из resources (config.js для akniga, decode.js для baza-knig)
 * точно так же, как это делал старый проект через Rhino.
 */
object JsRuntime {

    private val configJs by lazy { readResource("config.js") }
    private val decodeJs by lazy { readResource("decode.js") }

    fun getHash(key: String): String = call(configJs, "getHash", key)

    fun myDecrypt(hres: String): String = call(configJs, "myDecrypt", hres)

    fun strDecode(encoded: String): String = call(decodeJs, "strDecode", encoded)

    private fun call(script: String, functionName: String, arg: String): String {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            val scope: Scriptable = cx.initStandardObjects()
            cx.evaluateString(scope, script, functionName, 0, null)
            val function = scope.get(functionName, scope)
            if (function !is Function) {
                throw IOException("Функция $functionName не найдена в скрипте")
            }
            val result = function.call(cx, scope, scope, arrayOf(arg))
            return Context.toString(result)
        } finally {
            Context.exit()
        }
    }

    private fun readResource(name: String): String {
        val stream = JsRuntime::class.java.classLoader?.getResourceAsStream(name)
            ?: throw IOException("Ресурс $name не найден")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
