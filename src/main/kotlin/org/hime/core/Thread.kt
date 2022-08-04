package org.hime.core

import org.hime.cast
import org.hime.lang.Env
import org.hime.lang.FuncType
import org.hime.lang.HimeFunction
import org.hime.lang.SymbolTable
import org.hime.parse.ASTNode
import org.hime.parse.Token
import org.hime.toToken
import java.util.concurrent.locks.ReentrantLock

fun initThread(env: Env) {
    env.symbols.table.putAll(
        mutableMapOf(
            "make-lock" to (HimeFunction(env, FuncType.BUILT_IN, fun(_: List<Token>, _: SymbolTable): Token {
                return ReentrantLock().toToken(env)
            }, 0)).toToken(env),
            "lock" to (HimeFunction(
                env,
                FuncType.BUILT_IN,
                @Synchronized
                fun(args: List<Token>, _: SymbolTable): Token {
                    for (arg in args)
                        cast<ReentrantLock>(arg.value).lock()
                    return env.himeNil
                }, listOf(env.getType("lock")), true
            )).toToken(env),
            "unlock" to (HimeFunction(
                env,
                FuncType.BUILT_IN,
                @Synchronized
                fun(args: List<Token>, _: SymbolTable): Token {
                    for (arg in args)
                        cast<ReentrantLock>(arg.value).unlock()
                    return env.himeNil
                }, listOf(env.getType("lock")), true
            )).toToken(env),
            "get-lock" to (HimeFunction(env, FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                return cast<ReentrantLock>(args[0].value).isLocked.toToken(env)
            }, listOf(env.getType("lock")), false)).toToken(env),
            "sleep" to (HimeFunction(env, FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                Thread.sleep(args[0].toString().toLong())
                return env.himeNil
            }, listOf(env.getType("int")), false)).toToken(env),
            "thread" to (HimeFunction(
                env,
                FuncType.BUILT_IN, fun(args: List<Token>, symbol: SymbolTable): Token {
                    // 为了能够（简便的）调用HimeFunction，将参数放到一个ast树中
                    val asts = env.himeAstEmpty.copy()
                    asts.add(ASTNode(Thread.currentThread().toToken(env)))
                    return if (args.size > 1)
                        Thread({
                            cast<HimeFunction>(args[0].value).call(asts, symbol.createChild())
                        }, args[1].toString()).toToken(env)
                    else
                        Thread {
                            cast<HimeFunction>(args[0].value).call(asts, symbol.createChild())
                        }.toToken(env)
                },
                listOf(env.getType("function")),
                true
            )).toToken(env), //这种类重载函数的参数数量处理还比较棘手
            "thread-start" to (HimeFunction(
                env,
                FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                    cast<Thread>(args[0].value).start()
                    return env.himeNil
                }, listOf(env.getType("thread")), false
            )).toToken(env),
            "thread-current" to (HimeFunction(
                env,
                FuncType.BUILT_IN, fun(_: List<Token>, _: SymbolTable): Token {
                    return Thread.currentThread().toToken(env)
                }, 0
            )).toToken(env),
            "thread-name" to (HimeFunction(env, FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                return cast<Thread>(args[0].value).name.toToken(env)
            }, listOf(env.getType("thread")), false)).toToken(env),
            "thread-set-daemon" to (HimeFunction(
                env,
                FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                    cast<Thread>(args[0].value).isDaemon = cast<Boolean>(args[1].value)
                    return env.himeNil
                }, listOf(env.getType("thread"), env.getType("bool")), false
            )).toToken(env),
            "thread-daemon" to (HimeFunction(env, FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                return cast<Thread>(args[0].value).isDaemon.toToken(env)
            }, listOf(env.getType("thread")), false)).toToken(env),
            "thread-interrupt" to (HimeFunction(
                env,
                FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                    cast<Thread>(args[0].value).interrupt()
                    return env.himeNil
                }, listOf(env.getType("thread")), false
            )).toToken(env),
            "thread-join" to (HimeFunction(
                env,
                FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                    cast<Thread>(args[0].value).join()
                    return env.himeNil
                }, listOf(env.getType("thread")), false
            )).toToken(env),
            "thread-alive" to (HimeFunction(env, FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                return cast<Thread>(args[0].value).isAlive.toToken(env)
            }, listOf(env.getType("thread")), false)).toToken(env),
            "thread-interrupted" to (HimeFunction(env, FuncType.BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                return cast<Thread>(args[0].value).isInterrupted.toToken(env)
            }, listOf(env.getType("thread")), false)).toToken(env)
        )
    )
}