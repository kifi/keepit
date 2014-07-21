package com.keepit.curator.model

import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{DataBaseComponent, DbRepo}
import com.keepit.common.time.Clock
import com.keepit.model.{NormalizedURI}


@ImplementedBy(classOf[CuratorUriInfoRepoImpl])
trait CuratorUriInfoRepo extends DbRepo[CuratorUriInfo]{
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[CuratorUriInfo]
}

@Singleton
class CuratorUriInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock) extends DbRepo[CuratorUriInfo] with CuratorUriInfoRepo {
  import db.Driver.simple._

  type RepoImpl = CuratorUriInfoTable

  class CuratorUriInfoTable(tag: Tag) extends RepoTable[CuratorUriInfo](db, tag, "curator_keep_info") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def score = column[Double]("uri_score", O.NotNull)
    def * = (id.?, createdAt, updatedAt, uriId, score, state) <> ((CuratorUriInfo.apply _).tupled, CuratorUriInfo.unapply _)
  }

  def table(tag: Tag) = new CuratorUriInfoTable(tag)
  initTable()

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[CuratorUriInfo] = {
    (for (row <- rows if row.uriId === uriId) yield row).firstOption
  }

  def deleteCache(model: CuratorUriInfo)(implicit session: RSession): Unit = {}

  def invalidateCache(model: CuratorUriInfo)(implicit session: RSession): Unit = {}
}

