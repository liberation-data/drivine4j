package sample.mapped.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root

/**
 * Fixtures for cascade delete-by-id, modelled on the chat-session shape from the feature spec:
 *
 * ```
 * (:User) <-[OWNED_BY]- (:Session) -[HAS_MESSAGE]-> (:Message) -[AUTHORED_BY]-> (:User)
 *                                                              -[SENT_TO]->     (:User)
 * ```
 *
 * The cascade views below deliberately declare only a subset of these relationships so the tests
 * can prove the view is the cascade boundary: AUTHORED_BY / SENT_TO / OWNED_BY are never declared,
 * so cascade must never follow them — the :User node always survives.
 *
 * There is intentionally no `User` fragment class: the user node is only ever created and asserted
 * via raw Cypher, precisely because it must stay outside every cascade view.
 */

@NodeFragment(labels = ["Session"])
data class Session(
    @NodeId val id: String,
    val title: String? = null,
)

@NodeFragment(labels = ["Message"])
data class Message(
    @NodeId val id: String,
    val text: String? = null,
)

@NodeFragment(labels = ["Attachment"])
data class Attachment(
    @NodeId val id: String,
    val name: String? = null,
)

/**
 * Delete-only view: the Session root plus its Message children as plain node fragments.
 * The messages' own edges (AUTHORED_BY, SENT_TO) are not part of the view, so cascade deletes the
 * session and messages while leaving the users they point at untouched.
 */
@GraphView
data class DeletableSession(
    @Root val session: Session,

    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messages: List<Message>,
)

/**
 * A nested view: a Message together with its attachments. Used as the child of [DeletableSessionDeep]
 * to exercise multi-level cascade through a nested @GraphView.
 */
@GraphView
data class MessageWithAttachments(
    @Root val message: Message,

    @GraphRelationship(type = "HAS_ATTACHMENT", direction = Direction.OUTGOING)
    val attachments: List<Attachment>,
)

/**
 * Two-level delete view: Session -> MessageWithAttachments(view) -> Attachment. Cascade must reach
 * the attachments through the nested view while still leaving out-of-view users intact.
 */
@GraphView
data class DeletableSessionDeep(
    @Root val session: Session,

    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messages: List<MessageWithAttachments>,
)

/**
 * A recursive delete view: a Session, its messages, and the sessions `PART_OF` it, to three levels.
 * Each level holds its own messages, as the root does, so a cascade must delete those at every
 * level, and refuse a tree deeper than three levels rather than leave the rest of it behind.
 */
@GraphView
data class DeletableSessionTree(
    @Root val session: Session,

    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messages: List<Message> = emptyList(),

    @GraphRelationship(type = "PART_OF", direction = Direction.INCOMING, maxDepth = 3)
    val parts: List<DeletableSessionTree> = emptyList(),
)

/**
 * [DeletableSessionTree] whose messages are a nested view, so each level of the tree reaches the
 * attachments of its messages too.
 */
@GraphView
data class DeletableSessionTreeDeep(
    @Root val session: Session,

    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messages: List<MessageWithAttachments> = emptyList(),

    @GraphRelationship(type = "PART_OF", direction = Direction.INCOMING, maxDepth = 3)
    val parts: List<DeletableSessionTreeDeep> = emptyList(),
)

/**
 * A session that shelves session trees: the recursive relationship is one nested view in, so a
 * cascade reaches it only through the shelf's own relationship.
 */
@GraphView
data class DeletableSessionShelf(
    @Root val session: Session,

    @GraphRelationship(type = "SHELVES", direction = Direction.OUTGOING)
    val trees: List<DeletableSessionTree> = emptyList(),
)
