package com.roamly.ui.flights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.FlightDto
import com.roamly.data.repository.FlightRepository
import com.roamly.data.repository.Result
import com.roamly.data.repository.SaveFlightResult
import com.roamly.flights.Bcbp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * The editable form of a flight, all strings, in the server's field names'
 * order. Times are the local wall clock at each airport ("yyyy-MM-dd HH:mm"),
 * as printed on a boarding pass.
 */
data class FlightDraft(
    val id: Int? = null,
    val flightNumber: String = "",
    val date: String = "",
    val dep: String = "",
    val arr: String = "",
    val schedDep: String = "",
    val schedArr: String = "",
    val actualDep: String = "",
    val actualArr: String = "",
    val airlineName: String = "",
    val aircraftModel: String = "",
    val registration: String = "",
    val seat: String = "",
    val seatClass: String = "",
    val seatType: String = "",
    val reason: String = "",
    val notes: String = "",
    val bookingRef: String = "",
    val passengerName: String = "",
    val checkinSeq: String = "",
    val ticketNumber: String = "",
    val frequentFlyer: String = "",
    val bcbpRaw: String = "",
    val source: String = "manual",
    /** Airport detail beyond the code, from the stored flight or a lookup leg;
     *  sent only while the code still matches (see [toFields]). */
    val depDetail: FlightDto? = null,
    val arrDetail: FlightDto? = null,
    val airlineIata: String = "",
    val airlineIcao: String = "",
    val hasPassImage: Boolean = false,
    val hasTrack: Boolean = false,
    val trackDistanceKm: Double? = null,
    val trackAuto: Boolean = true,
    val icao24: String = "",
    val trackSource: String = "",
    val openskyStatus: String = "",
    val openskyMessage: String = "",
)

data class FlightsUiState(
    val flights: List<FlightDto> = emptyList(),
    /** Recorded tracks by flight id, [[lon, lat], ...] (longitudes unwrapped). */
    val tracks: Map<Int, List<List<Double>>> = emptyMap(),
    /** Result of the last "match tracks" run, for a one-off message. */
    val trackMessage: String? = null,
    /** flight id -> "recorded" | "opensky", for the map's colours. */
    val trackSources: Map<Int, String> = emptyMap(),
    /** The track the open flight's mini map shows, [[lon, lat], ...], and its source. */
    val editTrack: List<List<Double>> = emptyList(),
    val editTrackSource: String = "",
    val trackBusy: Boolean = false,
    /**
     * A track change staged by the card's buttons, committed only on Save:
     * a preview (its token rides with the save) or a removal. Leaving the
     * editor without saving discards it.
     */
    val pendingTrack: com.roamly.data.api.FlightTrackPreview? = null,
    val pendingRemove: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /**
     * Whether the server can look flights up. Null = not known yet (list not
     * loaded, failed, or a server without the field): treated as "try", so the
     * only thing that hides lookup is the server saying no. The server answers
     * a lookup it can't do with a clear error anyway.
     */
    val lookupEnabled: Boolean? = null,
    val error: String? = null,
    val draft: FlightDraft = FlightDraft(),
    /** JPEG of the boarding pass to attach on save (camera frame or picked image). */
    val passImage: ByteArray? = null,
    val status: String? = null,
    val statusIsError: Boolean = false,
    val lookingUp: Boolean = false,
    /** Lookup answered with several legs and none matched the pass's route. */
    val legChoices: List<FlightDto> = emptyList(),
    val saving: Boolean = false,
    /** Set when saving found the flight already logged. */
    val duplicateOf: Int? = null,
    /** Bumped when a save completes, so the edit screen can close. */
    val savedCount: Int = 0,
)

@HiltViewModel
class FlightsViewModel @Inject constructor(
    private val repo: FlightRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FlightsUiState())
    val state: StateFlow<FlightsUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch { fetch() }
    }

    private suspend fun fetch() {
        _state.update { it.copy(loading = !it.loaded, error = null) }
        when (val t = repo.tracks()) {
            is Result.Success -> _state.update { s ->
                s.copy(tracks = t.data.tracks.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap(),
                       trackSources = t.data.sources.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap())
            }
            is Result.Error -> {}   // the map falls back to great-circle arcs
        }
        when (val r = repo.list()) {
            is Result.Success -> _state.update {
                it.copy(flights = r.data.flights, lookupEnabled = r.data.lookupEnabled,
                        loading = false, loaded = true)
            }
            is Result.Error -> _state.update { it.copy(loading = false, error = r.message) }
        }
    }

    /** False only when the server has said lookup is off. */
    val lookupPossible: Boolean get() = _state.value.lookupEnabled != false

    // ── Starting a draft ────────────────────────────────────────────────────

    private fun reset(draft: FlightDraft, image: ByteArray? = null) = _state.update {
        it.copy(draft = draft, passImage = image, status = null, statusIsError = false,
                legChoices = emptyList(), duplicateOf = null, lookingUp = false, saving = false,
                editTrack = emptyList(), editTrackSource = "", trackBusy = false,
                pendingTrack = null, pendingRemove = false)
    }

    /** Load the open flight's track for its mini map. */
    fun loadEditTrack() {
        val id = _state.value.draft.id ?: return
        viewModelScope.launch {
            when (val r = repo.trackPoints(id)) {
                is Result.Success -> if (_state.value.draft.id == id) _state.update {
                    it.copy(editTrack = r.data.coords, editTrackSource = r.data.source)
                }
                is Result.Error -> {}
            }
        }
    }

    /** Find track (OpenSky) or Use existing locations, previewed against the form. */
    fun previewTrack(action: String) {
        val d = _state.value.draft
        if (d.dep.isBlank() || d.arr.isBlank() || d.date.isBlank()) {
            setStatus("Fill in the date and both airports first.", error = true); return
        }
        viewModelScope.launch {
            _state.update { it.copy(trackBusy = true) }
            when (val r = repo.previewTrack(action, d.id, toFields(d))) {
                is Result.Success -> {
                    val p = r.data
                    if (p.token != null) {
                        _state.update { st -> st.copy(pendingTrack = p, pendingRemove = false,
                            draft = st.draft.copy(icao24 = st.draft.icao24.ifBlank { p.icao24 })) }
                        setStatus("${p.message} Save to keep it.")
                    } else setStatus(p.message.ifBlank { "No track found." }, error = true)
                }
                is Result.Error -> setStatus(r.message, error = true)
            }
            _state.update { it.copy(trackBusy = false) }
        }
    }

    /** A KML/KMZ/CSV, previewed; on an empty form a Flightradar24 file fills it in. */
    fun previewFile(name: String, bytes: ByteArray) {
        viewModelScope.launch {
            _state.update { it.copy(trackBusy = true) }
            when (val r = repo.previewFile(name, bytes)) {
                is Result.Success -> {
                    val p = r.data
                    _state.update { st ->
                        val d = st.draft
                        val blank = d.id == null && d.dep.isBlank() && d.arr.isBlank()
                        val filled = if (blank && p.flight != null) fromDto(p.flight).copy(
                            id = null, seat = d.seat, seatClass = d.seatClass, seatType = d.seatType,
                            reason = d.reason, notes = d.notes, bookingRef = d.bookingRef,
                            passengerName = d.passengerName, source = d.source,
                        ) else d
                        st.copy(draft = filled, pendingTrack = p, pendingRemove = false)
                    }
                    val f = p.flight
                    val d = _state.value.draft
                    if (f != null && (f.depIata != d.dep || f.arrIata != d.arr))
                        setStatus("This file is for ${f.depIata} → ${f.arrIata}, not the airports in the form.", error = true)
                    else setStatus(p.message)
                }
                is Result.Error -> setStatus(r.message, error = true)
            }
            _state.update { it.copy(trackBusy = false) }
        }
    }

    fun removeTrack() = _state.update { it.copy(pendingTrack = null, pendingRemove = true) }

    fun startNew() = reset(FlightDraft(date = java.time.LocalDate.now().toString()))

    fun startEdit(id: Int) {
        val f = _state.value.flights.firstOrNull { it.id == id } ?: return
        reset(fromDto(f))
        loadEditTrack()
    }

    /** A scanned pass: fill what it carries, then ask AeroDataBox for the rest. */
    fun startFromPass(pass: Bcbp.Pass, leg: Bcbp.Leg, image: ByteArray?) {
        reset(FlightDraft(
            flightNumber = leg.flightNumber,
            date = leg.date?.toString().orEmpty(),
            dep = leg.from, arr = leg.to,
            seat = leg.seat, seatClass = leg.seatClass,
            bookingRef = leg.bookingRef, passengerName = pass.passengerName,
            checkinSeq = leg.checkinSeq, ticketNumber = leg.ticketNumber,
            frequentFlyer = leg.frequentFlyer, bcbpRaw = pass.raw,
            airlineIata = leg.carrier.takeIf { it.length == 2 }.orEmpty(),
            source = "boarding_pass",
        ), image)
        setStatus(if (leg.date == null) "Read from the boarding pass. Check the date." else "Read from the boarding pass.")
        if (leg.date == null) return
        viewModelScope.launch {
            // Opened straight from a share, the list may not have loaded yet,
            // and it is what says whether lookup is available.
            if (!_state.value.loaded) {
                fetch()
                // Say so rather than quietly skipping the lookup: this is what
                // made a failing list request look like "lookup does nothing".
                _state.value.error?.let { setStatus("Couldn't reach the server for flight details: $it", error = true) }
            }
            if (lookupPossible) lookup()
        }
    }

    fun edit(change: (FlightDraft) -> FlightDraft) = _state.update { it.copy(draft = change(it.draft)) }

    // ── AeroDataBox ─────────────────────────────────────────────────────────

    fun lookup() {
        val d = _state.value.draft
        val number = d.flightNumber.replace(" ", "").uppercase()
        if (number.isBlank() || d.date.isBlank()) {
            setStatus("Enter the flight number and date first.", error = true); return
        }
        viewModelScope.launch {
            _state.update { it.copy(lookingUp = true, legChoices = emptyList()) }
            when (val r = repo.lookup(number, d.date)) {
                is Result.Error -> setStatus(r.message, error = true)
                is Result.Success -> {
                    val legs = r.data.legs
                    // The pass already says which route, so pick the matching
                    // leg of a multi-stop flight number without asking.
                    val pick = legs.firstOrNull { it.depIata == d.dep && it.arrIata == d.arr }
                        ?: legs.firstOrNull { d.dep.isNotBlank() && it.depIata == d.dep }
                        ?: legs.singleOrNull()
                    when {
                        legs.isEmpty() -> setStatus("No flight $number found on ${d.date}. Fill in the rest by hand.", error = true)
                        pick != null -> applyLeg(pick)
                        else -> {
                            _state.update { it.copy(legChoices = legs) }
                            setStatus("This flight has several legs. Pick yours.")
                        }
                    }
                }
            }
            _state.update { it.copy(lookingUp = false) }
        }
    }

    /** Merge a lookup leg: times, aircraft, airline and airport detail. Whatever
     *  the boarding pass said (seat, class, booking…) is left alone. */
    fun applyLeg(leg: FlightDto) {
        _state.update { s ->
            val d = s.draft
            s.copy(legChoices = emptyList(), draft = d.copy(
                flightNumber = leg.flightNumber.ifBlank { d.flightNumber },
                dep = leg.depIata.ifBlank { leg.depIcao }.ifBlank { d.dep },
                arr = leg.arrIata.ifBlank { leg.arrIcao }.ifBlank { d.arr },
                schedDep = localOf(leg.schedDep, leg.depTz).ifBlank { d.schedDep },
                schedArr = localOf(leg.schedArr, leg.arrTz).ifBlank { d.schedArr },
                actualDep = localOf(leg.actualDep, leg.depTz).ifBlank { d.actualDep },
                actualArr = localOf(leg.actualArr, leg.arrTz).ifBlank { d.actualArr },
                airlineName = leg.airlineName.ifBlank { d.airlineName },
                airlineIata = leg.airlineIata.ifBlank { d.airlineIata },
                airlineIcao = leg.airlineIcao.ifBlank { d.airlineIcao },
                aircraftModel = leg.aircraftModel.ifBlank { d.aircraftModel },
                registration = leg.registration.ifBlank { d.registration },
                icao24 = leg.icao24.ifBlank { d.icao24 },
                depDetail = leg, arrDetail = leg,
            ))
        }
        setStatus("Filled in from AeroDataBox. Check it over, then save.")
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    /** [overwriteId]: save onto that existing flight instead (the duplicate prompt). */
    fun save(overwriteId: Int? = null) {
        val s = _state.value
        val d = s.draft
        if (d.dep.isBlank() || d.arr.isBlank() || d.date.isBlank()) {
            setStatus("A date and both airports are needed.", error = true); return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, duplicateOf = null) }
            val target = overwriteId ?: d.id
            when (val r = repo.save(target, toFields(d))) {
                is SaveFlightResult.Saved -> {
                    var saved = r.flight
                    s.passImage?.let { img ->
                        when (val u = repo.uploadPass(saved.id, img)) {
                            is Result.Success -> u.data?.let { saved = it }
                            is Result.Error -> setStatus("Saved, but the photo did not upload: ${u.message}", error = true)
                        }
                    }
                    _state.update { st ->
                        val others = st.flights.filterNot { it.id == saved.id }
                        st.copy(saving = false, savedCount = st.savedCount + 1, passImage = null,
                                pendingTrack = null, pendingRemove = false, trackMessage = r.trackNote,
                                flights = (others + saved).sortedByDescending { it.date })
                    }
                    load()
                }
                is SaveFlightResult.AlreadyLogged -> _state.update {
                    it.copy(saving = false, duplicateOf = r.existingId)
                }
                is SaveFlightResult.Failed -> {
                    _state.update { it.copy(saving = false) }
                    setStatus(r.message, error = true)
                }
            }
        }
    }

    fun dismissDuplicate() = _state.update { it.copy(duplicateOf = null) }

    // ── Recorded tracks ─────────────────────────────────────────────────────

    fun matchAllTracks() {
        viewModelScope.launch {
            val msg = when (val r = repo.matchAllTracks()) {
                is Result.Success -> if (r.data.matched > 0)
                    "Linked ${r.data.matched} recorded track${if (r.data.matched == 1) "" else "s"}." else "No new tracks found."
                is Result.Error -> r.message
            }
            _state.update { it.copy(trackMessage = msg) }
            fetch()
        }
    }

    fun clearTrackMessage() = _state.update { it.copy(trackMessage = null) }

    fun delete(id: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            when (val r = repo.delete(id)) {
                is Result.Success -> {
                    _state.update { s -> s.copy(flights = s.flights.filterNot { it.id == id }) }
                    onDone()
                }
                is Result.Error -> setStatus(r.message, error = true)
            }
        }
    }

    private fun setStatus(msg: String?, error: Boolean = false) =
        _state.update { it.copy(status = msg, statusIsError = error) }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private fun fromDto(f: FlightDto) = FlightDraft(
        id = f.id, flightNumber = f.flightNumber, date = f.date,
        dep = f.depIata.ifBlank { f.depIcao }, arr = f.arrIata.ifBlank { f.arrIcao },
        schedDep = f.schedDepLocal.replace('T', ' '), schedArr = f.schedArrLocal.replace('T', ' '),
        actualDep = f.actualDepLocal.replace('T', ' '), actualArr = f.actualArrLocal.replace('T', ' '),
        airlineName = f.airlineName, aircraftModel = f.aircraftModel, registration = f.registration,
        seat = f.seat, seatClass = f.seatClass, seatType = f.seatType, reason = f.reason, notes = f.notes,
        bookingRef = f.bookingRef, passengerName = f.passengerName, checkinSeq = f.checkinSeq,
        ticketNumber = f.ticketNumber, frequentFlyer = f.frequentFlyer, bcbpRaw = f.bcbpRaw,
        source = f.source.ifBlank { "manual" }, depDetail = f, arrDetail = f,
        airlineIata = f.airlineIata, airlineIcao = f.airlineIcao, hasPassImage = f.hasBoardingPassImage,
        hasTrack = f.hasTrack, trackDistanceKm = f.trackDistanceKm, trackAuto = f.trackAuto,
        icao24 = f.icao24, trackSource = f.trackSource,
        openskyStatus = f.openskyStatus, openskyMessage = f.openskyMessage,
    )

    private fun toFields(d: FlightDraft): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "flight_number" to d.flightNumber.trim(),
            "date" to d.date.trim(),
            "sched_dep_local" to d.schedDep.trim().replace(' ', 'T'),
            "sched_arr_local" to d.schedArr.trim().replace(' ', 'T'),
            "actual_dep_local" to d.actualDep.trim().replace(' ', 'T'),
            "actual_arr_local" to d.actualArr.trim().replace(' ', 'T'),
            "airline_name" to d.airlineName, "airline_iata" to d.airlineIata, "airline_icao" to d.airlineIcao,
            "aircraft_model" to d.aircraftModel, "registration" to d.registration,
            "seat" to d.seat, "seat_class" to d.seatClass, "seat_type" to d.seatType,
            "reason" to d.reason, "notes" to d.notes,
            "booking_ref" to d.bookingRef, "passenger_name" to d.passengerName,
            "checkin_seq" to d.checkinSeq, "ticket_number" to d.ticketNumber,
            "frequent_flyer" to d.frequentFlyer, "bcbp_raw" to d.bcbpRaw,
            "icao24" to d.icao24.trim().lowercase(),
            "source" to d.source,
        )
        airport(m, "dep", d.dep, d.depDetail)
        airport(m, "arr", d.arr, d.arrDetail)
        val st = _state.value
        if (st.pendingRemove) m["track_remove"] = true
        else st.pendingTrack?.token?.let { m["track_token"] = it }
        return m
    }

    /** The code always; the full detail only while it still describes that code. */
    private fun airport(m: MutableMap<String, Any?>, side: String, code: String, detail: FlightDto?) {
        val c = code.trim().uppercase()
        m[if (c.length == 4) "${side}_icao" else "${side}_iata"] = c
        detail ?: return
        val dep = side == "dep"
        val iata = if (dep) detail.depIata else detail.arrIata
        val icao = if (dep) detail.depIcao else detail.arrIcao
        if (c != iata && c != icao) return
        m["${side}_iata"] = iata
        m["${side}_icao"] = icao
        m["${side}_name"] = if (dep) detail.depName else detail.arrName
        m["${side}_city"] = if (dep) detail.depCity else detail.arrCity
        m["${side}_country"] = if (dep) detail.depCountry else detail.arrCountry
        m["${side}_lat"] = if (dep) detail.depLat else detail.arrLat
        m["${side}_lon"] = if (dep) detail.depLon else detail.arrLon
        m["${side}_tz"] = if (dep) detail.depTz else detail.arrTz
    }

    private val localFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun localOf(isoUtc: String?, tz: String): String = runCatching {
        val zone = if (tz.isBlank()) ZoneId.of("UTC") else ZoneId.of(tz)
        OffsetDateTime.parse(isoUtc!!).atZoneSameInstant(zone).format(localFmt)
    }.getOrDefault("")
}
