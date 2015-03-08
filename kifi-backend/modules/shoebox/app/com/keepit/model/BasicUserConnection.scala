package com.keepit.model

import com.keepit.common.cache._
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.serializer.ArrayBinaryFormat
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class BasicUserConnection(userId: Id[User], createdAt: DateTime)

object BasicUserConnection {
  implicit def format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat)
  )(BasicUserConnection.apply, unlift(BasicUserConnection.unapply))
}

case class BasicUserConnectionIdKey(userId: Id[User]) extends Key[Seq[BasicUserConnection]] {
  override val version = 2
  val namespace = "basic_user_connections"
  def toKey(): String = userId.id.toString
}

class BasicUserConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BasicUserConnectionIdKey, Seq[BasicUserConnection]](stats, accessLog, inner, outer: _*)

