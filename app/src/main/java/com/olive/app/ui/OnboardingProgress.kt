package com.olive.app.ui


import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// Numbered olives hang from the branch and mark each onboarding page.
@Composable internal fun OnboardingProgress(completed:Int,total:Int,page:Int,pages:Int,pageComplete:List<Boolean>) {
    if(pages<=1) return
    val layout=remember(pages) { OnboardingBranchLayout(pages) }
    val naturalStepOffsets=layout.naturalStepOffsets
    val sprigs=layout.sprigs
    Column(verticalArrangement=Arrangement.spacedBy(2.dp),modifier=Modifier.fillMaxWidth().testTag("onboarding_progress")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Stretch only the stem and distribute fruit along it; artwork stays at its original size.
            val availableSpan=(maxWidth.value-72f).coerceAtLeast(0f)
            val stepOffsets=naturalStepOffsets.map { it/naturalStepOffsets.last()*availableSpan }
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val inset=24.dp.toPx()
                    val middle=size.height/2
                    drawLine(OliveBrown.copy(alpha=.7f),Offset(5.dp.toPx(),middle),
                        Offset(inset,middle),2.dp.toPx(),StrokeCap.Round)
                    repeat(pages-1) { index ->
                        val start=inset+stepOffsets[index].dp.toPx()
                        val spacing=(stepOffsets[index+1]-stepOffsets[index]).dp.toPx()
                        val end=start+spacing
                        val grown=index<page
                        val stemColor=if(grown) OliveBrown else OliveBrown.copy(alpha=.7f)
                        val leafColor=if(grown) MossGreen else SageGreen
                        val stem=Path().apply {
                            moveTo(start,middle)
                            cubicTo(start+spacing*.3f,middle-7.dp.toPx(),
                                start+spacing*.7f,middle+7.dp.toPx(),end,middle)
                        }
                        drawPath(stem,stemColor,style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
                        sprigs[index].forEach { sprig ->
                            val fraction=sprig.start
                            val bend=3*fraction*(1-fraction)*(1-2*fraction)*(-7.dp.toPx())
                            drawOnboardingSprig(Offset(start+spacing*fraction,middle+bend),
                                (naturalStepOffsets[index+1]-naturalStepOffsets[index]).dp.toPx(),sprig,stemColor,leafColor)
                        }
                    }
                    val tipStart=inset+stepOffsets.last().dp.toPx()
                    val tip=Path().apply {
                        moveTo(tipStart,middle)
                        cubicTo(tipStart+12.dp.toPx(),middle+2.dp.toPx(),
                            size.width-12.dp.toPx(),middle-6.dp.toPx(),size.width-3.dp.toPx(),middle-12.dp.toPx())
                    }
                    drawPath(tip,OliveBrown.copy(alpha=.7f),style=Stroke(1.3.dp.toPx(),cap=StrokeCap.Round))
                    drawOnboardingLeaf(Offset(size.width-20.dp.toPx(),middle-4.dp.toPx()),
                        Offset(size.width-5.dp.toPx(),middle-26.dp.toPx()),SageGreen)
                    // Short curved stalks attach each numbered olive to the main branch.
                    repeat(pages) { index ->
                        val center=inset+stepOffsets[index].dp.toPx()
                        val side=if(index%2==0) 1f else -1f
                        val stalk=Path().apply {
                            moveTo(center+10.dp.toPx(),middle)
                            cubicTo(center+4.dp.toPx(),middle+side*5.dp.toPx(),
                                center-6.dp.toPx(),middle+side*10.dp.toPx(),
                                center-6.dp.toPx(),middle+side*16.dp.toPx())
                        }
                        drawPath(stalk,OliveBrown,style=Stroke(1.5.dp.toPx(),cap=StrokeCap.Round))
                    }
                }
                repeat(pages) { index ->
                    val done=index<page || (index==page && pageComplete.getOrElse(index) { false })
                    val active=index==page
                    OnboardingOlive(index,done,active,stepOffsets[index])
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            LocalizedText("Step ${page+1} of $pages",style=MaterialTheme.typography.labelLarge)
            LocalizedText("$completed of $total required",style=MaterialTheme.typography.labelMedium,color=Muted)
        }
    }
}

// Offshoots and leaf tips lean right, giving the branch a clear direction of growth.
private fun DrawScope.drawOnboardingSprig(base:Offset,spacing:Float,sprig:OnboardingSprig,stemColor:Color,leafColor:Color) {
    val reach=spacing*sprig.reach
    val rise=sprig.rise.dp.toPx()
    fun point(x:Float,y:Float)=base+Offset(reach*x,rise*y)
    val tip=point(1f,1f)
    val sprigPath=Path().apply {
        moveTo(base.x,base.y)
        val first=point(.38f,.08f)
        val second=point(.55f,1.05f)
        cubicTo(first.x,first.y,second.x,second.y,tip.x,tip.y)
    }
    drawPath(sprigPath,stemColor,style=Stroke(1.dp.toPx(),cap=StrokeCap.Round))
    drawOnboardingLeaf(point(.3f,.42f),point(.55f+sprig.lean,1.3f),leafColor)
    drawOnboardingLeaf(point(.58f,.83f),point(1.03f,.96f+sprig.lean),leafColor)
    if(sprig.leafCount==3) drawOnboardingLeaf(point(.74f,.95f),point(.94f+sprig.lean,.35f),leafColor)
}

// Two curved edges form a narrow olive leaf; its vein points back to the stem.
private fun DrawScope.drawOnboardingLeaf(base:Offset,tip:Offset,color:Color) {
    val direction=tip-base
    val side=Offset(-direction.y,direction.x)/direction.getDistance()*4.dp.toPx()
    val leaf=Path().apply {
        moveTo(base.x,base.y)
        val first=base+direction*.35f+side
        val second=base+direction*.75f+side
        cubicTo(first.x,first.y,second.x,second.y,tip.x,tip.y)
        val third=base+direction*.75f-side
        val fourth=base+direction*.35f-side
        cubicTo(third.x,third.y,fourth.x,fourth.y,base.x,base.y)
        close()
    }
    drawPath(leaf,color)
    drawLine(OliveGreen.copy(alpha=.35f),base,base+direction*.8f,.6.dp.toPx(),StrokeCap.Round)
}
