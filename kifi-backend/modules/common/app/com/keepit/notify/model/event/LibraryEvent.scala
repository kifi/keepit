package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ LibraryAccess, Library, User }
import com.keepit.notify.info.DbViewKey._
import com.keepit.notify.info._
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait LibraryCollabInviteAcceptedImpl extends NonGroupingNotificationKind[LibraryCollabInviteAccepted] {

  override val name: String = "library_collab_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryCollabInviteAccepted.apply, unlift(LibraryCollabInviteAccepted.unapply))

  def build(recipient: Recipient, time: DateTime, accepter: User, invitedLib: Library): ExistingDbView[LibraryCollabInviteAccepted] = {
    import DbViewKey._
    ExistingDbView(
      user.existing(accepter), library.existing(invitedLib)
    )(LibraryCollabInviteAccepted(
        recipient = recipient,
        time = time,
        accepterId = accepter.id.get,
        libraryId = invitedLib.id.get
      ))
  }

  override def info(event: LibraryCollabInviteAccepted): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(
      user(event.accepterId), library(event.libraryId), userImageUrl(event.accepterId), libraryInfo(event.libraryId)
    ) { subset =>
        val accepter = user(event.accepterId).lookup(subset)
        val invitedLib = library(event.libraryId).lookup(subset)
        val accepterImage = userImageUrl(event.accepterId).lookup(subset)
        val invitedLibInfo = libraryInfo(event.libraryId).lookup(subset)
        NotificationInfo(
          url = Path(accepter.username.value).encode.absolute,
          imageUrl = accepterImage,
          title = s"${accepter.firstName} is now collaborating on ${invitedLib.name}",
          body = s"You invited ${accepter.firstName} to join ${invitedLib.name}",
          linkText = s"See ${accepter.firstName}’s profile",
          extraJson = Some(Json.obj(
            "follower" -> BasicUser.fromUser(accepter),
            "library" -> Json.toJson(invitedLibInfo)
          ))
        )
      }
  }

}

trait LibraryFollowInviteAcceptedImpl extends NonGroupingNotificationKind[LibraryFollowInviteAccepted] {

  override val name: String = "library_follow_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryFollowInviteAccepted.apply, unlift(LibraryFollowInviteAccepted.unapply))

  def build(recipient: Recipient, time: DateTime, accepter: User, acceptedLib: Library): ExistingDbView[LibraryFollowInviteAccepted] = {
    import DbViewKey._
    ExistingDbView(
      user.existing(accepter), library.existing(acceptedLib)
    )(LibraryFollowInviteAccepted(
        recipient = recipient,
        time = time,
        accepterId = accepter.id.get,
        libraryId = acceptedLib.id.get
      ))
  }

  override def info(event: LibraryFollowInviteAccepted): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(
      user(event.accepterId), library(event.libraryId), userImageUrl(event.accepterId), libraryInfo(event.libraryId)
    ) { subset =>
        val accepter = user(event.accepterId).lookup(subset)
        val acceptedLib = library(event.libraryId).lookup(subset)
        val accepterImage = userImageUrl(event.accepterId).lookup(subset)
        val acceptedLibInfo = libraryInfo(event.libraryId).lookup(subset)
        NotificationInfo(
          url = Path(accepter.username.value).encode.absolute,
          imageUrl = accepterImage,
          title = s"${accepter.firstName} is now following ${acceptedLib.name}",
          body = s"You invited ${accepter.firstName} to join ${acceptedLib.name}",
          linkText = s"See ${accepter.firstName}’s profile",
          extraJson = Some(Json.obj(
            "follower" -> BasicUser.fromUser(accepter),
            "library" -> Json.toJson(acceptedLibInfo)
          ))
        )
      }
  }

}

trait LibraryNewCollabInviteImpl extends NonGroupingNotificationKind[LibraryNewCollabInvite] {

  override val name: String = "library_new_collab_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewCollabInvite.apply, unlift(LibraryNewCollabInvite.unapply))

  def build(recipient: Recipient, time: DateTime, inviter: User, invitedLib: Library, invitedLibOwner: User): ExistingDbView[LibraryNewCollabInvite] = {
    import DbViewKey._
    ExistingDbView(
      user.existing(inviter), library.existing(invitedLib), user.existing(invitedLibOwner)
    )(LibraryNewCollabInvite(
        recipient = recipient,
        time = time,
        inviterId = inviter.id.get,
        libraryId = invitedLib.id.get
      ))
  }

  override def info(event: LibraryNewCollabInvite): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(
      user(event.inviterId), userImageUrl(event.inviterId), library(event.libraryId), libraryInfo(event.libraryId),
      libraryUrl(event.libraryId), libraryOwner(event.libraryId)
    ) { subset =>
        val inviter = user(event.inviterId).lookup(subset)
        val inviterImage = userImageUrl(event.inviterId).lookup(subset)
        val invitedLib = library(event.libraryId).lookup(subset)
        val invitedLibInfo = libraryInfo(event.libraryId).lookup(subset)
        val invitedLibUrl = libraryUrl(event.libraryId).lookup(subset)
        val invitedLibOwner = libraryOwner(event.libraryId).lookup(subset)
        NotificationInfo(
          url = invitedLibUrl,
          imageUrl = inviterImage,
          title = s"${inviter.firstName} ${inviter.lastName} invited you to collaborate on a library!",
          body = s"Help ${invitedLibOwner.firstName} by sharing your knowledge in the library ${invitedLib.name}.", // todo doesn't _really_ make any sense
          linkText = "Visit library",
          extraJson = Some(Json.obj(
            "inviter" -> inviter,
            "library" -> Json.toJson(invitedLibInfo),
            "access" -> LibraryAccess.READ_WRITE
          ))
        )
      }
  }

}

trait LibraryNewFollowInviteImpl extends NonGroupingNotificationKind[LibraryNewFollowInvite] {

  override val name: String = "library_new_follow_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewFollowInvite.apply, unlift(LibraryNewFollowInvite.unapply))

  def build(recipient: Recipient, time: DateTime, inviter: User, invitedLib: Library, invitedLibOwner: User): ExistingDbView[LibraryNewFollowInvite] = {
    import DbViewKey._
    ExistingDbView(
      user.existing(inviter), library.existing(invitedLib), user.existing(invitedLibOwner)
    )(LibraryNewFollowInvite(
        recipient = recipient,
        time = time,
        inviterId = inviter.id.get,
        libraryId = invitedLib.id.get
      ))
  }

  override def info(event: LibraryNewFollowInvite): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(
      user(event.inviterId), userImageUrl(event.inviterId), library(event.libraryId), libraryInfo(event.libraryId),
      libraryUrl(event.libraryId), libraryOwner(event.libraryId)
    ) { subset =>
        val inviter = user(event.inviterId).lookup(subset)
        val inviterImage = userImageUrl(event.inviterId).lookup(subset)
        val libraryIn = library(event.libraryId).lookup(subset)
        val libraryInInfo = libraryInfo(event.libraryId).lookup(subset)
        val libraryInUrl = libraryUrl(event.libraryId).lookup(subset)
        val libraryInOwner = libraryOwner(event.libraryId).lookup(subset)
        NotificationInfo(
          url = libraryInUrl,
          imageUrl = inviterImage,
          title = s"${inviter.firstName} ${inviter.lastName} invited you to follow a library!",
          body = s"Browse keeps in ${libraryIn.name} to find some interesting gems kept by ${libraryIn.name}.", //same
          linkText = "Visit library",
          extraJson = Some(Json.obj(
            "inviter" -> inviter,
            "library" -> Json.toJson(libraryInInfo),
            "access" -> LibraryAccess.READ_ONLY
          ))
        )
      }
  }

}
