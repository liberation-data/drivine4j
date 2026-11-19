package sample.idkind

import org.drivine.annotation.Direction
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.ReadOnly
import org.drivine.annotation.RelationshipFragment
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.ZonedDateTime
import java.util.Date
import java.util.UUID

/**
 * Fixtures for a node whose `@NodeId` a SQL store holds as a temporal tag, read by classes that declare the id each way
 * (liberation-data/drivine4j#860, #861): an owner `-[:OWNS {since}]->` one keyed node, each labelled `IdKindRun`.
 */
@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedText(
    @NodeId val id: String,
    val name: String? = null,
    @FullTextIndex val body: String? = null,
    @VectorIndex val embedding: List<Float>? = null,
    @NodeStamp val stamp: String? = null,
)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedLong(@NodeId val id: Long, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedInt(@NodeId val id: Int)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedDouble(@NodeId val id: Double)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedDecimal(@NodeId val id: BigDecimal)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedBoolean(@NodeId val id: Boolean)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedUuid(@NodeId val id: UUID)

enum class Code { A }

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedEnum(@NodeId val id: Code)

/** An id the user didn't type: not refused, and saved back as the tag it was loaded from while it is unchanged. */
@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedAny(@NodeId val id: Any, val name: String? = null, @NodeStamp val stamp: String? = null)

/** An id stored under another name: refused by its field's name. */
@NodeFragment(labels = ["IdKindRun", "Renamed"])
data class RenamedId(@NodeId @GraphProperty("key") val id: String)

@NodeFragment(labels = ["IdKindRun", "Base"])
open class KeyedBase(@NodeId open val id: String = "")

/** A subclass that inherits its `@NodeId`. */
@NodeFragment(labels = ["IdKindRun", "Base", "Sub"])
class KeyedSub(override val id: String = "", val name: String? = null) : KeyedBase(id)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedDate(@NodeId val id: LocalDate, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedLocalDateTime(@NodeId val id: LocalDateTime, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedLocalTime(@NodeId val id: LocalTime, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedOffsetTime(@NodeId val id: OffsetTime, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedInstant(@NodeId val id: Instant, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedOffsetDateTime(@NodeId val id: OffsetDateTime, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedZonedDateTime(@NodeId val id: ZonedDateTime, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedUtilDate(@NodeId val id: Date, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedDuration(@NodeId val id: Duration, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Keyed"])
data class KeyedPeriod(@NodeId val id: Period, val name: String? = null)

@NodeFragment(labels = ["IdKindRun", "Owner"])
data class Owner(@NodeId val id: String, val name: String? = null)

@GraphView
data class KeyedView(@Root val keyed: KeyedText)

@RelationshipFragment
data class Ownership(val since: String? = null, val keyed: KeyedText)

@GraphView
data class Owned(
    @Root val owner: Owner,
    @GraphRelationship(type = "OWNS", direction = Direction.OUTGOING)
    val keyed: List<KeyedText> = emptyList(),
)

@GraphView
data class OwnedAny(
    @Root val owner: Owner,
    @GraphRelationship(type = "OWNS", direction = Direction.OUTGOING)
    val keyed: List<KeyedAny> = emptyList(),
)

@GraphView
data class OwnedNested(
    @Root val owner: Owner,
    @GraphRelationship(type = "OWNS", direction = Direction.OUTGOING)
    val views: List<KeyedView> = emptyList(),
)

@GraphView
data class OwnedByFragment(
    @Root val owner: Owner,
    @GraphRelationship(type = "OWNS", direction = Direction.OUTGOING)
    val ownerships: List<Ownership> = emptyList(),
)

@GraphView
data class OwnedByPath(
    @Root val owner: Owner,
    @ReadOnly
    @GraphPath([Hop("OWNS", Direction.OUTGOING)])
    val reached: List<KeyedText> = emptyList(),
)
