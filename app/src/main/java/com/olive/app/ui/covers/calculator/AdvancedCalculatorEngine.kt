package com.olive.app.ui.covers.calculator
import com.olive.app.ui.requireText
import com.olive.app.ui.ResourceFailure
import com.olive.app.R

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
internal enum class AdvancedTool(val category: Int, val title: Int, val example: Int) {
    Expression(R.string.ui_basic, R.string.ui_evaluate_expression, R.string.ui_sqrt_81_5_or_ncr_10_3),
    FunctionValue(R.string.ui_algebra, R.string.ui_function_value, R.string.ui_x_2_2x_3),
    LinearSystem(R.string.ui_algebra, R.string.ui_system_of_equations, R.string.ui_1_1_5_2_1_1),
    Fraction(R.string.ui_basic, R.string.ui_fraction_decimal, R.string.ui_1_25_or_7_4),
    MixedFraction(R.string.ui_basic, R.string.ui_mixed_improper_fraction, R.string.ui_2_1_3_or_7_3),
    Scientific(R.string.ui_basic, R.string.ui_scientific_notation, R.string.ui_1_2e5_or_1_2_10_5),
    Derivative(R.string.ui_calculus, R.string.ui_numerical_derivative, R.string.ui_x_3_2x_2),
    Integral(R.string.ui_calculus, R.string.ui_definite_integral, R.string.ui_sin_x_0_pi),
    Zero(R.string.ui_calculus, R.string.ui_find_zero, R.string.ui_x_2_4_0_5),
    Minimum(R.string.ui_calculus, R.string.ui_find_minimum, R.string.ui_x_2_4x_10_10),
    Maximum(R.string.ui_calculus, R.string.ui_find_maximum, R.string.ui_x_2_4x_10_10_be0ef3),
    Intersection(R.string.ui_calculus, R.string.ui_find_intersection, R.string.ui_x_2_2x_3_10_10),
    Limit(R.string.ui_calculus, R.string.ui_investigate_limit, R.string.ui_sin_x_x_0),
    ComplexMath(R.string.ui_complex, R.string.ui_complex_arithmetic, R.string.ui_2_3_4_1),
    ComplexDetails(R.string.ui_complex, R.string.ui_complex_details, R.string.ui_3_4),
    ComplexPower(R.string.ui_complex, R.string.ui_complex_power_roots, R.string.ui_3_4_2),
    MatrixAdd(R.string.ui_matrices, R.string.ui_matrix_add_subtract, R.string.ui_1_2_3_4_5_6_7_8),
    MatrixMultiply(R.string.ui_matrices, R.string.ui_matrix_multiply, R.string.ui_1_2_3_4_5_6_7_8_9ad55f),
    MatrixScalar(R.string.ui_matrices, R.string.ui_scalar_multiplication, R.string.ui_1_2_3_4_3),
    MatrixPower(R.string.ui_matrices, R.string.ui_matrix_power, R.string.ui_1_2_3_4_3),
    MatrixInverse(R.string.ui_matrices, R.string.ui_inverse, R.string.ui_1_2_3_4),
    MatrixDeterminant(R.string.ui_matrices, R.string.ui_determinant, R.string.ui_1_2_3_4),
    MatrixTranspose(R.string.ui_matrices, R.string.ui_transpose, R.string.ui_1_2_3_4_5_6),
    MatrixIdentity(R.string.ui_matrices, R.string.ui_identity_matrix, R.string.ui_3),
    MatrixReducedRowEchelonForm(R.string.ui_matrices, R.string.ui_rref_augmented_matrix, R.string.ui_1_2_5_3_4_11),
    MatrixRowOperation(R.string.ui_matrices, R.string.ui_row_operation, R.string.ui_1_2_3_4_swap_1_2),
    OneVariableStats(R.string.ui_statistics, R.string.ui_one_variable_statistics, R.string.ui_1_2_2_4_8),
    TwoVariableStats(R.string.ui_statistics, R.string.ui_two_variable_statistics, R.string.ui_1_2_3_2_4_5),
    PolynomialRegression(R.string.ui_statistics, R.string.ui_polynomial_regression, R.string.ui_degree_2_1_2_3_2_5_10),
    Regression(R.string.ui_statistics, R.string.ui_other_regression, R.string.ui_exponential_1_2_3_2_4_8)
}

// All advanced calculations stay local on the phone. Semicolons separate expression arguments and pipes separate data sets.
internal object AdvancedCalculatorEngine {
    fun calculate(tool: AdvancedTool, rawInput: String, mode: CalculatorAngleMode,text:com.olive.app.ui.ResourceText): String {
        val input=normalizeCalculatorNotation(rawInput)
        return when(tool) {
        AdvancedTool.Expression -> number(GraphExpression.compile(input,mode).evaluate(0.0))
        AdvancedTool.FunctionValue -> semi(input,2).let { text(R.string.ui_value_value_0c813d , number(GraphExpression.compile(it[0],mode).evaluate(value(it[1],mode)))) }
        AdvancedTool.LinearSystem -> matrix(reducedRowEchelonForm(matrix(input)))
        AdvancedTool.Fraction -> fraction(input)
        AdvancedTool.MixedFraction -> mixed(input)
        AdvancedTool.Scientific -> scientific(input)
        AdvancedTool.Derivative -> derivative(input,mode)
        AdvancedTool.Integral -> integral(input,mode)
        AdvancedTool.Zero -> optimize(input,mode,"zero")
        AdvancedTool.Minimum -> optimize(input,mode,"min")
        AdvancedTool.Maximum -> optimize(input,mode,"max")
        AdvancedTool.Intersection -> intersection(input,mode)
        AdvancedTool.Limit -> limit(input,mode,text)
        AdvancedTool.ComplexMath -> complexArithmetic(input)
        AdvancedTool.ComplexDetails -> complexDetails(input,text)
        AdvancedTool.ComplexPower -> complexPower(input,text)
        AdvancedTool.MatrixAdd -> matrixAdd(input)
        AdvancedTool.MatrixMultiply -> matrixBinary(input) { a,b -> a.multiply(b) }
        AdvancedTool.MatrixScalar -> matrixScalar(input)
        AdvancedTool.MatrixPower -> matrixPower(input)
        AdvancedTool.MatrixInverse -> matrix(LUDecomposition(matrix(input)).solver.inverse)
        AdvancedTool.MatrixDeterminant -> number(LUDecomposition(matrix(input)).determinant)
        AdvancedTool.MatrixTranspose -> matrix(matrix(input).transpose())
        AdvancedTool.MatrixIdentity -> matrix(MatrixUtils.createRealIdentityMatrix(input.trim().toInt().also { require(it in 1..10) }))
        AdvancedTool.MatrixReducedRowEchelonForm -> matrix(reducedRowEchelonForm(matrix(input)))
        AdvancedTool.MatrixRowOperation -> matrixRowOperation(input)
        AdvancedTool.OneVariableStats -> oneVariable(input,text)
        AdvancedTool.TwoVariableStats -> twoVariable(input,text)
        AdvancedTool.PolynomialRegression -> polynomialRegression(input)
        AdvancedTool.Regression -> regression(input,text)
    }
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
        return formatScientificCalculatorNumber(value)+" = "+number(value)
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
            requireText(bracket) { ResourceFailure(R.string.ui_no_zero_was_found_in_that_interval) }
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
    private fun limit(raw: String, mode: CalculatorAngleMode, text:com.olive.app.ui.ResourceText): String {
        val p=semi(raw,2);val f=GraphExpression.compile(p[0],mode);val x=value(p[1],mode)
        val rows=(1..6).map { 10.0.pow(-it) }.joinToString("\n") { h -> text(R.string.ui_h_value_left_value_right_value , number(h), number(f.evaluate(x-h)), number(f.evaluate(x+h))) }
        return rows
    }
    private fun complexArithmetic(raw: String): String {
        val match=Regex("\\(?\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*\\)?\\s*([+\\-*/^])\\s*\\(?\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*\\)?").matchEntire(raw.trim()) ?: error("Use (real,imaginary) operator (real,imaginary).")
        val a=Complex(match.groupValues[1].toDouble(),match.groupValues[2].toDouble());val b=Complex(match.groupValues[4].toDouble(),match.groupValues[5].toDouble())
        val result=when(match.groupValues[3]) { "+"->a.add(b);"-"->a.subtract(b);"*"->a.multiply(b);"/"->a.divide(b);else->a.pow(b) }
        return complex(result)
    }
    private fun complexDetails(raw: String, text:com.olive.app.ui.ResourceText): String { val z=complexValue(raw);return text(R.string.ui_z_value_real_value_imaginary_value_conjugate_value_magnitude_valu , complex(z), number(z.real), number(z.imaginary), complex(z.conjugate()), number(z.abs()), number(z.argument), number(z.abs()), number(z.argument)) }
    private fun complexPower(raw: String, text:com.olive.app.ui.ResourceText): String { val p=raw.split(';');require(p.size==2);val z=complexValue(p[0]);val n=p[1].trim().toInt();require(n!=0);val powered=z.pow(n.toDouble());val roots=(0 until abs(n)).joinToString("\n") { k -> complex(Complex(z.abs().pow(1.0/abs(n)),(z.argument+2*PI*k)/abs(n))) };return text(R.string.ui_power_value_roots_value , complex(powered), roots) }
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
    private fun reducedRowEchelonForm(source: Array2DRowRealMatrix): Array2DRowRealMatrix { val a=source.data.map { it.copyOf() }.toTypedArray();var lead=0;for(r in a.indices) { if(lead>=a[0].size) break;var i=r;while(abs(a[i][lead])<1e-10) { i++;if(i==a.size) { i=r;lead++;if(lead==a[0].size) return Array2DRowRealMatrix(a) } };val temp=a[i];a[i]=a[r];a[r]=temp;val divisor=a[r][lead];for(j in a[r].indices) a[r][j]/=divisor;for(k in a.indices) if(k!=r) { val factor=a[k][lead];for(j in a[k].indices) a[k][j]-=factor*a[r][j] };lead++ };return Array2DRowRealMatrix(a) }

    private fun oneVariable(raw: String, text:com.olive.app.ui.ResourceText): String { val v=list(raw);val d=DescriptiveStatistics(v.toDoubleArray());val modes=StatUtils.mode(v.toDoubleArray()).joinToString { number(it) };return text(R.string.ui_n_value_sum_value_sum_of_squares_value_mean_value_median_value_mo , v.size, number(v.sum()), number(v.sumOf { it*it }), number(d.mean), number(d.getPercentile(50.0)), modes.ifBlank { text(R.string.ui_none) }, number(d.min), number(d.getPercentile(25.0)), number(d.getPercentile(75.0)), number(d.max), number(d.standardDeviation), number(d.variance)) }
    private fun twoVariable(raw: String, text:com.olive.app.ui.ResourceText): String { val p=raw.split('|');require(p.size==2);val x=list(p[0]);val y=list(p[1]);require(x.size==y.size);val regression=SimpleRegression(true);x.indices.forEach { regression.addData(x[it],y[it]) };return text(R.string.ui_correlation_value_y_value_valuex_r_value , number(PearsonsCorrelation().correlation(x.toDoubleArray(),y.toDoubleArray())), number(regression.intercept), number(regression.slope), number(regression.rSquare)) }
    private fun polynomialRegression(raw: String): String { val p=raw.split('|');require(p.size==3);val degree=p[0].filter { it.isDigit() }.toInt();require(degree in 1..4);val x=list(p[1]);val y=list(p[2]);require(x.size==y.size && x.size>degree);val design=Array(x.size) { row -> DoubleArray(degree+1) { col -> x[row].pow(col) } };val coefficients=org.apache.commons.math3.linear.QRDecomposition(Array2DRowRealMatrix(design)).solver.solve(org.apache.commons.math3.linear.ArrayRealVector(y.toDoubleArray())).toArray();return coefficients.indices.joinToString(" + ") { index -> "${number(coefficients[index])}${if(index==0) "" else "x^$index"}" } }
    private fun regression(raw: String,text:com.olive.app.ui.ResourceText): String { val p=raw.split('|');require(p.size==3);val kind=p[0].trim().lowercase();val x=list(p[1]);val y=list(p[2]);require(x.size==y.size);return when(kind) { "linear" -> twoVariable("${p[1]}|${p[2]}",text);"median-median" -> medianMedian(x,y);"quadratic"->polynomialRegression("degree 2|${p[1]}|${p[2]}");"cubic"->polynomialRegression("degree 3|${p[1]}|${p[2]}");"quartic"->polynomialRegression("degree 4|${p[1]}|${p[2]}");"exponential" -> transformedRegression(x,y.map { ln(it) },"y = %s e^(%s x)",true);"logarithmic" -> transformedRegression(x.map { ln(it) },y,"y = %s + %s ln(x)",false);"power" -> transformedRegression(x.map { ln(it) },y.map { ln(it) },"y = %s x^(%s)",true);"logistic" -> nonlinearRegression(x,y,doubleArrayOf(y.max(),1.0,1.0,x.average())) { t,a -> a[0]/(1+a[1]*exp(-a[2]*(t-a[3]))) };"sinusoidal" -> nonlinearRegression(x,y,doubleArrayOf((y.max()-y.min())/2,1.0,0.0,y.average())) { t,a -> a[0]*sin(a[1]*t+a[2])+a[3] };else -> error("Use linear, quadratic, cubic, quartic, exponential, logarithmic, power, logistic, sinusoidal, or median-median.") } }
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

    private fun semi(raw: String, size: Int)=raw.split(';').also { requireText(it.size==size) { ResourceFailure(R.string.ui_use_semicolons_exactly_like_the_example) } }.map { it.trim() }
    private fun list(raw: String)=raw.trim().split(Regex("[,\\s]+")).filter { it.isNotBlank() }.map(::parseCalculatorNumber).also { require(it.isNotEmpty()) }
    private fun value(raw: String, mode: CalculatorAngleMode)=GraphExpression.compile(raw,mode).evaluate(0.0)
    private fun number(value: Double): String = formatCalculatorNumber(value)
    private fun approximateFraction(value: Double): Pair<Long,Long> { var bestN=0L;var bestD=1L;var bestError=Double.MAX_VALUE;for(d in 1L..10000L) { val n=round(value*d).toLong();val error=abs(value-n.toDouble()/d);if(error<bestError) { bestN=n;bestD=d;bestError=error };if(error<1e-10) break };val gcd=gcd(abs(bestN),bestD);return bestN/gcd to bestD/gcd }
    private tailrec fun gcd(a: Long,b: Long): Long=if(b==0L) max(1,a) else gcd(b,a%b)
}
