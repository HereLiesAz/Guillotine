package com.hereliesaz.guillotine.azphalt

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A `.azp` package handed to Guillotine from outside the app, waiting to be installed.
 *
 * azphalt's `spec/store-app.md` specifies the Android app-to-app handoff (`store.azphalt.action.BROWSE`,
 * see [AzphaltStoreHandoff]) and says in as many words that the web case is *not* specified — "the
 * desktop and web storefronts have no equivalent handoff… it should be specified separately rather than
 * assumed." Until it is, the web storefront can only tell a visitor to install the package "from any
 * Azphalt-conforming host", because it has no way to name one.
 *
 * This is the half of that a host can close on its own: Guillotine declares itself an opener for `.azp`
 * files (`AndroidManifest.xml`), so a package downloaded from azphalt.store — or pulled off a drive, or
 * shared from another app — can be opened *into* Guillotine. `MainActivity` drops the URI here and the
 * editor's [com.hereliesaz.guillotine.ui.AzphaltStoreScreen] picks it up, running the identical
 * verification and apply path the store-app handoff uses. Bytes arriving by a route with no trust
 * anchor at all is exactly the case `AzpHandoffInstaller` was already written for.
 *
 * A process-wide holder rather than a ViewModel field because the intent is delivered to the Activity
 * before any editor composition exists to receive it.
 */
object AzpExternalOpen {
    private val _pending = MutableStateFlow<Uri?>(null)

    /** The package waiting to be installed, or null when there is none. */
    val pending: StateFlow<Uri?> = _pending

    /** Records an incoming package. A newer one replaces an unconsumed older one. */
    fun offer(uri: Uri) {
        _pending.value = uri
    }

    /** Clears the pending package once the install flow has finished with it (or been dismissed). */
    fun consume() {
        _pending.value = null
    }
}
