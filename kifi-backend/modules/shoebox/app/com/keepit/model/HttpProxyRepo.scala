package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.db.slick.DbRepo
import com.keepit.common.db.slick.Repo
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.RSession

@ImplementedBy(classOf[HttpProxyRepoImpl])
trait HttpProxyRepo extends Repo[HttpProxy] {
  def allActive()(implicit session: RSession): Seq[HttpProxy]
  def getByAlias(alias: String)(implicit session: RSession): Option[HttpProxy]
}

@Singleton
class HttpProxyRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val httpProxyAllCache: HttpProxyAllCache)
    extends DbRepo[HttpProxy] with HttpProxyRepo {

  import db.Driver.simple._

  type RepoImpl = HttpProxyTable
  class HttpProxyTable(tag: Tag) extends RepoTable[HttpProxy](db, tag, "http_proxy") {
    def alias = column[String]("alias", O.NotNull)
    def hostname = column[String]("hostname", O.NotNull)
    def port = column[Int]("port", O.NotNull)
    def scheme = column[String]("scheme", O.NotNull)
    def username = column[String]("username", O.Nullable)
    def password = column[String]("password", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, alias, hostname, port, scheme, username.?, password.?) <> ((HttpProxy.apply _).tupled, HttpProxy.unapply _)
  }

  def table(tag: Tag) = new HttpProxyTable(tag)
  initTable()

  private var allMemCache: Option[Seq[HttpProxy]] = None

  override def invalidateCache(HttpProxy: HttpProxy)(implicit session: RSession): Unit = {
    httpProxyAllCache.remove(HttpProxyAllKey())
    allMemCache = None
  }

  override def deleteCache(model: HttpProxy)(implicit session: RSession): Unit = {
    httpProxyAllCache.remove(HttpProxyAllKey())
    allMemCache = None
  }

  def allActive()(implicit session: RSession): Seq[HttpProxy] =
    allMemCache.getOrElse {
      val result = httpProxyAllCache.getOrElse(HttpProxyAllKey()) {
        (for (f <- rows if f.state === HttpProxyStates.ACTIVE) yield f).list
      }
      allMemCache = Some(result)
      result.sortBy(_.id.get.id)
    }

  def getByAlias(alias: String)(implicit session: RSession) = allActive().find(_.alias == alias)
}
