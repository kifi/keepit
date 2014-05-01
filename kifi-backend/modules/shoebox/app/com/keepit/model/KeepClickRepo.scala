package com.keepit.model

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db._
import com.keepit.common.time._
import com.google.inject.{Singleton, ImplementedBy, Inject}
import org.joda.time.DateTime
import com.keepit.search.ArticleSearchResult

@ImplementedBy(classOf[KeepClickRepoImpl])
trait KeepClickRepo extends Repo[KeepClick] {
  def getClicksByUUID(uuid:ExternalId[ArticleSearchResult])(implicit r:RSession):Seq[KeepClick]
  def getByKeepId(keepId:Id[Keep])(implicit r:RSession):Seq[KeepClick]
}


@Singleton
class KeepClickRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock) extends DbRepo[KeepClick] with KeepClickRepo {

  import db.Driver.simple._

  type RepoImpl = KeepClicksTable
  class KeepClicksTable(tag: Tag) extends RepoTable[KeepClick](db, tag, "keep_click") {
    def searchUUID = column[ExternalId[ArticleSearchResult]]("search_uuid", O.NotNull)
    def numKeepers = column[Int]("num_keepers", O.NotNull)
    def keeperId = column[Id[User]]("keeper_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def uriId  = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, searchUUID, numKeepers, keeperId, keepId, uriId) <> ((KeepClick.apply _).tupled, KeepClick.unapply)
  }

  def table(tag:Tag) = new KeepClicksTable(tag)
  initTable()

  override def deleteCache(model: KeepClick)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: KeepClick)(implicit session: RSession): Unit = {}

  def getClicksByUUID(uuid: ExternalId[ArticleSearchResult])(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.searchUUID === uuid && r.state === KeepClicksStates.ACTIVE)) yield r).list()
  }

  def getByKeepId(keepId: Id[Keep])(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.keepId === keepId && r.state === KeepClicksStates.ACTIVE)) yield r).list()
  }

}
