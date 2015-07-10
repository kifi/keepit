package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.db.ModelWithState
import com.keepit.common.db.State
import com.keepit.common.db.States
import com.keepit.common.time._
import com.keepit.common.time.DateTimeJsonFormat
import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class HttpProxy(
    id: Option[Id[HttpProxy]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[HttpProxy] = HttpProxyStates.ACTIVE,
    alias: String,
    hostname: String,
    port: Int,
    scheme: String,
    username: Option[String],
    password: Option[String]) extends ModelWithState[HttpProxy] {

  def withId(id: Id[HttpProxy]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = this.state == HttpProxyStates.ACTIVE

}

object HttpProxy {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[HttpProxy]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[HttpProxy]) and
    (__ \ 'alias).format[String] and
    (__ \ 'hostname).format[String] and
    (__ \ 'port).format[Int] and
    (__ \ 'scheme).format[String] and
    (__ \ 'username).formatNullable[String] and
    (__ \ 'password).formatNullable[String]
  )(HttpProxy.apply, unlift(HttpProxy.unapply))
}

case class HttpProxyAllKey() extends Key[Seq[HttpProxy]] {
  override val version = 1
  val namespace = "http_proxy_all"
  def toKey(): String = "all"
}

class HttpProxyAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[HttpProxyAllKey, Seq[HttpProxy]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object HttpProxyStates extends States[HttpProxy]
