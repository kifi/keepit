package com.keepit.realtime

import java.util.concurrent.ConcurrentHashMap
import com.keepit.common.db.Id
import com.keepit.model._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{JsObject, JsValue, JsArray}
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.logging.Logging
import play.api.libs.json.Json
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock

@Singleton
class Streams @Inject() (
  db: Database,
  clock: Clock,
  commentRepo: CommentRepo,
  userNotifyRepo: UserNotificationRepo,
  userNotifier: UserNotifier,
  userNotificationStream: UserNotificationStreamManager
) {
  def welcome(userId: Id[User]): Enumerator[JsArray] = {
    Enumerator(Json.arr("welcome", Json.obj("connected" -> true, "userStatus" -> "awesome")))
  }

  def unreadNotifications(userId: Id[User]): Enumerator[JsArray] = {
    val unread = db.readOnly { implicit session =>
      userNotifyRepo.getUnreadCount(userId)
    }
    Enumerator(Json.arr("notification", Json.obj("unread" -> unread)))
  }

  def sendUnreadCount(userId: Id[User]) = {
    val unread = db.readOnly { implicit session =>
      userNotifyRepo.getUnreadCount(userId)
    }
    userNotificationStream.push(userId, "notification", Json.obj("unread" -> unread))
  }

}