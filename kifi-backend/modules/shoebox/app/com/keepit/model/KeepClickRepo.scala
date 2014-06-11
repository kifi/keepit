package com.keepit.model

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db._
import com.keepit.common.time._
import com.google.inject.{Singleton, ImplementedBy, Inject}
import org.joda.time.DateTime
import com.keepit.search.ArticleSearchResult
import com.keepit.heimdal.SanitizedKifiHit

@ImplementedBy(classOf[KeepClickRepoImpl])
trait KeepClickRepo extends Repo[KeepClick] {
  def getClicksByUUID(uuid:ExternalId[SanitizedKifiHit])(implicit r:RSession):Seq[KeepClick]
  def getByKeepId(keepId:Id[Keep])(implicit r:RSession):Seq[KeepClick]
  def getClicksByKeeper(userId:Id[User], since:DateTime = currentDateTime.minusMonths(1))(implicit r:RSession):Seq[KeepClick]
  def getClickCountByKeeper(userId:Id[User], since:DateTime = currentDateTime.minusMonths(1))(implicit r:RSession):Int
  def getClickCountsByKeeper(userId:Id[User], since:DateTime = currentDateTime.minusMonths(1))(implicit r:RSession):Map[Id[Keep], Int]
  def getUriClickCountsByKeeper(userId:Id[User], since:DateTime = currentDateTime.minusMonths(1))(implicit r:RSession):Map[Id[NormalizedURI], Int]
  def getClickCountsByKeepIds(userId:Id[User], keepIds:Set[Id[Keep]], since:DateTime = currentDateTime.minusMonths(1))(implicit r:RSession):Map[Id[Keep],Int]
}


@Singleton
class KeepClickRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock) extends DbRepo[KeepClick] with KeepClickRepo {

  import db.Driver.simple._

  type RepoImpl = KeepClicksTable
  class KeepClicksTable(tag: Tag) extends RepoTable[KeepClick](db, tag, "keep_click") {
    def hitUUID = column[ExternalId[SanitizedKifiHit]]("hit_uuid", O.NotNull)
    def numKeepers = column[Int]("num_keepers", O.NotNull)
    def keeperId = column[Id[User]]("keeper_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def uriId  = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def origin = column[String]("origin", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, hitUUID, numKeepers, keeperId, keepId, uriId, origin.?) <> ((KeepClick.apply _).tupled, KeepClick.unapply)
  }

  def table(tag:Tag) = new KeepClicksTable(tag)
  initTable()

  override def deleteCache(model: KeepClick)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: KeepClick)(implicit session: RSession): Unit = {}

  def getClicksByUUID(uuid: ExternalId[SanitizedKifiHit])(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.hitUUID === uuid && r.state === KeepClickStates.ACTIVE)) yield r).list()
  }

  def getByKeepId(keepId: Id[Keep])(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.keepId === keepId && r.state === KeepClickStates.ACTIVE)) yield r).list()
  }

  def getClicksByKeeper(userId: Id[User], since:DateTime)(implicit r: RSession): Seq[KeepClick] = {
    (for (r <- rows if (r.keeperId === userId && r.state === KeepClickStates.ACTIVE && r.createdAt >= since)) yield r).sortBy(_.createdAt.desc).list()
  }

  def getClickCountByKeeper(userId: Id[User], since:DateTime)(implicit r: RSession): Int = {
    (for (r <- rows if (r.keeperId === userId && r.state === KeepClickStates.ACTIVE && r.createdAt >= since)) yield r).length.run
  }

  def getClickCountsByKeeper(userId: Id[User], since:DateTime)(implicit r: RSession): Map[Id[Keep], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepClickStates.ACTIVE && r.createdAt >= since)) yield r)
      .groupBy(_.keepId)
      .map { case (kId, kc) => (kId, kc.length) }
    q.toMap()
  }

  def getUriClickCountsByKeeper(userId: Id[User], since:DateTime)(implicit r: RSession): Map[Id[NormalizedURI], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepClickStates.ACTIVE && r.createdAt >= since)) yield r)
      .groupBy(_.uriId)
      .map { case (uriId, kc) => (uriId, kc.length) }
    q.toMap()
  }

  def getClickCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]], since:DateTime)(implicit r: RSession): Map[Id[Keep], Int] = {
    if (keepIds.isEmpty) Map.empty[Id[Keep], Int]
    else {
      val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepClickStates.ACTIVE && r.keepId.inSet(keepIds) && r.createdAt >= since)) yield r)
        .groupBy(_.keepId)
        .map { case (kId, kc) => (kId, kc.length) }
      q.toMap()
    }
  }
}
