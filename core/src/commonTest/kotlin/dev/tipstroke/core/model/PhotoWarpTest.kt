package dev.tipstroke.core.model

import dev.tipstroke.core.geometry.Point
import kotlin.test.*

class PhotoWarpTest {
    @Test fun identityAndInverseIncludeEdges() {
        val mesh=PhotoWarp.identity()
        for(p in listOf(Point(0f,0f),Point(1f,1f),Point(.23f,.71f))) {
            val mapped=mesh.map(p.x,p.y)
            assertEquals(p.x,mapped.x,.00001f);assertEquals(p.y,mapped.y,.00001f)
            val inverse=assertNotNull(mesh.inverse(mapped.x,mapped.y))
            assertEquals(p.x,inverse.x,.00001f);assertEquals(p.y,inverse.y,.00001f)
        }
        assertNull(mesh.inverse(-.1f,.5f))
    }
    @Test fun pushIsLocalAndReversibleByInverseMapping() {
        val mesh=PhotoWarp.identity().deform(PhotoEditTool.PUSH,Point(.5f,.5f),Point(.03f,.01f),.3f,1f)
        assertEquals(Point(0f,0f),mesh.points.first())
        val mapped=mesh.map(.5f,.5f)
        assertTrue(mapped.x>.5f)
        val inverse=assertNotNull(mesh.inverse(mapped.x,mapped.y))
        assertEquals(.5f,inverse.x,.00001f); assertEquals(.5f,inverse.y,.00001f)
    }
    @Test fun bulgeEnlargesAndPinchShrinksNearbyFeatures() {
        val mesh=PhotoWarp.identity()
        val center=Point(.5f,.5f);val delta=Point(.02f,0f)
        assertTrue(mesh.deform(PhotoEditTool.BULGE,center,delta,.5f,1f).map(.625f,.5f).x>.625f)
        assertTrue(mesh.deform(PhotoEditTool.PINCH,center,delta,.5f,1f).map(.625f,.5f).x<.625f)
    }
    @Test fun extremePushCannotFoldMesh() {
        val mesh=PhotoWarp.identity()
        assertEquals(mesh,mesh.deform(PhotoEditTool.PUSH,Point(.5f,.5f),Point(10f,0f),.2f,1f))
    }
    @Test fun advancedControlsMoveOnlyTheirNeighborhoodAndRejectFoldovers() {
        for (divisions in listOf(2,4,8)) {
            val mesh=PhotoWarp.identity()
            val edited=mesh.moveControl(divisions/2,divisions/2,divisions,Point(.02f,.01f))
            assertEquals(.52f,edited.map(.5f,.5f).x,.00001f)
            assertEquals(.51f,edited.map(.5f,.5f).y,.00001f)
            assertEquals(Point(0f,0f),edited.map(0f,0f))
            val inverse=assertNotNull(edited.inverse(.52f,.51f))
            assertEquals(.5f,inverse.x,.00001f)
            assertEquals(mesh,mesh.moveControl(divisions/2,divisions/2,divisions,Point(10f,0f)))
        }
    }
    @Test fun perspectiveCornerKeepsOtherCornersAndStraightLines() {
        val mesh=PhotoWarp.identity().distortCorner(0,0,Point(.15f,.1f))
        assertEquals(.15f,mesh.map(0f,0f).x,.00001f)
        assertEquals(Point(1f,1f),mesh.map(1f,1f))
        val a=mesh.map(.25f,0f); val b=mesh.map(.25f,1f); val middle=mesh.map(.25f,.5f)
        assertEquals(0f,(middle.x-a.x)*(b.y-a.y)-(middle.y-a.y)*(b.x-a.x),.00001f)
        assertEquals(PhotoWarp.identity(),PhotoWarp.identity().distortCorner(0,0,Point(2f,2f)))
    }
}
