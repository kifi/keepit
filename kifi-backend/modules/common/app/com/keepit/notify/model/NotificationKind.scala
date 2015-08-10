package com.keepit.notify.model

import play.api.libs.json._

import scala.annotation.implicitNotFound
import scala.annotation.unchecked.uncheckedVariance

@implicitNotFound("No kind object found for action ${N}")
trait NotificationKind[N <: NotificationEvent] {

  val name: String

  implicit val format: Format[N]

  implicit val selfCompanion: NotificationKind[N] = this

  /**
   * A shortcut to grouping events quickly. Ifthe group identifier function is defined for a notification kind,
   * then a new event of that kind will automatically be grouped with existing event s with the same identifier.
   * 
   * Typically grouping is more intelligent and requires reading a couple events from the database and deserializing
   * JSON. For events like [[NewMessage]], which can be grouped with other events far earlier, deserializing a whole bunch
   * of events from the database to find the right group can be expensive. In addition, events like these do not require
   * advanced grouping behavior and only rely on a few external ids. Therefore, using [[groupIdentifier]] only requires
   * a simple WHERE sql clause instead of a whole bunch of deserialization.
   *
   * It is implied that all notification events with the same group identifier also have the same notification id.
   *
   * @param event The event to find the identifier for
   * @return [[Some]] with the identifier if the identifier exists, [[None]] otherwise
   */
  def groupIdentifier(event: N): Option[String] = None

  /**
   * Defines hether a new event of this kind should be grouped together with existing events in the same notification.
   *
   * @param newEvent The new events
   * @param existingEvents The existing events
   * @return True if the events should be grouped together, false otherwise
   */
  def shouldGroupWith(newEvent: N, existingEvents: Set[N]): Boolean

}

object NotificationKind {

  private val kinds: List[NKind] = List[NKind](
    NewKeepActivity,
    NewSocialConnection,
    OwnedLibraryNewFollower,
    OwnedLibraryNewCollaborator,
    LibraryNewKeep,
    LibraryCollabInviteAccepted,
    LibraryFollowInviteAccepted,
    LibraryNewCollabInvite,
    LibraryNewFollowInvite,
    OwnedLibraryNewCollabInvite,
    OwnedLibraryNewFollowInvite,
    OrgNewInvite,
    OrgInviteAccepted,
    SocialContactJoined,
    NewConnectionInvite,
    ConnectionInviteAccepted,
    DepressedRobotGrumble
  )

  private val kindsByName: Map[String, NKind] = kinds.map(kind => kind.name -> kind).toMap

  def getByName(name: String): Option[NKind] = kindsByName.get(name)

  implicit val format = new Format[NKind] {

    override def reads(json: JsValue): JsResult[NKind] = {
      val kindName = json.as[String]
      getByName(kindName).fold[JsResult[NKind]](JsError(s"Notification action kind $kindName does not exist")) { kind =>
        JsSuccess(kind)
      }
    }

    override def writes(o: NKind): JsValue = Json.toJson(o.name)

  }

}
