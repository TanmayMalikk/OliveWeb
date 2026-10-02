package com.olive.app.ui

import kotlin.math.*

enum class CalculatorAngleMode { RADIANS, DEGREES }

// Graph equations are parsed and evaluated locally; add supported functions in FunctionNode when needed.
class GraphExpression private constructor(private val root: Node) {
    fun evaluate(x: Double): Double = root.value(x)

    companion object {
        fun compile(input: String, angleMode: CalculatorAngleMode=CalculatorAngleMode.RADIANS): GraphExpression {
            val expression=input.trim().replaceFirst(Regex("^[yY]\\s*=\\s*"),"").trim()
            require(expression.isNotBlank()) { "Enter an equation after y =." }
            return GraphExpression(Parser(expression,angleMode).parse())
        }
    }

    private fun interface Node { fun value(x: Double): Double }

    private class Parser(private val source: String, private val angleMode: CalculatorAngleMode) {
        private var index=0

        fun parse(): Node {
            val result=addition()
            skipSpaces()
            require(index==source.length) { "Check the equation near '${source.substring(index).take(8)}'." }
            return result
        }

        private fun addition(): Node {
            var left=multiplication()
            while(true) {
                skipSpaces()
                left=when {
                    take('+') -> binary(left,multiplication()) { a,b -> a+b }
                    take('-') -> binary(left,multiplication()) { a,b -> a-b }
                    else -> return left
                }
            }
        }

        private fun multiplication(): Node {
            var left=unary()
            while(true) {
                skipSpaces()
                left=when {
                    take('*') || take('×') -> binary(left,unary()) { a,b -> a*b }
                    take('/') || take('÷') -> binary(left,unary()) { a,b -> a/b }
                    startsPrimary() -> binary(left,unary()) { a,b -> a*b }
                    else -> return left
                }
            }
        }

        private fun unary(): Node {
            skipSpaces()
            return when {
                take('+') -> unary()
                take('-') -> unary().let { node -> Node { x -> -node.value(x) } }
                else -> power()
            }
        }

        private fun power(): Node {
            val base=primary()
            skipSpaces()
            return if(take('^')) binary(base,unary()) { a,b -> a.pow(b) } else base
        }

        private fun primary(): Node {
            skipSpaces()
            require(index<source.length) { "The equation ends too early." }
            if(take('(')) {
                val inside=addition()
                skipSpaces()
                require(take(')')) { "Close every parenthesis." }
                return inside
            }
            if(source[index].isDigit() || source[index]=='.') return number()
            if(source[index].isLetter()) {
                val name=identifier().lowercase()
                return when(name) {
                    "x" -> Node { x -> x }
                    "pi" -> Node { PI }
                    "e" -> Node { E }
                    else -> function(name)
                }
            }
            throw IllegalArgumentException("Unexpected '${source[index]}'.")
        }

        private fun number(): Node {
            val start=index
            while(index<source.length && (source[index].isDigit() || source[index]=='.')) index++
            val number=source.substring(start,index).toDoubleOrNull()
                ?: throw IllegalArgumentException("Check the number near '${source.substring(start).take(8)}'.")
            return Node { number }
        }

        private fun identifier(): String {
            val start=index
            while(index<source.length && source[index].isLetter()) index++
            return source.substring(start,index)
        }

        private fun function(name: String): Node {
            skipSpaces()
            require(take('(')) { "Write $name with parentheses, like $name(x)." }
            val argument=addition()
            skipSpaces()
            require(take(')')) { "Close every parenthesis." }
            return when(name) {
                "sin" -> Node { x -> sin(angle(argument.value(x))) }
                "cos" -> Node { x -> cos(angle(argument.value(x))) }
                "tan" -> Node { x -> tan(angle(argument.value(x))) }
                "sqrt" -> Node { x -> sqrt(argument.value(x)) }
                "abs" -> Node { x -> abs(argument.value(x)) }
                "ln" -> Node { x -> ln(argument.value(x)) }
                "log" -> Node { x -> log10(argument.value(x)) }
                else -> throw IllegalArgumentException("Function '$name' is not supported.")
            }
        }

        private fun angle(value: Double)=if(angleMode==CalculatorAngleMode.DEGREES) Math.toRadians(value) else value

        private fun binary(left: Node, right: Node, operation: (Double,Double)->Double)=
            Node { x -> operation(left.value(x),right.value(x)) }

        private fun startsPrimary(): Boolean {
            skipSpaces()
            return index<source.length && (source[index]=='(' || source[index]=='.' || source[index].isDigit() || source[index].isLetter())
        }

        private fun take(character: Char): Boolean {
            if(index<source.length && source[index]==character) { index++;return true }
            return false
        }

        private fun skipSpaces() { while(index<source.length && source[index].isWhitespace()) index++ }
    }
}
