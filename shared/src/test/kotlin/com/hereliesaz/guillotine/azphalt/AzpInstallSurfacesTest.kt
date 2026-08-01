package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The post-install "where did it go" answer has to be derived per package, because a shader, a caption
 * animation and an on-device model install identically and then appear in three unrelated places — or in
 * none. These pin each mapping so the dialog can't drift into telling every user the same wrong thing.
 */
class AzpInstallSurfacesTest {

    private fun manifest(kind: String, assetTypes: List<String>) = AzpManifest(
        azphalt = "0.1",
        id = "com.example.pkg",
        name = "Test",
        version = "1.0.0",
        kind = kind,
        license = "MIT",
        compat = ">=0.1",
        files = emptyMap(),
        assets = assetTypes.map { AzpAsset(type = it, path = "assets/x") },
    )

    @Test fun shaderGoesToTheClipExtensionsPanel() {
        for (type in listOf("shader", "isf", "glsl")) {
            assertEquals(
                "asset type $type",
                listOf(AzpInstallSurfaces.Surface.CLIP_EXTENSIONS),
                AzpInstallSurfaces.of(manifest("asset", listOf(type))),
            )
        }
    }

    @Test fun lutGoesToTheClipExtensionsPanel() {
        for (type in listOf("lut", "cube")) {
            assertEquals(
                "asset type $type",
                listOf(AzpInstallSurfaces.Surface.CLIP_EXTENSIONS),
                AzpInstallSurfaces.of(manifest("asset", listOf(type))),
            )
        }
    }

    @Test fun motionGoesToTheCaptionPicker() {
        assertEquals(
            listOf(AzpInstallSurfaces.Surface.CAPTION_MOTION),
            AzpInstallSurfaces.of(manifest("asset", listOf("motion"))),
        )
    }

    @Test fun modelPayloadsPointAtTheSettingsFlow() {
        // Landing a model .azp here does NOT wire the model into a settings slot — that's AzpModelInstall,
        // driven from Settings. Saying "it's under Extensions" would be a lie the user would act on.
        for (type in AzpModelInstaller.MODEL_TYPES) {
            assertEquals(
                "asset type $type",
                listOf(AzpInstallSurfaces.Surface.AI_MODEL),
                AzpInstallSurfaces.of(manifest("asset", listOf(type))),
            )
        }
    }

    @Test fun unknownAssetTypeIsListedButNotApplicable() {
        assertEquals(
            listOf(AzpInstallSurfaces.Surface.LISTED_NOT_APPLICABLE),
            AzpInstallSurfaces.of(manifest("asset", listOf("font"))),
        )
    }

    @Test fun assetTypeMatchingIsCaseAndSpaceInsensitive() {
        assertEquals(
            listOf(AzpInstallSurfaces.Surface.CLIP_EXTENSIONS),
            AzpInstallSurfaces.of(manifest("asset", listOf("  SHADER "))),
        )
    }

    @Test fun payloadlessKindsSurfaceNowhere() {
        // The declared kind isn't what decides it — an empty payload is. code/app/mcp/pack all land here.
        for (kind in listOf("code", "app", "mcp", "pack")) {
            assertEquals(
                "kind $kind",
                listOf(AzpInstallSurfaces.Surface.NONE),
                AzpInstallSurfaces.of(manifest(kind, emptyList())),
            )
        }
    }

    @Test fun mixedPackageReachesEverySurfaceItActuallyHas() {
        assertEquals(
            listOf(AzpInstallSurfaces.Surface.CLIP_EXTENSIONS, AzpInstallSurfaces.Surface.CAPTION_MOTION),
            AzpInstallSurfaces.of(manifest("mixed", listOf("shader", "motion"))),
        )
    }

    @Test fun repeatedSurfacesAreReportedOnce() {
        assertEquals(
            listOf(AzpInstallSurfaces.Surface.CLIP_EXTENSIONS),
            AzpInstallSurfaces.of(manifest("asset", listOf("shader", "lut", "glsl"))),
        )
    }
}
