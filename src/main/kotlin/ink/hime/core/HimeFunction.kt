package ink.hime.core

import ink.hime.cast
import ink.hime.lang.himeAssertRuntime
import ink.hime.parse.ASTNode
import ink.hime.parse.Token
import ink.hime.parse.Type
import ink.hime.toToken

typealias Hime_HimeFunction = (List<Token>) -> Token                        // 自举函数
typealias Hime_Function = (List<Token>, SymbolTable) -> Token
typealias Hime_StaticFunction = (ASTNode, SymbolTable) -> Token

class HimeFunction(
    private val funcType: FuncType,
    private val func: Any,
    private val paramTypes: List<Type>,
    private val variadic: Boolean
) {
    // 接受任意类型，任意个数的参数的函数
    constructor(funcType: FuncType, func: Any) : this(funcType, func, listOf(), true)

    // 接受任意类型，指定个数的参数的函数
    constructor(funcType: FuncType, func: Any, size: Int) :
            this(funcType, func, List(size, fun(_) = Type.UNKNOWN), false)

    fun call(ast: ASTNode, symbol: SymbolTable): Token {
        if (funcType == FuncType.STATIC) {
            ast.tok = cast<Hime_StaticFunction>(func)(ast, symbol)
            ast.clear()
            return ast.tok
        }
        for (i in 0 until ast.size())
            ast[i].tok = eval(ast[i].copy(), symbol.createChild())
        val args = ArrayList<Token>()
        for (i in 0 until ast.size())
            args.add(ast[i].tok)
        return call(args, symbol)
    }

    fun call(args: List<Token>, symbol: SymbolTable): Token {
        himeAssertRuntime(funcType != FuncType.STATIC) { "static function definition." }
        himeAssertRuntime(args.size >= paramTypes.size) { "not enough arguments." }
        for (i in paramTypes.indices)
            himeAssertRuntime(
                paramTypes[i] == Type.UNKNOWN ||
                        args[i].type == paramTypes[i] ||
                        (paramTypes[i] == Type.NUM &&
                                (args[i].type == Type.INT || args[i].type == Type.REAL))
            ) { "${paramTypes[i]} expected but ${args[i].type} at position $i" }
        if (!variadic)
            himeAssertRuntime(args.size == paramTypes.size) { "too many arguments." }
        val result = when (this.funcType) {
            FuncType.USER_DEFINED -> cast<Hime_HimeFunction>(func)(args)
            FuncType.BUILT_IN -> cast<Hime_Function>(func)(args, symbol)
            else -> toToken() // 不可能进入该分支
        }
        return result
    }

    fun call(args: List<Token>): Token {
        himeAssertRuntime(funcType == FuncType.USER_DEFINED) { "call non user defined function." }
        return call(args, SymbolTable(HashMap(), null))
    }

    override fun toString(): String {
        return "<Function: ${this.func.hashCode()}>"
    }
}

enum class FuncType {
    BUILT_IN, USER_DEFINED, STATIC;
}