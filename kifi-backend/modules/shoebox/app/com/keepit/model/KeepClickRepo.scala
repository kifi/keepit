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
  def getByClickerAndKeepId(clickerId:Id[User], keepId:Id[Keep])(implicit r:RSession):Seq[KeepClick]
  def getRecentClicksByClickerAndKeepId(clickerId:Id[User], keepId:Id[Keep], since:DateTime = currentDateTime.minusMinutes(15))(implicit r:RSession):Seq[KeepClick]
  def getMostRecentClickByClickerAndKeepId(clickerId:Id[User], keepId:Id[Keep])(implicit r:RSession):Option[KeepClick]
  def getMostRecentClickByClickerAndUriId(clickerId:Id[User], uriId:Id[NormalizedURI])(implicit r:RSession):Option[KeepClick]
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
    def uuid = column[ExternalId[ArticleSearchResult]]("uuid", O.NotNull)
    def numKeepers = column[Int]("num_keepers", O.NotNull)
    def keeperId = column[Id[User]]("keeper_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def uriId  = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def clickerId = column[Id[User]]("clicker_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, uuid, numKeepers, keeperId, keepId, uriId, clickerId) <> ((KeepClick.apply _).tupled, KeepClick.unapply)
  }

  def table(tag:Tag) = new KeepClicksTable(tag)
  initTable()

  override def deleteCache(model: KeepClick)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: KeepClick)(implicit session: RSession): Unit = {}

  def getByClickerAndKeepId(clickerId: Id[User], keepId: Id[Keep])(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.clickerId === clickerId && r.keepId === keepId && r.state === KeepClicksStates.ACTIVE)) yield r).list()
  }

  def getRecentClicksByClickerAndKeepId(clickerId: Id[User], keepId: Id[Keep], since: DateTime)(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.clickerId === clickerId && r.keepId === keepId && r.state === KeepClicksStates.ACTIVE && r.createdAt >= since)) yield r).list()
  }

  def getMostRecentClickByClickerAndKeepId(clickerId: Id[User], keepId: Id[Keep])(implicit r: RSession): Option[KeepClick] = {
    (for (r <- rows if (r.clickerId === clickerId && r.keepId === keepId && r.state === KeepClicksStates.ACTIVE)) yield r).sortBy(_.createdAt.desc).firstOption()
  }

  def getMostRecentClickByClickerAndUriId(clickerId: Id[User], uriId: Id[NormalizedURI])(implicit r: RSession): Option[KeepClick] = {
    (for (r <- rows if (r.clickerId === clickerId && r.uriId === uriId && r.state === KeepClicksStates.ACTIVE)) yield r).sortBy(_.createdAt.desc).firstOption()
  }

  def getClicksByUUID(uuid: ExternalId[ArticleSearchResult])(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.uuid === uuid && r.state === KeepClicksStates.ACTIVE)) yield r).list()
  }

  def getByKeepId(keepId: Id[Keep])(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.keepId === keepId && r.state === KeepClicksStates.ACTIVE)) yield r).list()
  }

}
