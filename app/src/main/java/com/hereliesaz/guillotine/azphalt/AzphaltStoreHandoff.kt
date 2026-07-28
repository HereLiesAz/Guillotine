package com.hereliesaz.guillotine.azphalt

import android.content.Intent
import android.content.pm.PackageManager

/**
 * The delegated-acquisition intent a store app implements (azphalt `spec/store-app.md`): instead of
 * Guillotine building its own storefront UI, whichever Azphalt Store app is installed does the
 * browsing and acquiring, and hands back a package it has already fetched and checked. Guillotine
 * still re-verifies the bytes itself ([AzpHandoffInstaller]) — the spec is explicit that a store app
 * is a convenience, never a trust anchor.
 *
 * There is no published host-side SDK for this — the azphalt reference client libraries are JS/TS,
 * for hosts that talk to a repository directly — so this mirrors the spec (and the reference store
 * app's own `Handoff` object, `apps/storefront-cmp` in the azphalt repo) directly: it's just an
 * Android `Intent` contract, not a dependency.
 */
object AzphaltStoreHandoff {
    const val ACTION_BROWSE = "store.azphalt.action.BROWSE"
    const val EXTRA_APP = "app"

    /** Reverse-DNS package id of the reference Azphalt Store app, for the "not installed" fallback. */
    const val STORE_APP_PACKAGE = "store.azphalt.storefront"

    /**
     * The browse request for this host, scoped by [hostAppId] (this host's own package name) so
     * packages meant for other hosts never show. Launch it for result — the store app always finishes
     * with either a verified package or a clean [android.app.Activity.RESULT_CANCELED].
     */
    fun browseIntent(hostAppId: String): Intent =
        Intent(ACTION_BROWSE).putExtra(EXTRA_APP, hostAppId)

    /** Whether any installed app can handle [browseIntent] — i.e. an Azphalt Store app is present. */
    fun isAvailable(packageManager: PackageManager, hostAppId: String): Boolean =
        packageManager.resolveActivity(browseIntent(hostAppId), PackageManager.MATCH_DEFAULT_ONLY) != null
}
