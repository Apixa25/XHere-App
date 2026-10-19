package world.xhere.app.drop

import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationSignal

/**
 * The spot a Here Card or the AR view asked the composer to use.
 * The map sets it, then opens Drop. The composer reads it once.
 * A plain Drop from the button or the tab clears it, so GPS is used again.
 * A Here Card leaves [signal] null, which the composer treats as a map pin.
 * AR passes the Geospatial pose, and that one is a device fix.
 * This is not a location history.
 */
class PendingDropSpot {
    var spot: LatLng? = null
        private set
    var label: String? = null
        private set
    var signal: LocationSignal? = null
        private set

    fun set(spot: LatLng, label: String?, signal: LocationSignal? = null) {
        this.spot = spot
        this.label = label
        this.signal = signal
    }

    fun clear() {
        spot = null
        label = null
        signal = null
    }
}
