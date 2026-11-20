package dev.tipstroke.core.editor

import kotlin.test.*

class NativeVideoPlanTest {
    @Test fun crossfadeInterpolatesTheIncomingSourceAtItsOwnTime() {
        val plan = NativeVideoPlan.decode("""[
            {"id":"a","assetID":"first","name":"A","sourceInUs":1000,"durationUs":8000,"transitionUs":0,"keyframes":[]},
            {"id":"b","assetID":"second","name":"B","sourceInUs":2000,"durationUs":8000,"transitionUs":2000,
             "keyframes":[{"timeUs":0,"x":0,"y":0,"scale":1,"rotation":0,"opacity":1,"easing":"LINEAR"},
                          {"timeUs":2000,"x":1,"y":0,"scale":1,"rotation":0,"opacity":1,"easing":"HOLD"}]}
        ]""")
        assertEquals(14000L, plan.durationUs)
        val (outgoing, incoming) = plan.samples(7000)
        assertEquals(8000L, outgoing.sourceUs)
        assertEquals(3000L, incoming.sourceUs)
        assertEquals(.5f, incoming.weight)
        assertEquals(.5f, incoming.pose.x)
    }
}
