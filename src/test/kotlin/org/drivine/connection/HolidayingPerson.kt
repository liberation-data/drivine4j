package org.drivine.connection

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class HolidayingPerson(
    val person: Person,
    val holidays: List<Holiday>
)
