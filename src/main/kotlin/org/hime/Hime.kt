package org.hime

import org.hime.core.SymbolTable
import org.hime.core.core
import org.hime.core.eval
import org.hime.lang.IOConfig
import org.hime.parse.*

val defaultIO = IOConfig(System.out, System.err, System.`in`)
val defaultSymbolTable = SymbolTable(HashMap(), core, defaultIO)

/**
 * 执行代码
 * @param code   代码
 * @param symbol 符号表
 * @return       结果
 */
fun call(code: String, symbol: SymbolTable = defaultSymbolTable): Token {
    val asts = parser(lexer(preprocessor(code)))
    var result = NIL
    for (ast in asts)
        result = eval(ast, symbol)
    return result
}
