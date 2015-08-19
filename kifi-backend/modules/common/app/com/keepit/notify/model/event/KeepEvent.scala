package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.{ Library, Keep, User }
import com.keepit.notify.info.{ NotificationInfo, NeedsInfo }
import com.keepit.notify.model.{ NotificationEvent, NotificationKind, Recipient }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait KeepEvent extends NotificationEvent

case class LibraryNewKeep(
    recipient: Recipient,
    time: DateTime,
    keeperId: Id[User],
    keepId: Id[Keep],
    libraryId: Id[Library]) extends KeepEvent {

  val kind = LibraryNewKeep

}

object LibraryNewKeep extends NotificationKind[LibraryNewKeep] {

  override val name: String = "library_new_keep"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewKeep.apply, unlift(LibraryNewKeep.unapply))

  override def shouldGroupWith(newEvent: LibraryNewKeep, existingEvents: Set[LibraryNewKeep]): Boolean = false

  override val info = {
    import NeedsInfo._
    usingOne[LibraryNewKeep](
      "newKeep".arg(_.keepId, keep), "keeper".arg(_.keeperId, user), "keeperImage".arg(_.keeperId, userImage),
      "libraryKept".arg(_.libraryId, library)
    ) {
      case Fetched(args) =>
        val newKeep = args.get[Keep]("newKeep")
        val keeper = args.get[User]("keeper")
        val keeperImage = args.get[String]("keeperImage")
        val libraryKept = args.get[Library]("libraryKept")
        NotificationInfo(
          url = newKeep.url,
          imageUrl = keeperImage,
          title = s"New Keep in ${libraryKept.name}",
          body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
          linkText = "Go to Page",
          extraJson = Some(Json.obj(
            "keeper" -> BasicUser.fromUser(keeper),
            "library" -> Json.toJson(Json.obj()), // TODO fill in with library info
            "keep" -> Json.obj(
              "id" -> newKeep.externalId,
              "url" -> newKeep.url
            )
          ))
        )
    }
  }
}

// todo is this ever really used/called?
case class NewKeepActivity(
    recipient: Recipient,
    time: DateTime,
    keeperId: Id[User],
    keepId: Id[Keep],
    libraryId: Id[Library]) extends KeepEvent {

  val kind = NewKeepActivity

}

object NewKeepActivity extends NotificationKind[NewKeepActivity] {

  override val name: String = "new_keep_activity"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(NewKeepActivity.apply, unlift(NewKeepActivity.unapply))

  override def shouldGroupWith(newEvent: NewKeepActivity, existingEvents: Set[NewKeepActivity]): Boolean = false

  override val info = {
    import NeedsInfo._
    usingOne[NewKeepActivity](
      "libraryKept".arg(_.libraryId, library), "keeper".arg(_.keeperId, user), "newKeep".arg(_.keepId, keep),
      "libraryKeptUrl".arg(_.libraryId, libraryUrl), "keeperImage".arg(_.keeperId, userImage)
    ) {
      case Fetched(args) =>
        val libraryKept = args.get[Library]("libraryKept")
        val keeper = args.get[User]("keeper")
        val keeperBasic = BasicUser.fromUser(keeper)
        val newKeep = args.get[Keep]("newKeep")
        val libraryKeptUrl = args.get[String]("libraryKeptUrl")
        val keeperImage = args.get[String]("keeperImage")
        NotificationInfo(
          url = libraryKeptUrl,
          imageUrl = keeperImage,
          title = s"New Keep in ${libraryKept.name}",
          body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
          linkText = "Go to library",
          extraJson = Some(Json.obj(
            "keeper" -> keeperBasic,
            "library" -> Json.toJson(Json.obj()) , //todo fix
            "keep" -> Json.obj(
              "id" -> newKeep.externalId,
              "url" -> newKeep.url
            )
          ))
        )
    }
  }

}
