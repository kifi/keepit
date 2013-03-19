package com.keepit.realtime

import java.util.concurrent.ConcurrentHashMap
import com.keepit.common.db.Id
import com.keepit.model._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{JsObject, JsValue}
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.logging.Logging
import play.api.libs.json.Json
import com.keepit.common.db.slick.Database

@Singleton
class Streams @Inject() (db: Database, commentRepo: CommentRepo) {
  def welcome(userId: Id[User]): Enumerator[JsValue] = {
    Enumerator(Json.obj("connected" -> true, "userStatus" -> "awesome"))
  }

  def unreadNotifications(userId: Id[User]): Enumerator[JsValue] = {
    Enumerator(Json.obj("unreadNotifications" -> 0))
  }

}