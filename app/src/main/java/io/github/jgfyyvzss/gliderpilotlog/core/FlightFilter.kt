package io.github.jgfyyvzss.gliderpilotlog.core

import java.time.LocalDate

/** The Flights list and Totals filter. Every bound is inclusive and null means no restriction. */
data class FlightFilter(
    val gliderId: String? = null,
    val airfieldId: String? = null,
    val clubId: String? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val role: Role? = null,
    val logNoFrom: Int? = null,
    val logNoTo: Int? = null,
    val launchFrom: ClockTime? = null,
    val launchTo: ClockTime? = null,
) {
    val isActive: Boolean get() = this != FlightFilter()
}

/** A SQL condition with its bound arguments, in order. */
data class SqlCondition(val sql: String, val args: List<Any>)

object FilterSql {
    /**
     * The WHERE condition for live flights matching [filter], for a query that names the flight table `f`.
     * A launch time range leaves out flights without a launch time.
     */
    fun where(filter: FlightFilter): SqlCondition {
        val parts = mutableListOf("f.deleted_at IS NULL")
        val args = mutableListOf<Any>()
        fun add(sql: String, vararg values: Any) {
            parts += sql
            args.addAll(values)
        }
        filter.gliderId?.let { add("f.glider_id = ?", it) }
        filter.airfieldId?.let { add("f.airfield_id = ?", it) }
        filter.clubId?.let { add("f.club_id = ?", it) }
        filter.dateFrom?.let { add("f.date >= ?", it.toString()) }
        filter.dateTo?.let { add("f.date <= ?", it.toString()) }
        filter.role?.let { add("f.role = ?", it.name) }
        filter.logNoFrom?.let { add("f.log_no >= ?", it) }
        filter.logNoTo?.let { add("f.log_no <= ?", it) }
        filter.launchFrom?.let { add("f.launch_time >= ?", it.toString()) }
        filter.launchTo?.let { add("f.launch_time <= ?", it.toString()) }
        return SqlCondition(parts.joinToString(" AND "), args)
    }
}
