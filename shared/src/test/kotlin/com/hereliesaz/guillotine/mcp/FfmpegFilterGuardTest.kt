package com.hereliesaz.guillotine.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegFilterGuardTest {

    @Test fun parsesNamesStrippingLabelsAndInstances() {
        assertEquals(
            listOf("split", "gblur", "blend"),
            FfmpegFilterGuard.filterNames("split=2[a][b];[b]gblur@g1=sigma=10[bl];[a][bl]blend=all_mode=screen"),
        )
        assertEquals(listOf("hflip", "vflip"), FfmpegFilterGuard.filterNames("hflip,vflip"))
    }

    @Test fun allowsOrdinaryGraphs() {
        listOf(
            "unsharp=la=1.5", "hflip,vflip", "curves=preset=vintage", "curves=vintage", "eq=brightness=0.1",
            "drawtext=text='Hello, world':x=10:y=10", "scale=iw/2:ih/2", "[0:v]hue=s=0[out]",
        ).forEach { assertNull(it, FfmpegFilterGuard.check(it)) }
    }

    @Test fun everyCatalogEffectPasses() {
        for (name in VideoFilterCatalog.names) {
            val g = VideoFilterCatalog.graphFor(name, JSONObject())
            assertNull("$name: $g", FfmpegFilterGuard.check(g))
        }
    }

    @Test fun rejectsFileFilters() {
        listOf(
            "movie=/etc/passwd", "amovie=x.wav", "subtitles=a.srt", "ass=a.ass", "afir", "frei0r=x",
            "metadata=mode=print:file=/sdcard/x", "ametadata=print", "psnr=stats_file=/tmp/x", "ssim",
            "vidstabdetect=result=x.trf", "sendcmd=f=cmds.txt", "lut3d=x.cube", "haldclut",
            "hflip,movie=x", "[in]movie@m=x[out]", "nosuchfilter",
        ).forEach { assertNotNull(it, FfmpegFilterGuard.check(it)) }
    }

    @Test fun rejectsFileOptionKeys() {
        listOf(
            "drawtext=textfile=/etc/passwd", "drawtext=fontfile=/x.ttf:text=a", "curves=psfile=/x.acv",
            "eq=file=x", "scale=filename=x", "noise=f=x", "drawtext=a:b:/etc/passwd", "curves=vintage:x:y",
        ).forEach { assertNotNull(it, FfmpegFilterGuard.check(it)) }
    }

    @Test fun quotingCannotHideASecondFilter() {
        // The quoted comma stays inside drawtext's text; the unquoted one starts a real (forbidden) filter.
        assertNull(FfmpegFilterGuard.check("drawtext=text='a,movie=x'"))
        assertNotNull(FfmpegFilterGuard.check("drawtext=text='a',movie=x"))
        assertNotNull(FfmpegFilterGuard.check("drawtext=text='unterminated"))
        assertNotNull(FfmpegFilterGuard.check(""))
    }

    @Test fun xfadeTransitions() {
        assertTrue(FfmpegFilterGuard.isValidXfadeTransition("fade"))
        assertTrue(FfmpegFilterGuard.isValidXfadeTransition("wipeleft"))
        assertTrue(FfmpegFilterGuard.isValidXfadeTransition("revealdown"))
        assertFalse(FfmpegFilterGuard.isValidXfadeTransition("custom"))
        assertFalse(FfmpegFilterGuard.isValidXfadeTransition("fade:offset=0,movie=x"))
        assertFalse(FfmpegFilterGuard.isValidXfadeTransition("FADE"))
        assertFalse(FfmpegFilterGuard.isValidXfadeTransition(""))
    }
}
