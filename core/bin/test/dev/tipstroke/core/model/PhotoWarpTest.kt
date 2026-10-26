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
}
