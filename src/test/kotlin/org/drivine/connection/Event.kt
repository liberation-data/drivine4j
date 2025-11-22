package org.drivine.connection

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class Event(
    val uuid: UUID,
    val name: String,
    val description: String?,
    val occurredAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant?
)