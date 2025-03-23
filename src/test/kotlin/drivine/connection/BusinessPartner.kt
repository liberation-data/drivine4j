package drivine.connection
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class BusinessPartner(
    val clientReferenceDataTypes: List<String>?,
    val code: String?,
    val logoFileName: String?,
    val lastModifiedBy: String?,
    val createdTimestamp: String?,
    val active: Boolean,
    val uuid: UUID?,
    val lastModifiedTimestamp: String?,
    val features: List<String>?,
    val search: String?,
    val createdBy: UUID?,
    val name: String?,
    val bulkConfirmationLimit: Int?,
    val config: String?
)
