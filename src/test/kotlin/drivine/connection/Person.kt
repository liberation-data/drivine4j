package drivine.connection

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class Person(
    val uuid: UUID?,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val age: Int?,
    val city: String?,
    val country: String?,
    val profession: String?,
    val isActive: Boolean = true,
    val hobbies: List<String>? = emptyList(),
    val createdTimestamp: String?
)
