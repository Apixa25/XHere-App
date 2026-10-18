package world.xhere.app.drop

import world.xhere.shared.geo.LatLng

/**
 * The spot a Here Card asked the composer to use.
 * The map sets it, then opens Drop. The composer reads it once.
 * A plain Drop from the button or the tab clears it, so GPS is used again.
 * This is not a location history.
 */
class PendingDropSpot {
    var spot: LatLng? = null
        private set
    var label: String? = null
        private set

    fun set(spot: LatLng, label: String?) {
        this.spot = spot
        this.label = label
    }

    fun clear() {
        spot = null
        label = null
    }
}
