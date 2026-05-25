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