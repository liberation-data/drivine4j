package drivine.connection

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class HolidayingPerson(
    val person: Person,
    val holidays: List<Holiday>
)
