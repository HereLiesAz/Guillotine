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

    @Test fun positionalOptionsCannotReachFileOptions() {
        // deshake's 11th positional is `filename` (writes a log file).
        assertNotNull(FfmpegFilterGuard.check("deshake=0:0:-1:-1:16:16:0:8:125:1:/path/x"))
        // selectivecolor's 11th positional is `psfile` (reads a file).
        assertNotNull(FfmpegFilterGuard.check("selectivecolor=absolute:a:b:c:d:e:f:g:h:i:/etc/passwd"))
        assertNotNull(FfmpegFilterGuard.check("curves=none:0/0 1/1"))
        assertNotNull(FfmpegFilterGuard.check("drawtext=/f.ttf"))
        // Named file keys stay blocked too.
        assertNotNull(FfmpegFilterGuard.check("deshake=filename=/tmp/x"))
        assertNotNull(FfmpegFilterGuard.check("selectivecolor=psfile=/tmp/x.asv"))
    }

    @Test fun safePositionalUsageStillAllowed() {
        assertNull(FfmpegFilterGuard.check("deshake=0:0:-1:-1:16:16:0:8:125:1"))
        assertNull(FfmpegFilterGuard.check("selectivecolor=relative:reds=0 0 0 0.2"))
        assertNull(FfmpegFilterGuard.check("curves=vintage"))
        assertNull(FfmpegFilterGuard.check("scale=1280:720,crop=640:360:0:0,eq=1.1:0.05"))
    }
}
