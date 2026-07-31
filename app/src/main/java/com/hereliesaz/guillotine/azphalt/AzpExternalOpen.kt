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
 * This is the half of that a host can close on its own. Guillotine declares itself an opener for `.azp`
 * files *and* a handler for the `azphalt://install` scheme (`AndroidManifest.xml`), so a package
 * downloaded from azphalt.store — or pulled off a drive, shared from another app, or merely *named* by a
 * link on a web page — reaches the editor. `MainActivity` drops it here and
 * [com.hereliesaz.guillotine.ui.AzphaltStoreScreen] picks it up, running the identical verification and
 * apply path the store-app handoff uses. Bytes arriving by a route with no trust anchor at all is exactly
 * the case `AzpHandoffInstaller` was already written for.
 *
 * There are two ways a package arrives from outside, and [Incoming] is the choice between them: bytes
 * already in hand (a `.azp` file URI), or a name to go and fetch (an `azphalt://install` deep link — see
 * [AzpInstallLink]). Both are "a package handed to Guillotine from outside", differing only in how they
 * resolve to bytes, so they share one flow and one install path rather than running in parallel.
 *
 * A process-wide holder rather than a ViewModel field because the intent is delivered to the Activity
 * before any editor composition exists to receive it.
 */
object AzpExternalOpen {

    /** A package handed in from outside, in whichever form it arrived. */
    sealed interface Incoming {
        /** A `.azp` already on the device — a browser download, a file manager, a share sheet. */
        data class File(val uri: Uri) : Incoming

        /** A `azphalt://install` deep link naming a package for the host to download. */
        data class Link(val link: AzpInstallLink) : Incoming
    }

    private val _pending = MutableStateFlow<Incoming?>(null)

    /** The package waiting to be installed, or null when there is none. */
    val pending: StateFlow<Incoming?> = _pending

    /** Records an incoming package. A newer one replaces an unconsumed older one. */
    fun offer(incoming: Incoming) {
        _pending.value = incoming
    }

    /** Convenience for the file route: a `.azp` URI handed in by VIEW or SEND. */
    fun offer(uri: Uri) = offer(Incoming.File(uri))

    /** Convenience for the deep-link route: an already-parsed, already-validated install link. */
    fun offer(link: AzpInstallLink) = offer(Incoming.Link(link))

    /** Clears the pending package once the install flow has finished with it (or been dismissed). */
    fun consume() {
        _pending.value = null
    }
}
