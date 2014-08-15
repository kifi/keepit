package com.keepit.model

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db._
import com.keepit.common.time._
import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.search.ArticleSearchResult
import org.joda.time.DateTime
import scala.slick.jdbc.{ StaticQuery => Q }
import Q.interpolation

@ImplementedBy(classOf[KeepDiscoveryRepoImpl])
trait KeepDiscoveryRepo extends Repo[KeepDiscovery] {
  def getDiscoveriesByUUID(uuid: ExternalId[ArticleSearchResult])(implicit r: RSession): Seq[KeepDiscovery]
  def getByKeepId(keepId: Id[Keep])(implicit r: RSession): Seq[KeepDiscovery]
  def getDiscoveriesByKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Seq[KeepDiscovery]
  def getDiscoveryCountByKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Int
  def getDiscoveryCountByURI(uriId: Id[NormalizedURI], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Int
  def getDiscoveryCountsByURIs(uriIds: Set[Id[NormalizedURI]], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Map[Id[NormalizedURI], Int]
  def getDiscoveryCountsByKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Map[Id[Keep], Int]
  def getUriDiscoveriesWithCountsByKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Seq[(Id[NormalizedURI], Id[Keep], Id[User], Int)]
  def getUriDiscoveryCountsByKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Map[Id[NormalizedURI], Int]
  def getDiscoveryCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Map[Id[Keep], Int]
}

@Singleton
class KeepDiscoveryRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[KeepDiscovery] with KeepDiscoveryRepo {

  import db.Driver.simple._

  type RepoImpl = KeepDiscoveriesTable
  class KeepDiscoveriesTable(tag: Tag) extends RepoTable[KeepDiscovery](db, tag, "keep_click") { // todo(ray): rename => keep_discovery
    def hitUUID = column[ExternalId[ArticleSearchResult]]("hit_uuid", O.NotNull)
    def numKeepers = column[Int]("num_keepers", O.NotNull)
    def keeperId = column[Id[User]]("keeper_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def origin = column[String]("origin", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, hitUUID, numKeepers, keeperId, keepId, uriId, origin.?) <> ((KeepDiscovery.apply _).tupled, KeepDiscovery.unapply)
  }

  def table(tag: Tag) = new KeepDiscoveriesTable(tag)
  initTable()

  override def deleteCache(model: KeepDiscovery)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: KeepDiscovery)(implicit session: RSession): Unit = {}

  def getDiscoveriesByUUID(uuid: ExternalId[ArticleSearchResult])(implicit r: RSession): Seq[KeepDiscovery] = {
    (for (r <- rows if (r.hitUUID === uuid && r.state === KeepDiscoveryStates.ACTIVE)) yield r).list()
  }

  def getByKeepId(keepId: Id[Keep])(implicit r: RSession): Seq[KeepDiscovery] = {
    (for (r <- rows if (r.keepId === keepId && r.state === KeepDiscoveryStates.ACTIVE)) yield r).list()
  }

  def getDiscoveriesByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Seq[KeepDiscovery] = {
    (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r).sortBy(_.createdAt.desc).list()
  }

  def getDiscoveryCountByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Int = {
    (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r).length.run
  }

  def getDiscoveryCountByURI(uriId: Id[NormalizedURI], since: DateTime)(implicit r: RSession): Int = {
    val q = (for (r <- rows if (r.uriId === uriId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r)
      .groupBy(_.hitUUID)
      .map { case (uuid, kc) => (uuid, kc.length) }
    q.length.run
  }

  def getDiscoveryCountsByURIs(uriIds: Set[Id[NormalizedURI]], since: DateTime)(implicit r: RSession): Map[Id[NormalizedURI], Int] = { // todo(ray): optimize
    uriIds.map { uriId => uriId -> getDiscoveryCountByURI(uriId, since) }.toMap
  }

  def getDiscoveryCountsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Map[Id[Keep], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r)
      .groupBy(_.keepId)
      .map { case (kId, kc) => (kId, kc.length) }
    q.toMap()
  }

  def getUriDiscoveriesWithCountsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Seq[(Id[NormalizedURI], Id[Keep], Id[User], Int)] = {
    sql"select uri_id, keep_id, keeper_id, count(*) c from keep_click where created_at >= $since and keeper_id=$userId group by uri_id order by keep_id desc".as[(Id[NormalizedURI], Id[Keep], Id[User], Int)].list()
  }

  def getUriDiscoveryCountsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Map[Id[NormalizedURI], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r)
      .groupBy(_.uriId)
      .map { case (uriId, kc) => (uriId, kc.length) }
    q.toMap()
  }

  def getDiscoveryCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]], since: DateTime)(implicit r: RSession): Map[Id[Keep], Int] = {
    if (keepIds.isEmpty) Map.empty[Id[Keep], Int]
    else {
      val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.keepId.inSet(keepIds) && r.createdAt >= since)) yield r)
        .groupBy(_.keepId)
        .map { case (kId, kc) => (kId, kc.length) }
      q.toMap()
    }
  }
}
