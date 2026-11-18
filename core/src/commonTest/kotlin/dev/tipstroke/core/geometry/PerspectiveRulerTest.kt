package dev.tipstroke.core.geometry
import kotlin.test.*
class PerspectiveRulerTest {
    @Test fun perspectiveLocksToOneGuideEvenWhenPointerCrossesAnother() {
        val ruler=MagneticRuler(PerspectiveRuler(PerspectiveMode.ONE_POINT,Point(100f,100f),Point(200f,100f)),4f)
        assertEquals(Point(0f,0f),ruler.project(Point(0f,0f)))
        assertEquals(Point(0f,0f),ruler.project(Point(1f,2f)))
        val first=ruler.project(Point(20f,18f)); assertEquals(first.x,first.y,.001f)
        val next=ruler.project(Point(90f,5f)); assertEquals(next.x,next.y,.001f)
    }
    @Test fun twoPointKeepsVerticalsAndOffDoesNotSnap() {
        val config=PerspectiveRuler(PerspectiveMode.TWO_POINT,Point(-100f,0f),Point(100f,0f))
        val ruler=MagneticRuler(config,2f); ruler.project(Point(0f,50f))
        assertEquals(Point(0f,80f),ruler.project(Point(1f,80f)))
        assertEquals(Point(1f,3f),MagneticRuler(config.copy(mode=PerspectiveMode.OFF),2f).project(Point(1f,3f)))
    }
}
