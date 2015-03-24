package com.keepit.model.helprank

import java.sql.{ SQLException, Timestamp }

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.ArticleSearchResult
import org.joda.time.DateTime
import com.keepit.common.performance._

import scala.slick.jdbc.SetParameter
import com.keepit.common.db.slick.StaticQueryFixed.interpolation

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
    val clock: Clock,
    uriDiscoveryCountCache: UriDiscoveryCountCache) extends DbRepo[KeepDiscovery] with KeepDiscoveryRepo {

  import db.Driver.simple._

  type RepoImpl = KeepDiscoveriesTable
  class KeepDiscoveriesTable(tag: Tag) extends RepoTable[KeepDiscovery](db, tag, "keep_click") {
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

  override def deleteCache(model: KeepDiscovery)(implicit session: RSession): Unit = {
    uriDiscoveryCountCache.remove(UriDiscoveryCountKey(model.uriId))
  }
  override def invalidateCache(model: KeepDiscovery)(implicit session: RSession): Unit = deleteCache(model)

  def getDiscoveriesByUUID(uuid: ExternalId[ArticleSearchResult])(implicit r: RSession): Seq[KeepDiscovery] = {
    (for (r <- rows if (r.hitUUID === uuid && r.state === KeepDiscoveryStates.ACTIVE)) yield r).list
  }

  def getByKeepId(keepId: Id[Keep])(implicit r: RSession): Seq[KeepDiscovery] = {
    (for (r <- rows if (r.keepId === keepId && r.state === KeepDiscoveryStates.ACTIVE)) yield r).list
  }

  def getDiscoveriesByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Seq[KeepDiscovery] = {
    (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r).sortBy(_.createdAt.desc).list
  }

  def getDiscoveryCountByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Int = {
    (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r).length.run
  }

  def getDiscoveryCountByURI(uriId: Id[NormalizedURI], since: DateTime)(implicit r: RSession): Int = {
    sql"select count(distinct (hit_uuid)) from keep_click where uri_id=$uriId and created_at >= $since".as[Int].first
  }

  def getDiscoveryCountsByURIs(uriIds: Set[Id[NormalizedURI]], since: DateTime)(implicit session: RSession): Map[Id[NormalizedURI], Int] = timing(s"getDiscoveryCountsByURIs(sz=${uriIds.size})") {
    if (uriIds.isEmpty) Map.empty
    else {
      val valueMap = uriDiscoveryCountCache.bulkGetOrElse(uriIds.map(UriDiscoveryCountKey(_)).toSet) { keys =>
        val buf = collection.mutable.ArrayBuilder.make[(Id[NormalizedURI], Int)]
        val missing = keys.map(_.uriId)
        missing.grouped(20).foreach { ids =>
          val params = Seq.fill(ids.size)("?").mkString(",")
          val stmt = session.getPreparedStatement(s"select uri_id, count(distinct (hit_uuid)) from keep_click where uri_id in ($params) group by uri_id;")
          ids.zipWithIndex.foreach {
            case (uriId, idx) =>
              stmt.setLong(idx + 1, uriId.id)
          }
          val res = timing(s"getDiscoveryCountsByURIs(sz=${ids.size};ids=$ids)") { stmt.execute() }
          if (!res) throw new SQLException(s"[getDiscoveryCountsByURIs] ($stmt) failed to execute")
          val rs = stmt.getResultSet()
          while (rs.next()) {
            val uriId = Id[NormalizedURI](rs.getLong(1))
            val count = rs.getInt(2)
            buf += (uriId -> count)
          }
        }
        val resMap = buf.result.toMap
        missing.map { uriId =>
          UriDiscoveryCountKey(uriId) -> resMap.getOrElse(uriId, 0)
        }.toMap
      }
      valueMap.map { case (k, v) => (k.uriId -> v) }
    }
  }

  def getDiscoveryCountsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Map[Id[Keep], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r)
      .groupBy(_.keepId)
      .map { case (kId, kc) => (kId, kc.length) }
    q.toMap
  }

  def getUriDiscoveriesWithCountsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Seq[(Id[NormalizedURI], Id[Keep], Id[User], Int)] = {
    sql"select uri_id, keep_id, keeper_id, count(*) c from keep_click where created_at >= $since and keeper_id=$userId group by uri_id order by keep_id desc".as[(Id[NormalizedURI], Id[Keep], Id[User], Int)].list
  }

  def getUriDiscoveryCountsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Map[Id[NormalizedURI], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.createdAt >= since)) yield r)
      .groupBy(_.uriId)
      .map { case (uriId, kc) => (uriId, kc.length) }
    q.toMap
  }

  def getDiscoveryCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]], since: DateTime)(implicit r: RSession): Map[Id[Keep], Int] = {
    if (keepIds.isEmpty) Map.empty[Id[Keep], Int]
    else {
      val q = (for (r <- rows if (r.keeperId === userId && r.state === KeepDiscoveryStates.ACTIVE && r.keepId.inSet(keepIds) && r.createdAt >= since)) yield r)
        .groupBy(_.keepId)
        .map { case (kId, kc) => (kId, kc.length) }
      q.toMap
    }
  }
}
