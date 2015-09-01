package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.{ Library, Keep, User }
import com.keepit.notify.info._
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait LibraryNewKeepImpl extends NonGroupingNotificationKind[LibraryNewKeep] {

  override val name: String = "library_new_keep"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewKeep.apply, unlift(LibraryNewKeep.unapply))

  override def info(event: LibraryNewKeep): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestLibrary(event.libraryId), RequestKeep(event.keepId)
    )) { batched =>
      val newKeep = RequestKeep(event.keepId).lookup(batched)
      val keeper = RequestUserExternal(newKeep.ownerId).lookup(batched)
      val libraryKept = RequestLibrary(event.libraryId).lookup(batched)
      NotificationInfo(
        url = newKeep.url,
        image = UserImage(keeper),
        title = s"New keep in ${libraryKept.name}",
        body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
        linkText = "Go to page",
        extraJson = Some(Json.obj(
          "keeper" -> keeper,
          "library" -> Json.toJson(libraryKept),
          "keep" -> Json.obj(
            "id" -> newKeep.id,
            "url" -> newKeep.url
          )
        ))
      )
    }
  }
}

trait NewKeepActivityImpl extends NonGroupingNotificationKind[NewKeepActivity] {

  override val name: String = "new_keep_activity"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(NewKeepActivity.apply, unlift(NewKeepActivity.unapply))

  override def info(event: NewKeepActivity): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestLibrary(event.libraryId), RequestKeep(event.keepId)
    )) { batched =>
      val libraryKept = RequestLibrary(event.libraryId).lookup(batched)
      val newKeep = RequestKeep(event.keepId).lookup(batched)
      val keeper = RequestUserExternal(newKeep.ownerId).lookup(batched)
      NotificationInfo(
        url = libraryKeptInfo.path.encode.absolute,
        image = UserImage(keeper),
        title = s"New Keep in ${libraryKeptInfo.name}",
        body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
        linkText = "Go to library",
        extraJson = Some(Json.obj(
          "keeper" -> keeper,
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
