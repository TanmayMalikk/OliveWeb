package com.olive.app.ui

import kotlin.math.*

enum class CalculatorAngleMode { RADIANS, DEGREES }

// Graph equations are parsed and evaluated locally; add supported functions in FunctionNode when needed.
class GraphExpression private constructor(private val root: Node) {
    fun evaluate(x: Double): Double = root.value(x)

    companion object {
        fun compile(input: String, angleMode: CalculatorAngleMode=CalculatorAngleMode.RADIANS): GraphExpression {
            val expression=input.trim().replaceFirst(Regex("^[yY]\\s*=\\s*"),"").trim()
                .replace(Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(?:x|×)\\s*10\\s*\\^\\s*([+-]?[0-9]+)")) { "${it.groupValues[1]}e${it.groupValues[2]}" }
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
            var base=primary()
            skipSpaces()
            while(index<source.length && source[index] in "!%") {
                val operation=source[index++]
                val argument=base
                base=if(operation=='!') Node { x -> factorial(argument.value(x)) }
                    else Node { x -> argument.value(x)/100.0 }
                skipSpaces()
            }
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
            if(index<source.length && source[index].lowercaseChar()=='e') {
                index++
                if(index<source.length && source[index] in "+-") index++
                while(index<source.length && source[index].isDigit()) index++
            }
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
            val arguments=mutableListOf<Node>()
            if(!take(')')) {
                do { arguments += addition();skipSpaces() } while(take(','))
                require(take(')')) { "Close every parenthesis." }
            }
            fun one(): Node {
                require(arguments.size==1) { "$name needs one value." }
                return arguments[0]
            }
            fun two(): Pair<Node,Node> {
                require(arguments.size==2) { "$name needs two values." }
                return arguments[0] to arguments[1]
            }
            return when(name) {
                "sin" -> one().let { argument -> Node { x -> sin(angle(argument.value(x))) } }
                "cos" -> one().let { argument -> Node { x -> cos(angle(argument.value(x))) } }
                "tan" -> one().let { argument -> Node { x -> tan(angle(argument.value(x))) } }
                "asin","sin1" -> one().let { argument -> Node { x -> inverseAngle(asin(argument.value(x))) } }
                "acos","cos1" -> one().let { argument -> Node { x -> inverseAngle(acos(argument.value(x))) } }
                "atan","tan1" -> one().let { argument -> Node { x -> inverseAngle(atan(argument.value(x))) } }
                "sqrt" -> one().let { argument -> Node { x -> sqrt(argument.value(x)) } }
                "cbrt" -> one().let { argument -> Node { x -> cbrt(argument.value(x)) } }
                "abs" -> one().let { argument -> Node { x -> abs(argument.value(x)) } }
                "ln" -> one().let { argument -> Node { x -> ln(argument.value(x)) } }
                "log" -> one().let { argument -> Node { x -> log10(argument.value(x)) } }
                "exp" -> one().let { argument -> Node { x -> exp(argument.value(x)) } }
                "floor" -> one().let { argument -> Node { x -> floor(argument.value(x)) } }
                "ceil" -> one().let { argument -> Node { x -> ceil(argument.value(x)) } }
                "round" -> one().let { argument -> Node { x -> round(argument.value(x)) } }
                "min" -> two().let { (a,b) -> Node { x -> min(a.value(x),b.value(x)) } }
                "max" -> two().let { (a,b) -> Node { x -> max(a.value(x),b.value(x)) } }
                "mod","remainder" -> two().let { (a,b) -> Node { x -> a.value(x)%b.value(x) } }
                "root" -> two().let { (a,b) -> Node { x -> a.value(x).pow(1.0/b.value(x)) } }
                "npr" -> two().let { (a,b) -> Node { x -> permutation(a.value(x),b.value(x)) } }
                "ncr" -> two().let { (a,b) -> Node { x -> combination(a.value(x),b.value(x)) } }
                "random" -> { require(arguments.isEmpty()) { "random needs no values." };Node { Math.random() } }
                "randint" -> two().let { (a,b) -> Node { x ->
                    val low=ceil(a.value(x)).toInt();val high=floor(b.value(x)).toInt()
                    require(high>=low) { "randint needs a valid range." }
                    (low..high).random().toDouble()
                } }
                else -> throw IllegalArgumentException("Function '$name' is not supported.")
            }
        }

        private fun angle(value: Double)=if(angleMode==CalculatorAngleMode.DEGREES) Math.toRadians(value) else value
        private fun inverseAngle(value: Double)=if(angleMode==CalculatorAngleMode.DEGREES) Math.toDegrees(value) else value
        private fun factorial(value: Double): Double {
            val count=value.toInt()
            require(value==count.toDouble() && count in 0..170) { "Factorial needs a whole number from 0 to 170." }
            return (2..count).fold(1.0) { total,next -> total*next }
        }
        private fun permutation(nValue: Double,rValue: Double): Double {
            val n=nValue.toInt();val r=rValue.toInt()
            require(nValue==n.toDouble() && rValue==r.toDouble() && n>=0 && r in 0..n) { "nPr needs whole numbers with 0 ≤ r ≤ n." }
            return ((n-r+1)..n).fold(1.0) { total,next -> total*next }
        }
        private fun combination(nValue: Double,rValue: Double): Double {
            val n=nValue.toInt();val r=rValue.toInt()
            require(nValue==n.toDouble() && rValue==r.toDouble() && n>=0 && r in 0..n) { "nCr needs whole numbers with 0 ≤ r ≤ n." }
            val use=min(r,n-r)
            return (1..use).fold(1.0) { total,next -> total*(n-use+next)/next }
        }

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
