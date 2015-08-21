package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.{ Library, Keep, User }
import com.keepit.notify.info.{ UsingDbSubset, NotificationInfo, NeedInfo }
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationEvent, NotificationKind, Recipient }
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

object LibraryNewKeep extends NonGroupingNotificationKind[LibraryNewKeep] {

  override val name: String = "library_new_keep"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewKeep.apply, unlift(LibraryNewKeep.unapply))

  override def info(event: LibraryNewKeep): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.keeperId), userImageUrl(event.keeperId), library(event.libraryId), keep(event.keepId),
      libraryInfo(event.libraryId)
    )) { subset =>
      val newKeep = subset.keep(event.keepId)
      val keeper = subset.user(event.keeperId)
      val keeperImage = subset.userImageUrl(event.keeperId)
      val libraryKept = subset.library(event.libraryId)
      val libraryKeptInfo = subset.libraryInfo(event.libraryId)
      NotificationInfo(
        url = newKeep.url,
        imageUrl = keeperImage,
        title = s"New keep in ${libraryKept.name}",
        body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
        linkText = "Go to page",
        extraJson = Some(Json.obj(
          "keeper" -> BasicUser.fromUser(keeper),
          "library" -> Json.toJson(libraryKeptInfo),
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

object NewKeepActivity extends NonGroupingNotificationKind[NewKeepActivity] {

  override val name: String = "new_keep_activity"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(NewKeepActivity.apply, unlift(NewKeepActivity.unapply))

  override def info(event: NewKeepActivity): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      library(event.libraryId), user(event.keeperId), keep(event.keepId), libraryUrl(event.libraryId),
      userImageUrl(event.keeperId), libraryInfo(event.libraryId)
    )) { subset =>
      val libraryKept = subset.library(event.libraryId)
      val keeper = subset.user(event.keeperId)
      val keeperBasic = BasicUser.fromUser(keeper)
      val newKeep = subset.keep(event.keepId)
      val libraryKeptUrl = subset.libraryUrl(event.libraryId)
      val keeperImage = subset.userImageUrl(event.keeperId)
      val libraryKeptInfo = subset.libraryInfo(event.libraryId)
      NotificationInfo(
        url = libraryKeptUrl,
        imageUrl = keeperImage,
        title = s"New Keep in ${libraryKept.name}",
        body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
        linkText = "Go to library",
        extraJson = Some(Json.obj(
          "keeper" -> keeperBasic,
          "library" -> Json.toJson(libraryKeptInfo),
          "keep" -> Json.obj(
            "id" -> newKeep.externalId,
            "url" -> newKeep.url
          )
        ))
      )
    }
  }

}
