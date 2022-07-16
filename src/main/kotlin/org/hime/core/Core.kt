package org.hime.core

import ch.obermuhlner.math.big.BigDecimalMath
import org.hime.call
import org.hime.cast
import org.hime.isNum
import org.hime.parse.*
import org.hime.parse.Type.*
import org.hime.toToken
import java.io.File
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

val core = SymbolTable(
    mutableMapOf(
        "def-symbol" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            assert(ast[0].isNotEmpty())
            val parameters = ArrayList<String>()
            for (i in 0 until ast[0].size())
                parameters.add(ast[0][i].tok.toString())
            val asts = ArrayList<ASTNode>()
            for (i in 1 until ast.size())
                asts.add(ast[i].copy())
            symbol.put(
                ast[0].tok.toString(),
                Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
                    if (ast.type == AstType.FUNCTION) {
                        var result = NIL
                        val newSymbol = symbol.createChild()
                        for (node in ast.child)
                            result = eval(ASTNode(eval(node, newSymbol)), newSymbol)
                        return result
                    }
                    val newAsts = ArrayList<ASTNode>()
                    for (node in asts) {
                        val newAst = node.copy()
                        // 递归替换宏
                        fun rsc(ast: ASTNode, id: String, value: ASTNode) {
                            if (ast.tok.type == ID && ast.tok.toString() == id) {
                                ast.tok = value.tok
                                ast.child = value.child
                            }
                            for (child in ast.child)
                                rsc(child, id, value)
                        }
                        assert(ast.size() >= parameters.size)
                        for (i in parameters.indices)
                            rsc(newAst, parameters[i], ast[i])
                        newAsts.add(newAst)
                    }
                    val newSymbol = symbol.createChild()
                    var result = NIL
                    for (astNode in newAsts)
                        result = eval(astNode.copy(), newSymbol)
                    return result
                })
            )
            return NIL
        }),
        "cons-stream" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            // 对(cons-stream t1 t2)中的t1进行求值
            val t1 = eval(ast[0], symbol.createChild())
            val asts = ArrayList<ASTNode>()
            // 对t1后面的内容都作为begin添加到asts中
            for (i in 1 until ast.size())
                asts.add(ast[i].copy())
            // 建立过程，类似(delay t2*)
            return arrayListOf(t1, structureHimeFunction(arrayListOf(), asts, symbol.createChild())).toToken()
        }),
        "stream-car" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            // 因为(cons-stream t1 t2)的t1已经被求值，所以就直接返回
            return cast<List<Token>>(args[0].value)[0]
        }),
        "stream-cdr" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val tokens = cast<List<Token>>(args[0].value)
            val list = ArrayList<Token>()
            // 将stream-cdr传入的列表，除去第1个外的所有元素都应用force，即便(cons-stream t1 t2)只返回包含2个元素的列表
            for (i in 1 until tokens.size) {
                assert(tokens[i].type == HIME_FUNCTION)
                list.add(cast<Hime_HimeFunction>(tokens[i].value)(arrayListOf()))
            }
            // 如果列表中只存在一个元素，那么就返回这个元素
            if (list.size == 1)
                return list[0].toToken()
            // 否则返回整个列表
            return list.toToken()
        }),
        "stream-map" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            if (args[1].type == Type.EMPTY_STREAM)
                return EMPTY_STREAM
            assert(args[1].type == LIST)
            // 每个list只包含2个元素，一个已经求值，一个待求值，这里包含(stream-map function list*)的所有list
            var lists = ArrayList<List<Token>>()
            // 将所有list添加到lists中
            for (i in 1 until args.size)
                lists.add(cast<List<Token>>(args[i].value))
            val result = ArrayList<Token>()
            // 直到遇见EMPTY_STREAM才结束
            top@ while (true) {
                // 例如对于(stream-map f (stream-cons a b) (stream-cons c d))，则执行(f a c)等
                val parameters = ArrayList<Token>()
                // 将所有首项添加到parameters
                for (list in lists)
                    parameters.add(list[0])
                // 将parameters按匹配的类型添加到函数中并执行
                result.add(
                    when (args[0].type) {
                        FUNCTION -> cast<Hime_Function>(args[0].value)(parameters, symbol.createChild())
                        HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(parameters)
                        STATIC_FUNCTION -> {
                            val asts = ASTNode.EMPTY.copy()
                            for (arg in parameters)
                                asts.add(ASTNode(arg))
                            cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                        }
                        else -> NIL
                    }
                )
                val temp = ArrayList<List<Token>>()
                // 重新计算lists，并应用delay
                for (list in lists) {
                    assert(list[1].type == HIME_FUNCTION)
                    val t = cast<Hime_HimeFunction>(list[1].value)(arrayListOf())
                    if (t.type == Type.EMPTY_STREAM)
                        break@top
                    temp.add(cast<List<Token>>(t.value))
                }
                lists = temp
            }
            return result.toToken()
        }),
        "stream-for-each" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            if (args[1].type == Type.EMPTY_STREAM)
                return EMPTY_STREAM
            assert(args[1].type == LIST)
            // 每个list只包含2个元素，一个已经求值，一个待求值，这里包含(stream-map function list*)的所有list
            var lists = ArrayList<List<Token>>()
            // 将所有list添加到lists中
            for (i in 1 until args.size)
                lists.add(cast<List<Token>>(args[i].value))
            // 直到遇见EMPTY_STREAM才结束
            top@ while (true) {
                // 例如对于(stream-map f (stream-cons a b) (stream-cons c d))，则执行(f a c)等
                val parameters = ArrayList<Token>()
                // 将所有首项添加到parameters
                for (list in lists)
                    parameters.add(list[0])
                // 将parameters按匹配的类型添加到函数中并执行
                when (args[0].type) {
                    FUNCTION -> cast<Hime_Function>(args[0].value)(parameters, symbol.createChild())
                    HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(parameters)
                    STATIC_FUNCTION -> {
                        val asts = ASTNode.EMPTY.copy()
                        for (arg in parameters)
                            asts.add(ASTNode(arg))
                        cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                    }
                    else -> {}
                }
                val temp = ArrayList<List<Token>>()
                // 重新计算lists，并应用delay
                for (list in lists) {
                    assert(list[1].type == HIME_FUNCTION)
                    val t = cast<Hime_HimeFunction>(list[1].value)(arrayListOf())
                    if (t.type == Type.EMPTY_STREAM)
                        break@top
                    temp.add(cast<List<Token>>(t.value))
                }
                lists = temp
            }
            return NIL
        }),
        "stream-filter" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            val newSymbol = symbol.createChild()
            // 将匹配的参数添加到newSymbol中
            newSymbol.put("pred", args[0])
            newSymbol.put("stream", args[1])
            // 解释执行
            return eval(
                parser(
                    lexer(
                        "(cond ((= stream empty-stream) empty-stream) " +
                                "((pred (stream-car stream)) " +
                                "(cons-stream (stream-car stream) " +
                                "(stream-filter pred (stream-cdr stream)))) " +
                                "(else (stream-filter pred (stream-cdr stream))))"
                    )
                )[0], newSymbol
            )
        }),
        "stream-ref" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            val newSymbol = symbol.createChild()
            // 将匹配的参数添加到newSymbol中
            newSymbol.put("s", args[0])
            newSymbol.put("n", args[1])
            // 解释执行
            return eval(
                parser(
                    lexer(
                        "(if (= s empty-stream) " +
                                "empty-stream " +
                                "(if (= n 0) " +
                                "(stream-car s) " +
                                "(stream-ref (stream-cdr s) (- n 1))))"
                    )
                )[0], newSymbol
            )
        }),
        // (delay e) => (lambda () e)
        "delay" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.isNotEmpty())
            val asts = ArrayList<ASTNode>()
            for (i in 0 until ast.size())
                asts.add(ast[i].copy())
            return structureHimeFunction(arrayListOf(), asts, symbol.createChild())
        }),
        // (force d) => (d)
        "force" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var result = NIL
            for (token in args) {
                assert(token.type == HIME_FUNCTION)
                result = cast<Hime_HimeFunction>(token.value)(arrayListOf())
            }
            return result
        }),
        // 局部变量绑定
        "let" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.isNotEmpty())
            // 新建执行的新环境（继承）
            val newSymbol = symbol.createChild()
            var result = NIL
            for (node in ast[0].child) {
                if (node.tok.toString() == "apply") {
                    val parameters = ArrayList<String>()
                    for (i in 0 until node[0].size()) {
                        assert(node[0][i].tok.type == ID)
                        parameters.add(node[0][i].tok.toString())
                    }
                    val asts = ArrayList<ASTNode>()
                    for (i in 1 until node.size())
                        asts.add(node[i].copy())
                    // 这里采用原环境的继承，因为let不可互相访问
                    newSymbol.put(node[0].tok.toString(), structureHimeFunction(parameters, asts, symbol.createChild()))
                } else {
                    var value = NIL
                    for (e in node.child)
                        value = eval(e.copy(), symbol.createChild())
                    newSymbol.put(node.tok.toString(), value)
                }
            }
            for (i in 1 until ast.size())
                result = eval(ast[i].copy(), newSymbol.createChild())
            return result
        }),
        "let*" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.isNotEmpty())
            // 新建执行的新环境（继承）
            val newSymbol = symbol.createChild()
            var result = NIL
            for (node in ast[0].child) {
                if (node.tok.toString() == "apply") {
                    val parameters = ArrayList<String>()
                    for (i in 0 until node[0].size())
                        parameters.add(node[0][i].tok.toString())
                    val asts = ArrayList<ASTNode>()
                    for (i in 1 until node.size())
                        asts.add(node[i].copy())
                    // 这里采用新环境的继承，因为let*可互相访问
                    newSymbol.put(
                        node[0].tok.toString(),
                        structureHimeFunction(parameters, asts, newSymbol.createChild())
                    )
                } else {
                    var value = NIL
                    for (e in node.child)
                        value = eval(e.copy(), newSymbol.createChild())
                    newSymbol.put(node.tok.toString(), value)
                }
            }
            for (i in 1 until ast.size())
                result = eval(ast[i].copy(), newSymbol.createChild())
            return result
        }),
        // 建立新绑定
        "def" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            // 如果是(def key value)
            if (ast[0].isEmpty() && ast[0].type != AstType.FUNCTION) {
                var result = NIL
                for (i in 1 until ast.size())
                    result = eval(ast[i], symbol.createChild())
                symbol.put(ast[0].tok.toString(), result)
            }
            // 如果是(def (function-name p*) e)
            else {
                val parameters = ArrayList<String>()
                for (i in 0 until ast[0].size())
                    parameters.add(ast[0][i].tok.toString())
                val asts = ArrayList<ASTNode>()
                // 将ast都复制一遍并存到asts中
                for (i in 1 until ast.size())
                    asts.add(ast[i].copy())
                symbol.put(
                    ast[0].tok.toString(),
                    structureHimeFunction(parameters, asts, symbol.createChild())
                )
            }
            return NIL
        }),
        // 建立新绑定(变长)
        "def-variable" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            assert(ast[0].isNotEmpty() || ast[0].type != AstType.FUNCTION)
            val parameters = ArrayList<String>()
            for (i in 0 until ast[0].size())
                parameters.add(ast[0][i].tok.toString())
            val asts = ArrayList<ASTNode>()
            // 将ast都复制一遍并存到asts中
            for (i in 1 until ast.size())
                asts.add(ast[i].copy())
            symbol.put(
                ast[0].tok.toString(),
                variableHimeFunction(parameters, asts, symbol.createChild())
            )
            return NIL
        }),
        // 解除绑定
        "undef" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.isNotEmpty())
            assert(symbol.contains(ast[0].tok.toString()))
            // 从环境中删除绑定
            symbol.remove(ast[0].tok.toString())
            return NIL
        }),
        // 更改绑定
        "set" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            assert(symbol.contains(ast[0].tok.toString()))
            // 如果是(set key value)
            if (ast[0].isEmpty() && ast[0].type != AstType.FUNCTION) {
                var result = NIL
                for (i in 1 until ast.size())
                    result = eval(ast[i], symbol.createChild())
                symbol.set(ast[0].tok.toString(), result)
            } else {
                // 如果是(set (function-name p*) e)
                val args = ArrayList<String>()
                for (i in 0 until ast[0].size())
                    args.add(ast[0][i].tok.toString())
                val asts = ArrayList<ASTNode>()
                // 将ast都复制一遍并存到asts中
                for (i in 1 until ast.size())
                    asts.add(ast[i].copy())
                symbol.set(ast[0].tok.toString(), structureHimeFunction(args, asts, symbol))
            }
            return NIL
        }),
        "set-variable" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            assert(symbol.contains(ast[0].tok.toString()))
            assert(ast[0].isNotEmpty() || ast[0].type != AstType.FUNCTION)
            val parameters = ArrayList<String>()
            for (i in 0 until ast[0].size())
                parameters.add(ast[0][i].tok.toString())
            val asts = ArrayList<ASTNode>()
            // 将ast都复制一遍并存到asts中
            for (i in 1 until ast.size())
                asts.add(ast[i].copy())
            symbol.set(
                ast[0].tok.toString(),
                variableHimeFunction(parameters, asts, symbol.createChild())
            )
            return NIL
        }),
        "lambda" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            val parameters = ArrayList<String>()
            // 判断非(lambda () e)
            if (ast[0].tok.type != EMPTY) {
                parameters.add(ast[0].tok.toString())
                for (i in 0 until ast[0].size())
                    parameters.add(ast[0][i].tok.toString())
            }
            val asts = ArrayList<ASTNode>()
            // 将ast都复制一遍并存到asts中
            for (i in 1 until ast.size())
                asts.add(ast[i].copy())
            return structureHimeFunction(parameters, asts, symbol.createChild())
        }),
        "if" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            // 新建执行的新环境（继承）
            val newSymbol = symbol.createChild()
            // 执行condition
            val condition = eval(ast[0], newSymbol)
            assert(condition.type == BOOL)
            // 分支判断
            if (cast<Boolean>(condition.value))
                return eval(ast[1].copy(), newSymbol)
            else if (ast.size() > 2)
                return eval(ast[2].copy(), newSymbol)
            return NIL
        }),
        "cond" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            // 新建执行的新环境（继承）
            val newSymbol = symbol.createChild()
            for (node in ast.child) {
                // 如果碰到else，就直接执行返回
                if (node.tok.type == ID && node.tok.toString() == "else")
                    return eval(node[0].copy(), newSymbol)
                else {
                    val result = eval(node[0].copy(), newSymbol)
                    assert(result.type == BOOL)
                    if (cast<Boolean>(result.value)) {
                        var r = NIL
                        for (index in 1 until node.size())
                            r = eval(node[index].copy(), newSymbol)
                        return r
                    }
                }
            }
            return NIL
        }),
        "switch" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            assert(ast.size() > 1)
            val newSymbol = symbol.createChild()
            val op = eval(ast[0].copy(), newSymbol)
            for (index in 1 until ast.size()) {
                val node = ast[index]
                if (node.tok.type == ID && node.tok.toString() == "else")
                    return eval(node.copy(), newSymbol)
                else
                    if (node.tok == op) {
                        var r = NIL
                        for (i in 0 until node.size())
                            r = eval(node[i].copy(), newSymbol)
                        return r
                    }
            }
            return NIL
        }),
        // 执行多个组合式
        "begin" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            // 新建执行的新环境（继承）
            val newSymbolTable = symbol.createChild()
            var result = NIL
            for (i in 0 until ast.size())
                result = eval(ast[i].copy(), newSymbolTable)
            return result
        }),
        "while" to Token(STATIC_FUNCTION, fun(ast: ASTNode, symbol: SymbolTable): Token {
            // 新建执行的新环境（继承）
            val newSymbolTable = symbol.createChild()
            var result = NIL
            // 执行condition
            var condition = eval(ast[0].copy(), newSymbolTable)
            assert(condition.type == BOOL)
            while (cast<Boolean>(condition.value)) {
                for (i in 1 until ast.size())
                    result = eval(ast[i].copy(), newSymbolTable)
                // 重新执行condition
                condition = eval(ast[0].copy(), newSymbolTable)
                assert(condition.type == BOOL)
            }
            return result
        }),
        "apply" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.isNotEmpty())
            val parameters = ArrayList<Token>()
            for (i in 1 until args.size)
                parameters.add(args[i])
            return when (args[0].type) {
                FUNCTION -> cast<Hime_Function>(args[0].value)(parameters, symbol.createChild())
                HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(parameters)
                STATIC_FUNCTION -> {
                    val asts = ASTNode.EMPTY.copy()
                    for (arg in parameters)
                        asts.add(ASTNode(arg))
                    cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                }
                else -> NIL
            }
        }),
        "apply-list" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[1].type == LIST)
            val parameters = cast<List<Token>>(args[1].value)
            return when (args[0].type) {
                FUNCTION -> cast<Hime_Function>(args[0].value)(parameters, symbol.createChild())
                HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(parameters)
                STATIC_FUNCTION -> {
                    val asts = ASTNode.EMPTY.copy()
                    for (arg in parameters)
                        asts.add(ASTNode(arg))
                    cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                }
                else -> NIL
            }
        }),
        "require" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.isNotEmpty())
            val path = args[0].toString()
            // 导入内置的模块
            if (module.containsKey(path)) {
                for ((key, value) in module[path]!!.table)
                    symbol.put(key, value)
                return NIL
            }
            // 导入工作目录的模块
            val file = File(System.getProperty("user.dir") + "/" + path.replace(".", "/") + ".hime")
            if (file.exists())
                for (node in parser(lexer(preprocessor(Files.readString(file.toPath())))))
                    eval(node, symbol)
            return NIL
        }),
        "read-bit" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            return System.`in`.read().toToken()
        }),
        "read-line" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            return Scanner(System.`in`).nextLine().toToken()
        }),
        "read" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            return Scanner(System.`in`).next().toToken()
        }),
        "read-num" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            return Scanner(System.`in`).nextBigInteger().toToken()
        }),
        "read-real" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            return Scanner(System.`in`).nextBigDecimal().toToken()
        }),
        "read-bool" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            return Scanner(System.`in`).nextBoolean().toToken()
        }),
        "println" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            val builder = StringBuilder()
            for (token in args)
                builder.append(token.toString())
            println(builder.toString())
            return NIL
        }),
        "print" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            val builder = StringBuilder()
            for (token in args)
                builder.append(token.toString())
            print(builder.toString())
            return NIL
        }),
        "newline" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            println()
            return NIL
        }),
        "+" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var num = BigDecimal.ZERO
            for (parameter in args) {
                assert(parameter.isNum())
                num = num.add(BigDecimal(parameter.toString()))
            }
            return num.toToken()
        }),
        "-" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            var num = BigDecimal(args[0].toString())
            if (args.size == 1)
                return BigDecimal.ZERO.subtract(num).toToken()
            for (i in 1 until args.size) {
                assert(args[i].isNum())
                num = num.subtract(BigDecimal(args[i].toString()))
            }
            return num.toToken()
        }),
        "*" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var num = BigDecimal.ONE
            for (parameter in args) {
                assert(parameter.isNum())
                num = num.multiply(BigDecimal(parameter.toString()))
            }
            return num.toToken()
        }),
        "/" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            var num = BigDecimal(args[0].toString())
            for (i in 1 until args.size) {
                assert(args[i].isNum())
                num = num.divide(BigDecimal(args[i].toString()), MathContext.DECIMAL64)
            }
            return num.toToken()
        }),
        "and" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args) {
                assert(arg.type == BOOL)
                if (!cast<Boolean>(arg.value))
                    return FALSE
            }
            return TRUE
        }),
        "or" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args) {
                assert(arg.type == BOOL)
                if (cast<Boolean>(arg.value))
                    return TRUE
            }
            return FALSE
        }),
        "not" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == BOOL)
            return if (cast<Boolean>(args[0].value)) FALSE else TRUE
        }),
        "=" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var token = args[0]
            for (t in args) {
                if (t != token)
                    return FALSE
                token = t
            }
            return TRUE
        }),
        "/=" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (i in args.indices)
                for (j in args.indices)
                    if (i != j && args[i] == args[j])
                        return FALSE
            return TRUE
        }),
        ">" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var token = BigDecimal(args[0].toString())
            for (index in 1 until args.size) {
                val n = BigDecimal(args[index].toString())
                if (token <= n)
                    return FALSE
                token = n
            }
            return TRUE
        }),
        "<" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var token = BigDecimal(args[0].toString())
            for (index in 1 until args.size) {
                val n = BigDecimal(args[index].toString())
                if (token >= n)
                    return FALSE
                token = n
            }
            return TRUE
        }),
        ">=" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var token = BigDecimal(args[0].toString())
            for (index in 1 until args.size) {
                val n = BigDecimal(args[index].toString())
                if (token < n)
                    return FALSE
                token = n
            }
            return TRUE
        }),
        "<=" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var token = BigDecimal(args[0].toString())
            for (index in 1 until args.size) {
                val n = BigDecimal(args[index].toString())
                if (token > n)
                    return FALSE
                token = n
            }
            return TRUE
        }),
        "random" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == NUM)
            if (args.size > 1)
                assert(args[1].type == NUM)
            val start = if (args.size == 1) BigInteger.ZERO else BigInteger(args[0].toString())
            val end =
                if (args.size == 1) BigInteger(args[0].toString()) else BigInteger(args[1].toString())
            val rand = Random()
            val scale = end.toString().length
            var generated = ""
            for (i in 0 until end.toString().length)
                generated += rand.nextInt(10)
            val inputRangeStart = BigDecimal("0").setScale(scale, RoundingMode.FLOOR)
            val inputRangeEnd =
                BigDecimal(String.format("%0" + end.toString().length + "d", 0).replace('0', '9')).setScale(
                    scale,
                    RoundingMode.FLOOR
                )
            val outputRangeStart = BigDecimal(start).setScale(scale, RoundingMode.FLOOR)
            val outputRangeEnd = BigDecimal(end).add(BigDecimal("1"))
                .setScale(scale, RoundingMode.FLOOR)
            val bd1 = BigDecimal(BigInteger(generated)).setScale(scale, RoundingMode.FLOOR).subtract(inputRangeStart)
            val bd2 = inputRangeEnd.subtract(inputRangeStart)
            val bd3 = bd1.divide(bd2, RoundingMode.FLOOR)
            val bd4 = outputRangeEnd.subtract(outputRangeStart)
            val bd5 = bd3.multiply(bd4)
            val bd6 = bd5.add(outputRangeStart)
            var returnInteger = bd6.setScale(0, RoundingMode.FLOOR).toBigInteger()
            returnInteger =
                if (returnInteger > end) end else returnInteger
            return returnInteger.toToken()
        }),
        "cons" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size == 2)
            return ArrayList(args).toToken()
        }),
        "car" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            return cast<List<Token>>(args[0].value)[0]
        }),
        "cdr" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val tokens = cast<List<Token>>(args[0].value)
            val list = ArrayList<Token>()
            // 例如(cdr (list a b c d))将返回(list b c d)
            for (i in 1 until tokens.size)
                list.add(tokens[i])
            if (list.size == 1)
                return list[0].toToken()
            return list.toToken()
        }),
        "list" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            return args.toToken()
        }),
        "head" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val list = cast<List<Token>>(args[0].value)
            if (list.isEmpty())
                return NIL
            return list[0]
        }),
        "last" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val tokens = cast<List<Token>>(args[0].value)
            return tokens[tokens.size - 1]
        }),
        "tail" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val tokens = cast<List<Token>>(args[0].value)
            val list = ArrayList<Token>()
            for (i in 1 until tokens.size)
                list.add(tokens[i])
            if (list.size == 1)
                return arrayListOf(list[0]).toToken()
            return list.toToken()
        }),
        "init" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val tokens = cast<List<Token>>(args[0].value)
            val list = ArrayList<Token>()
            for (i in 0 until tokens.size - 1)
                list.add(tokens[i])
            if (list.size == 1)
                return arrayListOf(list[0]).toToken()
            return list.toToken()
        }),
        "list-contains" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == LIST)
            return cast<List<Token>>(args[0].value).contains(args[1]).toToken()
        }),
        "list-remove" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == LIST)
            assert(args[1].type == NUM)
            val tokens = ArrayList(cast<List<Token>>(args[0].value))
            tokens.removeAt(cast<Int>(args[1].value))
            return tokens.toToken()
        }),
        "list-set" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[0].type == LIST)
            assert(args[1].type == NUM)
            val index = cast<Int>(args[1].value)
            val tokens = ArrayList(cast<List<Token>>(args[0].value))
            assert(index < tokens.size)
            tokens[index] = args[2]
            return tokens.toToken()
        }),
        "list-add" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == LIST)
            val tokens = ArrayList(cast<List<Token>>(args[0].value))
            if (args.size > 2) {
                assert(args[1].type == NUM)
                tokens.add(cast<Int>(args[1].value), args[2])
            } else
                tokens.add(args[1])
            return tokens.toToken()
        }),
        "list-remove!" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == LIST)
            assert(args[1].type == NUM)
            cast<MutableList<Token>>(args[0].value).removeAt(cast<Int>(args[1].value))
            return args[0].toToken()
        }),
        "list-set!" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[0].type == LIST)
            assert(args[1].type == NUM)
            cast<MutableList<Token>>(args[0].value)[cast<Int>(args[1].value)] = args[2]
            return args[0].toToken()
        }),
        "list-add!" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == LIST)
            val tokens = cast<MutableList<Token>>(args[0].value)
            if (args.size > 2) {
                assert(args[1].type == NUM)
                tokens.add(cast<Int>(args[1].value), args[2])
            } else
                tokens.add(args[1])
            return args[0].toToken()
        }),
        "list-ref" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == LIST)
            assert(args[1].type == NUM)
            val index = cast<Int>(args[1].value)
            val tokens = cast<List<Token>>(args[0].value)
            assert(index < tokens.size)
            return tokens[index]
        }),
        "++" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var flag = false
            // 判断是否是List，还是string
            for (arg in args)
                if (arg.type == LIST) {
                    flag = true
                    break
                }
            return if (flag) {
                val list = ArrayList<Token>()
                for (arg in args) {
                    if (arg.type == LIST)
                        list.addAll(cast<List<Token>>(arg.value))
                    else
                        list.add(arg)
                }
                list.toToken()
            } else {
                val builder = StringBuilder()
                for (arg in args)
                    builder.append(arg.toString())
                builder.toString().toToken()
            }
        }),
        "range" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            val start = if (args.size >= 2) BigInteger(args[0].toString()) else BigInteger.ZERO
            val end =
                if (args.size >= 2) BigInteger(args[1].toString()) else BigInteger(args[0].toString())
            // 每次增加的step
            val step = if (args.size >= 3) BigInteger(args[2].toString()) else BigInteger.ONE
            val size = end.subtract(start).divide(step)
            val list = ArrayList<Token>()
            var i = BigInteger.ZERO
            // index和size是否相等
            while (i.compareTo(size) != 1) {
                list.add(start.add(i.multiply(step)).toToken())
                i = i.add(BigInteger.ONE)
            }
            return Token(LIST, list)
        }),
        // 获取长度，可以是字符串也可以是列表
        "length" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return when (args[0].type) {
                STR -> cast<String>(args[0].value).length.toToken()
                LIST -> cast<List<Token>>(args[0].value).size.toToken()
                else -> args[0].toString().length.toToken()
            }
        }),
        // 反转列表
        "reverse" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val result = ArrayList<Token>()
            val tokens = cast<MutableList<Token>>(args[0].value)
            for (i in tokens.size - 1 downTo 0)
                result.add(tokens[i])
            tokens.clear()
            for (t in result)
                tokens.add(t)
            return tokens.toToken()
        }),
        // 排序
        "sort" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            // 归并排序
            fun merge(a: Array<BigDecimal?>, low: Int, mid: Int, high: Int) {
                val temp = arrayOfNulls<BigDecimal>(high - low + 1)
                var i = low
                var j = mid + 1
                var k = 0
                while (i <= mid && j <= high)
                    temp[k++] = if (a[i]!! < a[j]) a[i++] else a[j++]
                while (i <= mid)
                    temp[k++] = a[i++]
                while (j <= high)
                    temp[k++] = a[j++]
                for (k2 in temp.indices)
                    a[k2 + low] = temp[k2]!!
            }

            fun mergeSort(a: Array<BigDecimal?>, low: Int, high: Int) {
                val mid = (low + high) / 2
                if (low < high) {
                    mergeSort(a, low, mid)
                    mergeSort(a, mid + 1, high)
                    merge(a, low, mid, high)
                }
            }

            val tokens = cast<List<Token>>(args[0].value)
            val result = ArrayList<Token>()
            val list = arrayOfNulls<BigDecimal>(tokens.size)
            for (i in tokens.indices)
                list[i] = BigDecimal(tokens[i].toString())
            mergeSort(list, 0, list.size - 1)
            for (e in list)
                result.add(e!!.toToken())
            return result.toToken()
        }),
        "curry" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            assert(args[1].type == NUM)
            fun rsc(func: Token, n: Int, parameters: ArrayList<Token>): Token {
                if (n == 0)
                    return when (func.type) {
                        FUNCTION -> cast<Hime_Function>(func.value)(parameters, symbol.createChild())
                        HIME_FUNCTION -> cast<Hime_HimeFunction>(func.value)(parameters)
                        STATIC_FUNCTION -> {
                            val asts = ASTNode.EMPTY.copy()
                            for (arg in parameters)
                                asts.add(ASTNode(arg))
                            cast<Hime_StaticFunction>(func.value)(asts, symbol.createChild())
                        }
                        else -> NIL
                    }
                return Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
                    assert(args.isNotEmpty())
                    parameters.add(args[0])
                    return rsc(func, n - 1, parameters)
                })
            }
            return rsc(args[0], cast<Int>(args[1].value), ArrayList<Token>())
        }),
        "maybe" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            val parameters = ArrayList<Token>()
            for (i in 1 until args.size) {
                if (args[i].type == Type.NIL)
                    return NIL
                parameters.add(args[i])
            }
            return when (args[0].type) {
                FUNCTION -> cast<Hime_Function>(args[0].value)(parameters, symbol.createChild())
                HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(parameters)
                STATIC_FUNCTION -> {
                    val asts = ASTNode.EMPTY.copy()
                    for (arg in parameters)
                        asts.add(ASTNode(arg))
                    cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                }
                else -> NIL
            }
        }),
        "map" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            assert(args[1].type == LIST)
            val result = ArrayList<Token>()
            val tokens = cast<List<Token>>(args[1].value)
            for (i in tokens.indices) {
                val parameters = ArrayList<Token>()
                parameters.add(tokens[i])
                // 例如对于(map f (list a b) (list c d))，则执行(f a c)等
                for (j in 1 until args.size - 1)
                    parameters.add(cast<List<Token>>(args[j + 1].value)[i])
                result.add(
                    when (args[0].type) {
                        FUNCTION -> cast<Hime_Function>(args[0].value)(parameters, symbol.createChild())
                        HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(parameters)
                        STATIC_FUNCTION -> {
                            val asts = ASTNode.EMPTY.copy()
                            for (arg in parameters)
                                asts.add(ASTNode(arg))
                            cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                        }
                        else -> NIL
                    }
                )
            }
            return result.toToken()
        }),
        "foldr" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            assert(args[2].type == LIST)
            var result = args[1]
            val tokens = cast<List<Token>>(args[2].value)
            for (i in tokens.size - 1 downTo 0)
                result = when (args[0].type) {
                    FUNCTION -> cast<Hime_Function>(args[0].value)(arrayListOf(tokens[i], result), symbol.createChild())
                    HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(arrayListOf(tokens[i], result))
                    STATIC_FUNCTION -> {
                        val asts = ASTNode.EMPTY.copy()
                        for (arg in arrayListOf(tokens[i], result))
                            asts.add(ASTNode(arg))
                        cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                    }
                    else -> arrayListOf(tokens[i], result).toToken()
                }
            return result
        }),
        "foldl" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            assert(args[2].type == LIST)
            var result = args[1]
            val tokens = cast<List<Token>>(args[2].value)
            for (i in tokens.size - 1 downTo 0)
                result = when (args[0].type) {
                    FUNCTION -> cast<Hime_Function>(args[0].value)(arrayListOf(result, tokens[i]), symbol.createChild())
                    HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(arrayListOf(result, tokens[i]))
                    STATIC_FUNCTION -> {
                        val asts = ASTNode.EMPTY.copy()
                        for (arg in arrayListOf(result, tokens[i]))
                            asts.add(ASTNode(arg))
                        cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                    }
                    else -> arrayListOf(result, tokens[i]).toToken()
                }
            return result
        }),
        "for-each" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            assert(args[1].type == LIST)
            val tokens = cast<List<Token>>(args[1].value)
            for (i in tokens.indices) {
                val parameters = ArrayList<Token>()
                parameters.add(tokens[i])
                // 例如对于(map f (list a b) (list c d))，则执行(f a c)等
                for (j in 1 until args.size - 1)
                    parameters.add(cast<List<Token>>(args[j + 1].value)[i])
                when (args[0].type) {
                    FUNCTION -> cast<Hime_Function>(args[0].value)(parameters, symbol.createChild())
                    HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(parameters)
                    STATIC_FUNCTION -> {
                        val asts = ASTNode.EMPTY.copy()
                        for (arg in parameters)
                            asts.add(ASTNode(arg))
                        cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                    }
                    else -> {}
                }
            }
            return NIL
        }),
        "filter" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == FUNCTION || args[0].type == HIME_FUNCTION || args[0].type == STATIC_FUNCTION)
            assert(args[1].type == LIST)
            val result = ArrayList<Token>()
            val tokens = cast<List<Token>>(args[1].value)
            for (token in tokens) {
                val op = when (args[0].type) {
                    FUNCTION -> cast<Hime_Function>(args[0].value)(arrayListOf(token), symbol.createChild())
                    HIME_FUNCTION -> cast<Hime_HimeFunction>(args[0].value)(arrayListOf(token))
                    STATIC_FUNCTION -> {
                        val asts = ASTNode.EMPTY.copy()
                        asts.add(ASTNode(token))
                        cast<Hime_StaticFunction>(args[0].value)(asts, symbol.createChild())
                    }
                    else -> token
                }
                assert(op.type == BOOL)
                if (cast<Boolean>(op.value))
                    result.add(token)
            }
            return result.toToken()
        }),
        "sqrt" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.sqrt(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "sin" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.sin(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "sinh" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.sinh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "asin" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.asin(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "asinh" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.asinh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "cos" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.cos(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "cosh" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.cosh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "acos" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.acos(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "acosh" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.acosh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "tan" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.tan(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "tanh" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.tanh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "atan" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.atan(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "atanh" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.atanh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "atan2" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].isNum())
            assert(args[1].isNum())
            return BigDecimalMath.atan2(
                BigDecimal(args[0].toString()),
                BigDecimal(args[1].toString()),
                MathContext.DECIMAL64
            ).toToken()
        }),
        "log" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.log(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "log10" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.log10(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "log2" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.log2(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "exp" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].isNum())
            return BigDecimalMath.exp(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken()
        }),
        "pow" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].isNum())
            assert(args[1].isNum())
            return BigDecimalMath.pow(
                BigDecimal(args[0].toString()),
                BigDecimal(args[1].toString()),
                MathContext.DECIMAL64
            ).toToken()
        }),
        "mod" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].isNum())
            assert(args[1].isNum())
            return BigInteger(args[0].toString()).mod(BigInteger(args[1].toString())).toToken()
        }),
        "max" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var max = BigDecimal(args[0].toString())
            for (i in 1 until args.size)
                max = max.max(BigDecimal(args[i].toString()))
            return max.toToken()
        }),
        "min" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var min = BigDecimal(args[0].toString())
            for (i in 1 until args.size)
                min = min.min(BigDecimal(args[i].toString()))
            return min.toToken()
        }),
        "abs" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return BigDecimal(args[0].toString()).abs().toToken()
        }),
        "average" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            var num = BigDecimal.ZERO
            for (parameter in args)
                num = num.add(BigDecimal(parameter.value.toString()))
            return num.divide(args.size.toBigDecimal()).toToken()
        }),
        "floor" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return BigInteger(
                BigDecimal(args[0].toString()).setScale(0, RoundingMode.FLOOR).toPlainString()
            ).toToken()
        }),
        "ceil" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return BigInteger(
                BigDecimal(args[0].toString()).setScale(0, RoundingMode.CEILING).toPlainString()
            ).toToken()
        }),
        "gcd" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            for (parameter in args)
                assert(parameter.type == NUM || parameter.type == BIG_NUM)
            var temp = BigInteger(args[0].toString()).gcd(BigInteger(args[1].toString()))
            for (i in 2 until args.size)
                temp = temp.gcd(BigInteger(args[i].toString()))
            return temp.toToken()
        }),
        // (lcm a b) = (/ (* a b) (gcd a b))
        "lcm" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            fun BigInteger.lcm(n: BigInteger): BigInteger = (this.multiply(n).abs()).divide(this.gcd(n))
            assert(args.size > 1)
            for (parameter in args)
                assert(parameter.type == NUM || parameter.type == BIG_NUM)
            var temp = BigInteger(args[0].toString()).lcm(BigInteger(args[1].toString()))
            for (i in 2 until args.size)
                temp = temp.lcm(BigInteger(args[i].toString()))
            return temp.toToken()
        }),
        "->bool" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return if (args[0].toString() == "true") TRUE else FALSE
        }),
        "->string" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return args[0].toString().toToken()
        }),
        "->num" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return BigInteger(args[0].toString()).toToken()
        }),
        "->real" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return BigDecimal(args[0].toString()).toToken()
        }),
        "string-replace" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 2)
            return args[0].toString().replace(args[1].toString(), args[2].toString()).toToken()
        }),
        "string-substring" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[1].type == NUM)
            assert(args[2].type == NUM)
            return args[0].toString()
                .substring(cast<Int>(args[1].value), cast<Int>(args[2].value))
                .toToken()
        }),
        "string-split" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            return args[0].toString().split(args[1].toString()).toList().toToken()
        }),
        "string-index" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            return args[0].toString().indexOf(args[1].toString()).toToken()
        }),
        "string-last-index" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            return args[0].toString().lastIndexOf(args[1].toString()).toToken()
        }),
        "string-format" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            val newArgs = arrayOfNulls<Any>(args.size - 1)
            for (i in 1 until args.size)
                newArgs[i - 1] = args[i].value
            return String.format(args[0].toString(), *newArgs).toToken()
        }),
        "string->list" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            val chars = args[0].toString().toCharArray()
            val list = ArrayList<Token>()
            for (c in chars) {
                assert(args.isNotEmpty())
                list.add(c.toString().toToken())
            }
            return list.toToken()
        }),
        "list->string" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val builder = StringBuilder()
            val list = cast<List<Token>>(args[0].value)
            for (token in list)
                builder.append(token.toString())
            return builder.toString().toToken()
        }),
        "string->bytes" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            val builder = StringBuilder()
            for (token in args)
                builder.append(token.toString())
            val list = ArrayList<Token>()
            val bytes = builder.toString().toByteArray()
            for (byte in bytes)
                list.add(byte.toToken())
            return list.toToken()
        }),
        "bytes->string" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val list = cast<List<Token>>(args[0].value)
            val bytes = ByteArray(list.size)
            for (index in list.indices) {
                assert(list[index].type == BYTE)
                bytes[index] = cast<Byte>(list[index].value)
            }
            return String(bytes).toToken()
        }),
        "string->bits" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            val s = args[0].toString()
            val result = ArrayList<Token>()
            for (c in s)
                result.add(c.code.toToken())
            return result.toToken()
        }),
        "bits->string" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == LIST)
            val result = StringBuilder()
            val tokens = cast<List<Token>>(args[0].value)
            for (t in tokens) {
                assert(t.type == NUM)
                result.append(cast<Int>(t.value).toChar())
            }
            return result.toToken()
        }),
        "bool?" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args)
                if (arg.type != BOOL)
                    return FALSE
            return TRUE
        }),
        "string?" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args)
                if (arg.type != STR)
                    return FALSE
            return TRUE
        }),
        "num?" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args)
                if (arg.type != NUM && arg.type != BIG_NUM)
                    return FALSE
            return TRUE
        }),
        "real?" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args)
                if (arg.type != REAL && arg.type != BIG_REAL)
                    return FALSE
            return TRUE
        }),
        "list?" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args)
                if (arg.type != LIST)
                    return FALSE
            return TRUE
        }),
        "byte?" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args)
                if (arg.type != BYTE)
                    return FALSE
            return TRUE
        }),
        "function?" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            for (arg in args)
                if (arg.type != FUNCTION && arg.type != STATIC_FUNCTION && arg.type != HIME_FUNCTION)
                    return FALSE
            return TRUE
        }),
        "exit" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == NUM)
            exitProcess(cast<Int>(args[0].value))
        }),
        "exit" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == NUM)
            exitProcess(cast<Int>(args[0].value))
        }),
        // (extern "class" "function name")
        "extern" to Token(FUNCTION, fun(args: List<Token>, symbolTable: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == STR)
            assert(args[1].type == STR)
            val clazz = args[0].toString()
            val name = args[1].toString()
            val method = Class.forName(clazz).declaredMethods
                .firstOrNull { Modifier.isStatic(it.modifiers) && it.name == name }
                ?: throw UnsatisfiedLinkError("Method $name not found for class $clazz.")
            symbolTable.put(name, Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
                val newArgs = arrayOfNulls<Any>(args.size)
                for (i in args.indices)
                    newArgs[i] = args[i].value
                return method.invoke(null, *newArgs).toToken()
            }))
            return NIL
        }),
        "eval" to Token(FUNCTION, fun(args: List<Token>, symbol: SymbolTable): Token {
            assert(args.isNotEmpty())
            val newSymbol = symbol.createChild()
            var result = NIL
            for (node in args)
                result = call(node.toString(), newSymbol)
            return result
        }),
        "bit-and" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == NUM || args[0].type == BIG_NUM)
            assert(args[1].type == NUM || args[1].type == BIG_NUM)
            val m = BigInteger(args[0].toString())
            val n = BigInteger(args[1].toString())
            return m.and(n).toToken()
        }),
        "bit-or" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == NUM || args[0].type == BIG_NUM)
            assert(args[1].type == NUM || args[1].type == BIG_NUM)
            val m = BigInteger(args[0].toString())
            val n = BigInteger(args[1].toString())
            return m.or(n).toToken()
        }),
        "bit-xor" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == NUM || args[0].type == BIG_NUM)
            assert(args[1].type == NUM || args[1].type == BIG_NUM)
            val m = BigInteger(args[0].toString())
            val n = BigInteger(args[1].toString())
            return m.xor(n).toToken()
        }),
        "bit-left" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == NUM || args[0].type == BIG_NUM)
            assert(args[1].type == NUM)
            val m = BigInteger(args[0].toString())
            val n = cast<Int>(args[1].value)
            return m.shiftLeft(n).toToken()
        }),
        "bit-right" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == NUM || args[0].type == BIG_NUM)
            assert(args[1].type == NUM)
            val m = BigInteger(args[0].toString())
            val n = cast<Int>(args[1].value)
            return m.shiftRight(n).toToken()
        })
    ), null
)

val file = SymbolTable(
    mutableMapOf(
        "file-exists" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return File(args[0].toString()).exists().toToken()
        }),
        "file-list" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            fun listAllFile(f: File): Token {
                val list = ArrayList<Token>()
                val files = f.listFiles()
                for (file in files!!) {
                    if (file.isDirectory)
                        list.add(listAllFile(file))
                    else
                        list.add(file.path.toToken())
                }
                return list.toToken()
            }
            assert(args.isNotEmpty())
            return listAllFile(File(args[0].toString()))
        }),
        "file-mkdirs" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            val file = File(args[0].toString())
            if (!file.parentFile.exists())
                !file.parentFile.mkdirs()
            if (!file.exists())
                file.createNewFile()
            return NIL
        }),
        "file-new" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            val file = File(args[0].toString())
            if (!file.exists())
                file.createNewFile()
            return NIL
        }),
        "file-read-string" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            return Files.readString(Paths.get(args[0].toString())).toToken()
        }),
        "file-remove" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            File(args[0].toString()).delete()
            return NIL
        }),
        "file-write-string" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            val file = File(args[0].toString())
            if (!file.parentFile.exists())
                !file.parentFile.mkdirs()
            if (!file.exists())
                file.createNewFile()
            Files.writeString(file.toPath(), args[1].toString())
            return NIL
        }),
        "file-read-bytes" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            val list = ArrayList<Token>()
            val bytes = Files.readAllBytes(Paths.get(args[0].toString()))
            for (byte in bytes)
                list.add(byte.toToken())
            return list.toToken()
        }),
        "file-write-bytes" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[1].type == LIST)
            val file = File(args[0].toString())
            if (!file.parentFile.exists())
                !file.parentFile.mkdirs()
            if (!file.exists())
                file.createNewFile()
            val list = cast<List<Token>>(args[1].value)
            val bytes = ByteArray(list.size)
            for (index in list.indices) {
                assert(list[index].type == BYTE)
                bytes[index] = cast<Byte>(list[index].value)
            }
            Files.write(file.toPath(), bytes)
            return NIL
        })
    ), null
)

val time = SymbolTable(
    mutableMapOf(
        "time" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            return Date().time.toToken()
        }),
        "time-format" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[1].type == NUM || args[1].type == BIG_NUM)
            return SimpleDateFormat(args[0].toString()).format(args[1].toString().toLong())
                .toToken()
        }),
        "time-parse" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            return SimpleDateFormat(args[0].toString()).parse(args[1].value.toString()).time.toToken()
        })
    ), null
)

val table = SymbolTable(
    mutableMapOf(
        "table" to Token(FUNCTION, fun(_: List<Token>, _: SymbolTable): Token {
            return mapOf<Token, Token>().toToken()
        }),
        "table-put" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[0].type == TABLE)
            val table = HashMap(cast<Map<Token, Token>>(args[0].value))
            table[args[1]] = args[2]
            return table.toToken()
        }),
        "table-get" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 1)
            assert(args[0].type == TABLE)
            val table = cast<Map<Token, Token>>(args[0].value)
            assert(table.containsKey(args[1]))
            return table[args[1]] ?: NIL
        }),
        "table-remove" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[0].type == TABLE)
            val table = HashMap(cast<Map<Token, Token>>(args[0].value))
            table.remove(args[1])
            return table.toToken()
        }),
        "table-keys" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.isNotEmpty())
            assert(args[0].type == TABLE)
            return cast<Map<Token, Token>>(args[0].value).keys.toList().toToken()
        }),
        "table-put!" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[0].type == TABLE)
            cast<MutableMap<Token, Token>>(args[0].value)[args[1]] = args[2]
            return args[0].toToken()
        }),
        "table-remove!" to Token(FUNCTION, fun(args: List<Token>, _: SymbolTable): Token {
            assert(args.size > 2)
            assert(args[0].type == TABLE)
            cast<MutableMap<Token, Token>>(args[0].value).remove(args[1])
            return args[0].toToken()
        })
    ), null
)

val module = mutableMapOf(
    "util.file" to file,
    "util.time" to time,
    "util.table" to table
)
