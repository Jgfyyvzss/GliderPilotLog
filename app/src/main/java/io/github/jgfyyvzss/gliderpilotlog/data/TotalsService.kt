package io.github.jgfyyvzss.gliderpilotlog.data

import io.github.jgfyyvzss.gliderpilotlog.core.FlightFilter
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.core.RoleTotal
import io.github.jgfyyvzss.gliderpilotlog.core.Totals
import io.github.jgfyyvzss.gliderpilotlog.core.TotalsTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/** The filter shared by the Flights list and Totals. It lasts until the app is closed. */
class FilterStore {
    private val state = MutableStateFlow(FlightFilter())
    val filter: StateFlow<FlightFilter> = state

    fun set(filter: FlightFilter) {
        state.value = filter
    }

    fun clear() = set(FlightFilter())
}

class TotalsService(private val repo: LogbookRepository, private val settings: SettingsRepository) {
    /** The Totals grid for [filter], kept up to date as flights, balances and settings change. */
    fun observe(filter: FlightFilter): Flow<TotalsTable> =
        combine(repo.observeTotalsByRole(filter), repo.observeOpeningBalances(), settings.settings) { flights, balances, s ->
            val openings = balances.mapNotNull { b ->
                Role.entries.firstOrNull { it.name == b.role }?.let { it to RoleTotal(b.minutes, b.flights) }
            }.toMap()
            Totals.table(flights, openings, filter.isActive, s.instructionCountsAsPic)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(filter: Flow<FlightFilter>): Flow<TotalsTable> = filter.flatMapLatest { observe(it) }
}
