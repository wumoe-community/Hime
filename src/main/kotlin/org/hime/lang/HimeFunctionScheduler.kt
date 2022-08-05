package org.hime.lang

import org.hime.cast
import org.hime.lang.typeMatch.noMatchLevel
import org.hime.parse.ASTNode
import org.hime.parse.Token

class HimeFunctionScheduler(private val env: Env, private val functions: MutableList<HimeFunction> = ArrayList()) {
    fun add(function: HimeFunction): HimeFunctionScheduler {
        functions.add(function)
        return this
    }

    fun call(ast: ASTNode, symbol: SymbolTable = env.symbol): Token {
        for (function in functions) {
            if (function.funcType == FuncType.STATIC) {
                ast.tok = cast<Hime_StaticFunction>(function.func)(ast, symbol)
                ast.clear()
                return ast.tok
            }
        }
        for (i in 0 until ast.size())
            ast[i].tok = eval(env, ast[i].copy(), symbol.createChild())
        val args = ArrayList<Token>()
        for (i in 0 until ast.size())
            args.add(ast[i].tok)
        return call(args, symbol)
    }

    fun call(args: List<Token>, symbol: SymbolTable = env.symbol): Token {
        val its =
            functions.filter { args.size == it.paramTypes.size || (it.variadic && args.size >= it.paramTypes.size) }
        val matchList = MutableList(its.size, fun(idx) = Pair(idx, noMatchLevel)) // Pair为(idx, matchLevel)
        loop@ for (i in matchList.indices) {
            val it = its[matchList[i].first]
            for (j in 0 until it.paramTypes.size) {
                val res = env.typeMatch(args[j], it.paramTypes[j])
                if (!res.matched())
                    continue@loop
                matchList[i] = Pair(matchList[i].first, matchList[i].second + res)
            }
            if (it.variadic)
                for (j in it.paramTypes.size until args.size)
                    if (!env.typeMatch(args[j], it.varType).matched())
                        matchList[i] = Pair(matchList[i].first, noMatchLevel)
        }
        // rhs比较lhs（而不是lhs比较rhs）使得权重大的函数排在列表的首部
        matchList.sortWith(fun(lhs, rhs) = rhs.second.compareTo(lhs.second))
        if (matchList.isEmpty())
            throw HimeRuntimeException("No matching function was found.")
        else if (matchList.size >= 2 && matchList[0].second <= matchList[1].second)
            throw HimeRuntimeException("Ambiguous call.")
        return its[matchList[0].first].call(args, symbol)
    }
}