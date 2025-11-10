package org.drivine.connection

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class Holiday(
    val uuid: UUID?,
    val name: String?,
    val date: String?, // ISO date string
    val country: String?,
    val type: String?, // "national", "religious", "cultural"
    val description: String?,
    val isPublicHoliday: Boolean = false,
    val createdBy: String?,
    val createdTimestamp: String?,
    val tags: List<String>? = emptyList()
)
