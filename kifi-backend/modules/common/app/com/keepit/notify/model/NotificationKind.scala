package com.keepit.notify.model

import play.api.libs.json._

import scala.annotation.implicitNotFound
import scala.annotation.unchecked.uncheckedVariance

@implicitNotFound("No kind object found for action ${N}")
trait NotificationKind[N <: NotificationEvent] {

  val name: String

  implicit val format: Format[N]

  implicit val selfCompanion: NotificationKind[N] = this

  def shouldGroupWith(newEvent: N, existingEvents: Set[N]): Boolean

}

object NotificationKind {

  private val kinds: List[NKind] = List[NKind](
    NewKeepActivity,
    NewSocialConnection,
    OwnedLibraryNewFollower,
    OwnedLibraryNewCollaborator,
    LibraryNewKeep,
    NewConnection,
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
