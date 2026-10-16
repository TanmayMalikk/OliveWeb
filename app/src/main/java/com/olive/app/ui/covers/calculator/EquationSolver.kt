package com.olive.app.ui.covers.calculator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// These tools solve typed equations locally; expand the parser here if more equation formats are needed.
object EquationSolver {
    private const val MAX_DEGREE = 8
    private const val EPSILON = 1e-9

    fun quadratic(equation: String): String {
        val coefficients=parse(equation)
        require(coefficients.lastIndex==2) { "Enter a quadratic equation with an x² term." }
        val c=coefficients[0]
        val b=coefficients[1]
        val a=coefficients[2]
        val discriminant=b*b-4*a*c
        return if(discriminant>=-EPSILON) {
            val root=sqrt(max(0.0,discriminant))
            roots(listOf(Complex((-b+root)/(2*a)),Complex((-b-root)/(2*a))))
        } else {
            val real=-b/(2*a)
            val imaginary=sqrt(-discriminant)/abs(2*a)
            roots(listOf(Complex(real,imaginary),Complex(real,-imaginary)))
        }
    }

    fun polynomial(equation: String): String {
        val coefficients=parse(equation)
        return when(coefficients.lastIndex) {
            1 -> roots(listOf(Complex(-coefficients[0]/coefficients[1])))
            2 -> quadratic(equation)
            else -> roots(durandKerner(coefficients))
        }
    }

    fun calculatePolynomial(expression: String): String {
        val cleaned=normalize(expression)
        require(cleaned.isNotBlank()) { "Enter a polynomial calculation first." }
        require('=' !in cleaned) { "Enter a calculation without an equals sign." }
        return formatPolynomial(PolynomialParser(cleaned).parse())
    }

    private fun parse(equation: String): DoubleArray {
        val cleaned=normalize(equation)
        require(cleaned.isNotBlank()) { "Enter an equation first." }
        val sides=cleaned.split('=')
        require(sides.size<=2 && sides.none(String::isBlank)) { "Use one equals sign with an expression on each side." }
        val left=parseSide(sides[0])
        val right=parseSide(sides.getOrElse(1) { "0" })
        val combined=DoubleArray(max(left.size,right.size)) { index ->
            left.getOrElse(index) { 0.0 }-right.getOrElse(index) { 0.0 }
        }
        var degree=combined.lastIndex
        while(degree>0 && abs(combined[degree])<EPSILON) degree--
        require(degree>0) { "The equation must contain x." }
        require(degree<=MAX_DEGREE) { "Use a polynomial with degree $MAX_DEGREE or lower." }
        return combined.copyOf(degree+1)
    }

    private fun normalize(value: String)=normalizeCalculatorNotation(value).lowercase().replace(" ","")
        .replace('−','-').replace("²","^2").replace("³","^3")

    private fun parseSide(side: String): DoubleArray {
        return PolynomialParser(side).parse()
    }

    private class PolynomialParser(private val source: String) {
        private var index=0

        fun parse(): DoubleArray {
            require(source.isNotBlank()) { "Enter terms such as (x - 2)(x - 3)." }
            val result=addition()
            require(index==source.length) { "Check the equation near '${source.substring(index).take(8)}'." }
            return result
        }

        private fun addition(): DoubleArray {
            var left=multiplication()
            while(true) left=when {
                take('+') -> add(left,multiplication(),1.0)
                take('-') -> add(left,multiplication(),-1.0)
                else -> return left
            }
        }

        private fun multiplication(): DoubleArray {
            var left=unary()
            while(true) left=when {
                take('*') -> multiply(left,unary())
                take('/') || take('÷') -> divide(left,unary())
                startsPrimary() -> multiply(left,unary())
                else -> return left
            }
        }

        private fun unary(): DoubleArray = when {
            take('+') -> unary()
            take('-') -> unary().map { -it }.toDoubleArray()
            else -> power()
        }

        private fun power(): DoubleArray {
            val base=primary()
            if(!take('^')) return base
            val start=index
            while(index<source.length && source[index].isDigit()) index++
            val exponent=source.substring(start,index).toIntOrNull()
                ?: throw IllegalArgumentException("Use a whole-number exponent after ^.")
            require(exponent in 0..MAX_DEGREE) { "Use exponents from 0 to $MAX_DEGREE." }
            var result=doubleArrayOf(1.0)
            repeat(exponent) { result=multiply(result,base) }
            return result
        }

        private fun primary(): DoubleArray {
            require(index<source.length) { "The equation ends too early." }
            if(take('(')) {
                val value=addition()
                require(take(')')) { "Close every parenthesis." }
                return value
            }
            if(source[index]=='x') { index++;return doubleArrayOf(0.0,1.0) }
            if(source[index].isDigit() || source[index]=='.') {
                val start=index
                while(index<source.length && (source[index].isDigit() || source[index]=='.')) index++
                if(index<source.length && source[index]=='e') {
                    index++
                    if(index<source.length && source[index] in "+-") index++
                    while(index<source.length && source[index].isDigit()) index++
                }
                val value=source.substring(start,index).toDoubleOrNull()
                    ?: throw IllegalArgumentException("Check the number near '${source.substring(start).take(8)}'.")
                require(value.isFinite()) { "Use finite numbers only." }
                return doubleArrayOf(value)
            }
            throw IllegalArgumentException("Unexpected '${source[index]}'.")
        }

        private fun startsPrimary()=index<source.length && (source[index]=='(' || source[index]=='x' || source[index]=='.' || source[index].isDigit())

        private fun take(character: Char): Boolean {
            if(index<source.length && source[index]==character) { index++;return true }
            return false
        }

        private fun add(left: DoubleArray, right: DoubleArray, sign: Double): DoubleArray {
            val result=DoubleArray(max(left.size,right.size)) { position ->
                left.getOrElse(position) { 0.0 }+sign*right.getOrElse(position) { 0.0 }
            }
            return trim(result)
        }

        private fun multiply(left: DoubleArray, right: DoubleArray): DoubleArray {
            val result=DoubleArray(left.lastIndex+right.lastIndex+1)
            left.forEachIndexed { leftPower,leftValue -> right.forEachIndexed { rightPower,rightValue ->
                val power=leftPower+rightPower
                require(power<=MAX_DEGREE || abs(leftValue*rightValue)<EPSILON) { "Use a polynomial with degree $MAX_DEGREE or lower." }
                if(power<=MAX_DEGREE) result[power]+=leftValue*rightValue
            } }
            return trim(result.copyOf(minOf(result.size,MAX_DEGREE+1)))
        }

        private fun divide(dividend: DoubleArray, divisor: DoubleArray): DoubleArray {
            val cleanDividend=trim(dividend)
            val cleanDivisor=trim(divisor)
            require(cleanDivisor.any { abs(it)>=EPSILON }) { "Cannot divide by zero." }
            if(cleanDividend.lastIndex<cleanDivisor.lastIndex) {
                require(cleanDividend.all { abs(it)<EPSILON }) { "This division does not simplify to a polynomial." }
                return doubleArrayOf(0.0)
            }
            val remainder=cleanDividend.copyOf()
            val quotient=DoubleArray(cleanDividend.lastIndex-cleanDivisor.lastIndex+1)
            for(power in quotient.lastIndex downTo 0) {
                val factor=remainder[cleanDivisor.lastIndex+power]/cleanDivisor.last()
                quotient[power]=factor
                cleanDivisor.forEachIndexed { divisorPower,value -> remainder[divisorPower+power]-=factor*value }
            }
            require(remainder.all { abs(it)<1e-7 }) { "This division leaves a remainder and does not simplify to a polynomial." }
            return trim(quotient)
        }

        private fun trim(value: DoubleArray): DoubleArray {
            var last=value.lastIndex
            while(last>0 && abs(value[last])<EPSILON) last--
            return value.copyOf(last+1)
        }
    }

    private fun formatPolynomial(coefficients: DoubleArray): String {
        val terms=coefficients.indices.reversed().mapNotNull { power ->
            val coefficient=coefficients[power]
            if(abs(coefficient)<EPSILON) null else {
                val magnitude=abs(coefficient)
                val variable=when(power) { 0 -> ""; 1 -> "x"; else -> "x^$power" }
                val amount=if(power>0 && abs(magnitude-1.0)<EPSILON) "" else number(magnitude)
                Triple(coefficient<0,amount,variable)
            }
        }
        if(terms.isEmpty()) return "0"
        return terms.mapIndexed { index,(negative,amount,variable) ->
            val body=amount+variable
            when { index==0 && negative -> "-$body"; index==0 -> body; negative -> "− $body"; else -> "+ $body" }
        }.joinToString(" ")
    }

    private fun durandKerner(coefficients: DoubleArray): List<Complex> {
        val degree=coefficients.lastIndex
        val lead=coefficients.last()
        val normalized=coefficients.map { it/lead }
        val radius=1.0+normalized.dropLast(1).maxOf { abs(it) }
        val values=MutableList(degree) { index ->
            val angle=(2*PI*index/degree)+0.37
            Complex(radius*cos(angle),radius*sin(angle))
        }
        repeat(1500) {
            var largest=0.0
            val next=values.toMutableList()
            for(index in values.indices) {
                var denominator=Complex(1.0)
                for(other in values.indices) if(other!=index) denominator*=values[index]-values[other]
                if(denominator.magnitude<EPSILON) denominator+=Complex(EPSILON,EPSILON)
                val change=evaluate(normalized,values[index])/denominator
                next[index]=values[index]-change
                largest=max(largest,change.magnitude)
            }
            values.indices.forEach { values[it]=next[it] }
            if(largest<1e-10) return values
        }
        return values
    }

    private fun evaluate(coefficients: List<Double>, value: Complex): Complex =
        coefficients.asReversed().fold(Complex(0.0)) { total,coefficient -> total*value+Complex(coefficient) }

    private fun roots(values: List<Complex>): String {
        val ordered=values.map { if(abs(it.imaginary)<1e-7) it.copy(imaginary=0.0) else it }
            .sortedWith(compareBy<Complex> { it.real }.thenBy { it.imaginary })
        return "x = "+ordered.joinToString(", ") { value ->
            if(value.imaginary==0.0) number(value.real)
            else "${number(value.real)} ${if(value.imaginary>=0) "+" else "−"} ${number(abs(value.imaginary))}i"
        }
    }

    private fun number(value: Double): String {
        val cleaned=if(abs(value)<1e-8) 0.0 else value
        return formatCalculatorNumber(cleaned,9)
    }

    private data class Complex(val real: Double, val imaginary: Double=0.0) {
        val magnitude get()=sqrt(real*real+imaginary*imaginary)
        operator fun plus(other: Complex)=Complex(real+other.real,imaginary+other.imaginary)
        operator fun minus(other: Complex)=Complex(real-other.real,imaginary-other.imaginary)
        operator fun times(other: Complex)=Complex(real*other.real-imaginary*other.imaginary,real*other.imaginary+imaginary*other.real)
        operator fun div(other: Complex): Complex {
            val scale=other.real*other.real+other.imaginary*other.imaginary
            return Complex((real*other.real+imaginary*other.imaginary)/scale,(imaginary*other.real-real*other.imaginary)/scale)
        }
    }
}
