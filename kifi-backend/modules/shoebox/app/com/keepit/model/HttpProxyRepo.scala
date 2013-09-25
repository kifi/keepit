package com.keepit.model

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.db.slick.DbRepo
import com.keepit.common.db.slick.DBSession
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
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[HttpProxy](db, "http_proxy") {
    def alias = column[String]("alias", O.NotNull)
    def hostname = column[String]("hostname", O.NotNull)
    def port = column[Int]("port", O.NotNull)
    def scheme = column[String]("scheme", O.NotNull)
    def username = column[String]("username", O.Nullable)
    def password = column[String]("password", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ state ~ alias ~ hostname ~ port ~ scheme ~ username.? ~ password.? <> (HttpProxy.apply _, HttpProxy.unapply _)
  }

  private var allMemCache: Option[Seq[HttpProxy]] = None

  override def invalidateCache(HttpProxy: HttpProxy)(implicit session: RSession) = {
    httpProxyAllCache.remove(HttpProxyAllKey())
    allMemCache = None
    HttpProxy
  }

  def allActive()(implicit session: RSession): Seq[HttpProxy] =
    allMemCache.getOrElse {
      val result = httpProxyAllCache.getOrElse(HttpProxyAllKey()) {
        (for(f <- table if f.state === HttpProxyStates.ACTIVE) yield f).list
      }
      allMemCache = Some(result)
      result.sortBy(_.id.get.id)
    }

  def getByAlias(alias: String)(implicit session: RSession) = allActive().find(_.alias == alias)
}
