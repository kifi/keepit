package com.keepit.rover.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ Database, DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class RoverHttpProxy(
    id: Option[Id[RoverHttpProxy]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[RoverHttpProxy] = RoverHttpProxyStates.ACTIVE,
    alias: String,
    host: String,
    port: Int,
    scheme: ProxyScheme,
    username: Option[String],
    password: Option[String]) extends ModelWithState[RoverHttpProxy] {

  def withId(id: Id[RoverHttpProxy]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = this.state == RoverHttpProxyStates.ACTIVE

}

object RoverHttpProxy {

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[RoverHttpProxy]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[RoverHttpProxy]) and
    (__ \ 'alias).format[String] and
    (__ \ 'host).format[String] and
    (__ \ 'port).format[Int] and
    (__ \ 'scheme).format[ProxyScheme] and
    (__ \ 'username).formatNullable[String] and
    (__ \ 'password).formatNullable[String]
  )(RoverHttpProxy.apply, unlift(RoverHttpProxy.unapply))

  def applyFromDbRow(id: Option[Id[RoverHttpProxy]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[RoverHttpProxy],
    alias: String,
    host: String,
    port: Int,
    scheme: String,
    username: Option[String],
    password: Option[String]) = RoverHttpProxy(id, createdAt, updatedAt, state, alias, host, port, ProxyScheme.fromName(scheme), username, password)

  def unapplyToDbRow(proxy: RoverHttpProxy) = Some(
    proxy.id,
    proxy.createdAt,
    proxy.updatedAt,
    proxy.state,
    proxy.alias,
    proxy.host,
    proxy.port,
    ProxyScheme.toName(proxy.scheme),
    proxy.username,
    proxy.password)

}

object RoverHttpProxyStates extends States[RoverHttpProxy]

@ImplementedBy(classOf[RoverHttpProxyRepoImpl])
trait RoverHttpProxyRepo extends Repo[RoverHttpProxy] {
  def allActive()(implicit session: RSession): Seq[RoverHttpProxy]
  def getByAlias(alias: String)(implicit session: RSession): Option[RoverHttpProxy]
}

@Singleton
class RoverHttpProxyRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val cache: RoverHttpProxyAllCache)
    extends DbRepo[RoverHttpProxy] with RoverHttpProxyRepo {

  import db.Driver.simple._

  type RepoImpl = HttpProxyTable
  class HttpProxyTable(tag: Tag) extends RepoTable[RoverHttpProxy](db, tag, "rover_http_proxy") {
    def alias = column[String]("alias", O.NotNull)
    def host = column[String]("host", O.NotNull)
    def port = column[Int]("port", O.NotNull)
    def scheme = column[String]("scheme", O.NotNull)
    def username = column[String]("username", O.Nullable)
    def password = column[String]("password", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, alias, host, port, scheme, username.?, password.?) <> ((RoverHttpProxy.applyFromDbRow _).tupled, RoverHttpProxy.unapplyToDbRow)
  }

  def table(tag: Tag) = new HttpProxyTable(tag)
  initTable()

  override def invalidateCache(HttpProxy: RoverHttpProxy)(implicit session: RSession): Unit = {}

  override def deleteCache(model: RoverHttpProxy)(implicit session: RSession): Unit = {}

  def allActive()(implicit session: RSession): Seq[RoverHttpProxy] = {
    val result = cache.getOrElse(RoverHttpProxyAllKey()) {
      (for (f <- rows if f.state === RoverHttpProxyStates.ACTIVE) yield f).list
    }
    result.sortBy(_.id.get.id)
  }

  def getByAlias(alias: String)(implicit session: RSession) = allActive().find(_.alias == alias)
}

case class RoverHttpProxyAllKey() extends Key[Seq[RoverHttpProxy]] {
  override val version = 1
  val namespace = "rover_http_proxy_all"
  def toKey(): String = "all"
}

class RoverHttpProxyAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RoverHttpProxyAllKey, Seq[RoverHttpProxy]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
