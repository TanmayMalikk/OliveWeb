package com.olive.app.ui

import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.LUDecomposition
import org.apache.commons.math3.linear.MatrixUtils
import org.apache.commons.math3.stat.StatUtils
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math3.stat.regression.SimpleRegression
import org.apache.commons.math3.fitting.SimpleCurveFitter
import org.apache.commons.math3.fitting.WeightedObservedPoint
import kotlin.math.*

// These are the advanced calculator choices. Add a choice here and handle it in calculate() below.
internal enum class AdvancedTool(val category: String, val title: String, val example: String) {
    Expression("Basic", "Evaluate expression", "sqrt(81) + 5! or nCr(10,3)"),
    FunctionValue("Algebra", "Function value", "x^2 + 2x ; 3"),
    LinearSystem("Algebra", "System of equations", "1,1,5;2,-1,1"),
    Fraction("Basic", "Fraction / decimal", "1.25 or 7/4"),
    MixedFraction("Basic", "Mixed / improper fraction", "2 1/3 or 7/3"),
    Scientific("Basic", "Scientific notation", "1.2E5 or 1.2×10^5"),
    Derivative("Calculus", "Numerical derivative", "x^3 - 2x ; 2"),
    Integral("Calculus", "Definite integral", "sin(x) ; 0 ; pi"),
    Zero("Calculus", "Find zero", "x^2 - 4 ; 0 ; 5"),
    Minimum("Calculus", "Find minimum", "x^2 - 4x ; -10 ; 10"),
    Maximum("Calculus", "Find maximum", "-x^2 + 4x ; -10 ; 10"),
    Intersection("Calculus", "Find intersection", "x^2 ; 2x + 3 ; -10 ; 10"),
    Limit("Calculus", "Investigate limit", "sin(x)/x ; 0"),
    ComplexMath("Complex", "Complex arithmetic", "(2,3) * (4,-1)"),
    ComplexDetails("Complex", "Complex details", "3,4"),
    ComplexPower("Complex", "Complex power / roots", "3,4 ; 2"),
    MatrixAdd("Matrices", "Matrix add / subtract", "1,2;3,4 | 5,6;7,8 | +"),
    MatrixMultiply("Matrices", "Matrix multiply", "1,2;3,4 | 5,6;7,8"),
    MatrixScalar("Matrices", "Scalar multiplication", "1,2;3,4 | 3"),
    MatrixPower("Matrices", "Matrix power", "1,2;3,4 | 3"),
    MatrixInverse("Matrices", "Inverse", "1,2;3,4"),
    MatrixDeterminant("Matrices", "Determinant", "1,2;3,4"),
    MatrixTranspose("Matrices", "Transpose", "1,2,3;4,5,6"),
    MatrixIdentity("Matrices", "Identity matrix", "3"),
    MatrixRref("Matrices", "RREF / augmented matrix", "1,2,5;3,4,11"),
    MatrixRowOperation("Matrices", "Row operation", "1,2;3,4 | swap 1 2"),
    OneVariableStats("Statistics", "One-variable statistics", "1,2,2,4,8"),
    TwoVariableStats("Statistics", "Two-variable statistics", "1,2,3 | 2,4,5"),
    PolynomialRegression("Statistics", "Polynomial regression", "degree 2 | 1,2,3 | 2,5,10"),
    Regression("Statistics", "Other regression", "exponential | 1,2,3 | 2,4,8")
}

// All advanced calculations stay local on the phone. Semicolons separate expression arguments and pipes separate data sets.
internal object AdvancedCalculatorEngine {
    fun calculate(tool: AdvancedTool, input: String, mode: CalculatorAngleMode): String = when(tool) {
        AdvancedTool.Expression -> number(GraphExpression.compile(input,mode).evaluate(0.0))
        AdvancedTool.FunctionValue -> semi(input,2).let { "value = ${number(GraphExpression.compile(it[0],mode).evaluate(value(it[1],mode)))}" }
        AdvancedTool.LinearSystem -> matrix(rref(matrix(input)))
        AdvancedTool.Fraction -> fraction(input)
        AdvancedTool.MixedFraction -> mixed(input)
        AdvancedTool.Scientific -> scientific(input)
        AdvancedTool.Derivative -> derivative(input,mode)
        AdvancedTool.Integral -> integral(input,mode)
        AdvancedTool.Zero -> optimize(input,mode,"zero")
        AdvancedTool.Minimum -> optimize(input,mode,"min")
        AdvancedTool.Maximum -> optimize(input,mode,"max")
        AdvancedTool.Intersection -> intersection(input,mode)
        AdvancedTool.Limit -> limit(input,mode)
        AdvancedTool.ComplexMath -> complexArithmetic(input)
        AdvancedTool.ComplexDetails -> complexDetails(input)
        AdvancedTool.ComplexPower -> complexPower(input)
        AdvancedTool.MatrixAdd -> matrixAdd(input)
        AdvancedTool.MatrixMultiply -> matrixBinary(input) { a,b -> a.multiply(b) }
        AdvancedTool.MatrixScalar -> matrixScalar(input)
        AdvancedTool.MatrixPower -> matrixPower(input)
        AdvancedTool.MatrixInverse -> matrix(LUDecomposition(matrix(input)).solver.inverse)
        AdvancedTool.MatrixDeterminant -> number(LUDecomposition(matrix(input)).determinant)
        AdvancedTool.MatrixTranspose -> matrix(matrix(input).transpose())
        AdvancedTool.MatrixIdentity -> matrix(MatrixUtils.createRealIdentityMatrix(input.trim().toInt().also { require(it in 1..10) }))
        AdvancedTool.MatrixRref -> matrix(rref(matrix(input)))
        AdvancedTool.MatrixRowOperation -> matrixRowOperation(input)
        AdvancedTool.OneVariableStats -> oneVariable(input)
        AdvancedTool.TwoVariableStats -> twoVariable(input)
        AdvancedTool.PolynomialRegression -> polynomialRegression(input)
        AdvancedTool.Regression -> regression(input)
    }

    private fun fraction(raw: String): String {
        val value=if('/' in raw) raw.split('/').let { require(it.size==2);it[0].trim().toDouble()/it[1].trim().toDouble() } else raw.trim().toDouble()
        val (n,d)=approximateFraction(value)
        return "$n/$d = ${number(value)}"
    }
    private fun mixed(raw: String): String {
        val text=raw.trim()
        if(' ' in text) {
            val parts=text.split(Regex("\\s+"));require(parts.size==2)
            val whole=parts[0].toLong();val fraction=parts[1].split('/');require(fraction.size==2)
            val denominator=fraction[1].toLong();val numerator=abs(whole)*denominator+fraction[0].toLong()
            return "${if(whole<0) -numerator else numerator}/$denominator"
        }
        val pieces=text.split('/');require(pieces.size==2)
        val numerator=pieces[0].toLong();val denominator=pieces[1].toLong().also { require(it!=0L) }
        return "${numerator/denominator} ${abs(numerator%denominator)}/$denominator"
    }
    private fun scientific(raw: String): String {
        val value=value(raw,CalculatorAngleMode.RADIANS)
        return "%.10e = %s".format(value,number(value))
    }
    private fun derivative(raw: String, mode: CalculatorAngleMode): String {
        val p=semi(raw,2);val f=GraphExpression.compile(p[0],mode);val x=value(p[1],mode);val h=max(1e-6,abs(x)*1e-6)
        return "f′(${number(x)}) = ${number((f.evaluate(x+h)-f.evaluate(x-h))/(2*h))}"
    }
    private fun integral(raw: String, mode: CalculatorAngleMode): String {
        val p=semi(raw,3);val f=GraphExpression.compile(p[0],mode);val a=value(p[1],mode);val b=value(p[2],mode)
        val steps=4000;val h=(b-a)/steps;var total=f.evaluate(a)+f.evaluate(b)
        for(i in 1 until steps) total+=(if(i%2==0) 2 else 4)*f.evaluate(a+i*h)
        return "∫ = ${number(total*h/3)}"
    }
    private fun optimize(raw: String, mode: CalculatorAngleMode, kind: String): String {
        val p=semi(raw,3);val f=GraphExpression.compile(p[0],mode);var left=value(p[1],mode);var right=value(p[2],mode);require(right>left)
        if(kind=="zero") {
            var fl=f.evaluate(left);var bracket=false
            for(i in 1..1000) { val x=left+(right-left)*i/1000;val fx=f.evaluate(x);if(fl*fx<=0) { right=x;left=x-(right-left)/i;bracket=true;break };fl=fx }
            require(bracket) { "No zero was found in that interval." }
            repeat(80) { val mid=(left+right)/2;if(f.evaluate(left)*f.evaluate(mid)<=0) right=mid else left=mid }
            val x=(left+right)/2;return "x = ${number(x)}, y = ${number(f.evaluate(x))}"
        }
        val maximize=kind=="max"
        repeat(100) { val m1=left+(right-left)/3;val m2=right-(right-left)/3;val chooseLeft=if(maximize) f.evaluate(m1)<f.evaluate(m2) else f.evaluate(m1)>f.evaluate(m2);if(chooseLeft) left=m1 else right=m2 }
        val x=(left+right)/2;return "x = ${number(x)}, y = ${number(f.evaluate(x))}"
    }
    private fun intersection(raw: String, mode: CalculatorAngleMode): String {
        val p=semi(raw,4);return optimize("(${p[0]})-(${p[1]});${p[2]};${p[3]}",mode,"zero")
    }
    private fun limit(raw: String, mode: CalculatorAngleMode): String {
        val p=semi(raw,2);val f=GraphExpression.compile(p[0],mode);val x=value(p[1],mode)
        val rows=(1..6).map { 10.0.pow(-it) }.joinToString("\n") { h -> "h=${number(h)}: left ${number(f.evaluate(x-h))}, right ${number(f.evaluate(x+h))}" }
        return rows
    }
    private fun complexArithmetic(raw: String): String {
        val match=Regex("\\(?\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*\\)?\\s*([+\\-*/^])\\s*\\(?\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*\\)?").matchEntire(raw.trim()) ?: error("Use (real,imaginary) operator (real,imaginary).")
        val a=Complex(match.groupValues[1].toDouble(),match.groupValues[2].toDouble());val b=Complex(match.groupValues[4].toDouble(),match.groupValues[5].toDouble())
        val result=when(match.groupValues[3]) { "+"->a.add(b);"-"->a.subtract(b);"*"->a.multiply(b);"/"->a.divide(b);else->a.pow(b) }
        return complex(result)
    }
    private fun complexDetails(raw: String): String { val z=complexValue(raw);return "z = ${complex(z)}\nreal = ${number(z.real)}\nimaginary = ${number(z.imaginary)}\nconjugate = ${complex(z.conjugate())}\nmagnitude = ${number(z.abs())}\nangle = ${number(z.argument)} rad\npolar = ${number(z.abs())} ∠ ${number(z.argument)}" }
    private fun complexPower(raw: String): String { val p=raw.split(';');require(p.size==2);val z=complexValue(p[0]);val n=p[1].trim().toInt();require(n!=0);val powered=z.pow(n.toDouble());val roots=(0 until abs(n)).joinToString("\n") { k -> complex(Complex(z.abs().pow(1.0/abs(n)),(z.argument+2*PI*k)/abs(n))) };return "power = ${complex(powered)}\nroots:\n$roots" }
    private fun complexValue(raw: String)=raw.trim().removePrefix("(").removeSuffix(")").split(',').let { require(it.size==2);Complex(it[0].trim().toDouble(),it[1].trim().toDouble()) }
    private fun complex(z: Complex)="${number(z.real)} ${if(z.imaginary<0) "−" else "+"} ${number(abs(z.imaginary))}i"

    private fun matrixAdd(raw: String): String { val p=raw.split('|');require(p.size==3);val a=matrix(p[0]);val b=matrix(p[1]);return matrix(if(p[2].trim()=="-") a.subtract(b) else a.add(b)) }
    private fun matrixBinary(raw: String, op: (Array2DRowRealMatrix,Array2DRowRealMatrix)->Array2DRowRealMatrix): String { val p=raw.split('|');require(p.size==2);return matrix(op(matrix(p[0]),matrix(p[1]))) }
    private fun matrixScalar(raw: String): String { val p=raw.split('|');require(p.size==2);return matrix(matrix(p[0]).scalarMultiply(p[1].trim().toDouble())) }
    private fun matrixPower(raw: String): String { val p=raw.split('|');require(p.size==2);val exponent=p[1].trim().toInt();require(exponent>=0);return matrix(matrix(p[0]).power(exponent)) }
    private fun matrixRowOperation(raw: String): String {
        val p=raw.split('|');require(p.size==2);val source=matrix(p[0]);val data=source.data.map { it.copyOf() }.toTypedArray();val command=p[1].trim().split(Regex("\\s+"))
        when(command.firstOrNull()?.lowercase()) {
            "swap" -> { require(command.size==3);val a=command[1].toInt()-1;val b=command[2].toInt()-1;val temp=data[a];data[a]=data[b];data[b]=temp }
            "scale" -> { require(command.size==3);val row=command[1].toInt()-1;val factor=command[2].toDouble();data[row].indices.forEach { data[row][it]*=factor } }
            "add" -> { require(command.size==4);val sourceRow=command[1].toInt()-1;val targetRow=command[2].toInt()-1;val factor=command[3].toDouble();data[targetRow].indices.forEach { data[targetRow][it]+=factor*data[sourceRow][it] } }
            else -> error("Use swap row row, scale row factor, or add source target factor.")
        }
        return matrix(Array2DRowRealMatrix(data))
    }
    private fun matrix(raw: String): Array2DRowRealMatrix { val rows=raw.trim().split(';').map { row -> row.split(',').map { it.trim().toDouble() }.toDoubleArray() };require(rows.isNotEmpty() && rows.all { it.size==rows[0].size });return Array2DRowRealMatrix(rows.toTypedArray()) }
    private fun matrix(value: org.apache.commons.math3.linear.RealMatrix)=value.data.joinToString("\n") { row -> row.joinToString(prefix="[ ",postfix=" ]") { number(it) } }
    private fun rref(source: Array2DRowRealMatrix): Array2DRowRealMatrix { val a=source.data.map { it.copyOf() }.toTypedArray();var lead=0;for(r in a.indices) { if(lead>=a[0].size) break;var i=r;while(abs(a[i][lead])<1e-10) { i++;if(i==a.size) { i=r;lead++;if(lead==a[0].size) return Array2DRowRealMatrix(a) } };val temp=a[i];a[i]=a[r];a[r]=temp;val divisor=a[r][lead];for(j in a[r].indices) a[r][j]/=divisor;for(k in a.indices) if(k!=r) { val factor=a[k][lead];for(j in a[k].indices) a[k][j]-=factor*a[r][j] };lead++ };return Array2DRowRealMatrix(a) }

    private fun oneVariable(raw: String): String { val v=list(raw);val d=DescriptiveStatistics(v.toDoubleArray());val modes=StatUtils.mode(v.toDoubleArray()).joinToString { number(it) };return "n = ${v.size}\nsum = ${number(v.sum())}\nsum of squares = ${number(v.sumOf { it*it })}\nmean = ${number(d.mean)}\nmedian = ${number(d.getPercentile(50.0))}\nmode = ${if(modes.isBlank()) "none" else modes}\nmin = ${number(d.min)}\nQ1 = ${number(d.getPercentile(25.0))}\nQ3 = ${number(d.getPercentile(75.0))}\nmax = ${number(d.max)}\nsample SD = ${number(d.standardDeviation)}\nvariance = ${number(d.variance)}" }
    private fun twoVariable(raw: String): String { val p=raw.split('|');require(p.size==2);val x=list(p[0]);val y=list(p[1]);require(x.size==y.size);val regression=SimpleRegression(true);x.indices.forEach { regression.addData(x[it],y[it]) };return "correlation = ${number(PearsonsCorrelation().correlation(x.toDoubleArray(),y.toDoubleArray()))}\ny = ${number(regression.intercept)} + ${number(regression.slope)}x\nR² = ${number(regression.rSquare)}" }
    private fun polynomialRegression(raw: String): String { val p=raw.split('|');require(p.size==3);val degree=p[0].filter { it.isDigit() }.toInt();require(degree in 1..4);val x=list(p[1]);val y=list(p[2]);require(x.size==y.size && x.size>degree);val design=Array(x.size) { row -> DoubleArray(degree+1) { col -> x[row].pow(col) } };val coefficients=org.apache.commons.math3.linear.QRDecomposition(Array2DRowRealMatrix(design)).solver.solve(org.apache.commons.math3.linear.ArrayRealVector(y.toDoubleArray())).toArray();return coefficients.indices.joinToString(" + ") { index -> "${number(coefficients[index])}${if(index==0) "" else "x^$index"}" } }
    private fun regression(raw: String): String { val p=raw.split('|');require(p.size==3);val kind=p[0].trim().lowercase();val x=list(p[1]);val y=list(p[2]);require(x.size==y.size);return when(kind) { "linear" -> twoVariable("${p[1]}|${p[2]}");"median-median" -> medianMedian(x,y);"quadratic"->polynomialRegression("degree 2|${p[1]}|${p[2]}");"cubic"->polynomialRegression("degree 3|${p[1]}|${p[2]}");"quartic"->polynomialRegression("degree 4|${p[1]}|${p[2]}");"exponential" -> transformedRegression(x,y.map { ln(it) },"y = %s e^(%s x)",true);"logarithmic" -> transformedRegression(x.map { ln(it) },y,"y = %s + %s ln(x)",false);"power" -> transformedRegression(x.map { ln(it) },y.map { ln(it) },"y = %s x^(%s)",true);"logistic" -> nonlinearRegression(x,y,doubleArrayOf(y.max(),1.0,1.0,x.average())) { t,a -> a[0]/(1+a[1]*exp(-a[2]*(t-a[3]))) };"sinusoidal" -> nonlinearRegression(x,y,doubleArrayOf((y.max()-y.min())/2,1.0,0.0,y.average())) { t,a -> a[0]*sin(a[1]*t+a[2])+a[3] };else -> error("Use linear, quadratic, cubic, quartic, exponential, logarithmic, power, logistic, sinusoidal, or median-median.") } }
    private fun transformedRegression(x: List<Double>, y: List<Double>, format: String, exponentiateIntercept: Boolean): String { val r=SimpleRegression(true);x.indices.forEach { r.addData(x[it],y[it]) };val intercept=if(exponentiateIntercept) exp(r.intercept) else r.intercept;return format.format(number(intercept),number(r.slope))+"\nR² = ${number(r.rSquare)}" }
    private fun nonlinearRegression(x: List<Double>,y: List<Double>,guess: DoubleArray,function: (Double,DoubleArray)->Double): String {
        val model=object: org.apache.commons.math3.analysis.ParametricUnivariateFunction {
            override fun value(t: Double, parameters: DoubleArray)=function(t,parameters)
            override fun gradient(t: Double, parameters: DoubleArray): DoubleArray {
                val h=1e-6
                return parameters.indices.map { index -> val upper=parameters.copyOf();val lower=parameters.copyOf();upper[index]+=h;lower[index]-=h;(function(t,upper)-function(t,lower))/(2*h) }.toDoubleArray()
            }
        }
        val points=x.indices.map { WeightedObservedPoint(1.0,x[it],y[it]) }
        val fitted=SimpleCurveFitter.create(model,guess).withMaxIterations(2000).fit(points)
        return fitted.mapIndexed { index,value -> "p${index+1} = ${number(value)}" }.joinToString("\n")
    }
    private fun medianMedian(x: List<Double>,y: List<Double>): String {
        val pairs=x.zip(y).sortedBy { it.first };val groups=pairs.chunked(ceil(pairs.size/3.0).toInt())
        require(groups.size>=2)
        val medians=groups.map { group -> group.map { it.first }.sorted().let { it[it.size/2] } to group.map { it.second }.sorted().let { it[it.size/2] } }
        val slope=(medians.last().second-medians.first().second)/(medians.last().first-medians.first().first)
        val intercept=medians.map { it.second-slope*it.first }.average()
        return "y = ${number(intercept)} + ${number(slope)}x"
    }

    private fun semi(raw: String, size: Int)=raw.split(';').also { require(it.size==size) { "Use semicolons exactly like the example." } }.map { it.trim() }
    private fun list(raw: String)=raw.trim().split(Regex("[,\\s]+")).filter { it.isNotBlank() }.map { it.toDouble() }.also { require(it.isNotEmpty()) }
    private fun value(raw: String, mode: CalculatorAngleMode)=GraphExpression.compile(raw,mode).evaluate(0.0)
    private fun number(value: Double): String { require(value.isFinite()) { "The result is not a finite number." };val rounded=round(value);return if(abs(value-rounded)<1e-10 && abs(rounded)<9e15) rounded.toLong().toString() else "%.10g".format(value) }
    private fun approximateFraction(value: Double): Pair<Long,Long> { var bestN=0L;var bestD=1L;var bestError=Double.MAX_VALUE;for(d in 1L..10000L) { val n=round(value*d).toLong();val error=abs(value-n.toDouble()/d);if(error<bestError) { bestN=n;bestD=d;bestError=error };if(error<1e-10) break };val gcd=gcd(abs(bestN),bestD);return bestN/gcd to bestD/gcd }
    private tailrec fun gcd(a: Long,b: Long): Long=if(b==0L) max(1,a) else gcd(b,a%b)
}
