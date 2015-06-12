package com.keepit.model.helprank

import java.sql.SQLException

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.performance._
import com.keepit.model._
import org.joda.time.DateTime

import com.keepit.common.db.slick.StaticQueryFixed.interpolation

@ImplementedBy(classOf[ReKeepRepoImpl])
trait ReKeepRepo extends Repo[ReKeep] {
  def getReKeep(keeperId: Id[User], uriId: Id[NormalizedURI], rekeeperId: Id[User])(implicit r: RSession): Option[ReKeep]
  def getReKeepsByKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Seq[ReKeep]
  def getAllReKeepsByKeeper(userId: Id[User])(implicit r: RSession): Seq[ReKeep]
  def getReKeepsByReKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Seq[ReKeep]
  def getAllReKeepsByReKeeper(userId: Id[User])(implicit r: RSession): Seq[ReKeep]
  def getReKeepCountByKeeper(userId: Id[User])(implicit r: RSession): Int
  def getReKeepCountsByKeeper(userId: Id[User], since: DateTime = currentDateTime.minusYears(3))(implicit r: RSession): Map[Id[Keep], Int]
  def getReKeepCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]])(implicit r: RSession): Map[Id[Keep], Int]
  def getReKeepCountByURI(uriId: Id[NormalizedURI])(implicit r: RSession): Int
  def getReKeepCountsByURIs(uriIds: Set[Id[NormalizedURI]])(implicit r: RSession): Map[Id[NormalizedURI], Int]
  def getUriReKeepsWithCountsByKeeper(userId: Id[User])(implicit r: RSession): Seq[(Id[NormalizedURI], Id[Keep], Id[User], Int)]
  def getUriReKeepCountsByKeeper(userId: Id[User])(implicit r: RSession): Map[Id[NormalizedURI], Int]
  def getReKeeps(keepIds: Set[Id[Keep]])(implicit r: RSession): Map[Id[Keep], Seq[ReKeep]]
  def getAllReKeepCountsByUser()(implicit r: RSession): Map[Id[User], Int]
  def getAllReKeepCountsByURI()(implicit r: RSession): Map[Id[NormalizedURI], Int]
  def getAllDirectReKeepCountsByKeep()(implicit r: RSession): Map[Id[Keep], Int]
  def getAllKeepers()(implicit r: RSession): Seq[Id[User]]
}

@Singleton
class ReKeepRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val uriReKeepCountCache: UriReKeepCountCache) extends DbRepo[ReKeep] with ReKeepRepo {

  import db.Driver.simple._

  type RepoImpl = ReKeepsTable
  class ReKeepsTable(tag: Tag) extends RepoTable[ReKeep](db, tag, "rekeep") {
    def keeperId = column[Id[User]]("keeper_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def srcUserId = column[Id[User]]("src_user_id", O.NotNull)
    def srcKeepId = column[Id[Keep]]("src_keep_id", O.NotNull)
    def attributionFactor = column[Int]("attr_factor", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, keeperId, keepId, uriId, srcUserId, srcKeepId, attributionFactor) <> ((ReKeep.apply _).tupled, ReKeep.unapply)
  }

  def table(tag: Tag) = new ReKeepsTable(tag)
  initTable()

  def deleteCache(model: ReKeep)(implicit session: RSession): Unit = {
    uriReKeepCountCache.remove(UriReKeepCountKey(model.uriId))
  }
  def invalidateCache(model: ReKeep)(implicit session: RSession): Unit = deleteCache(model)

  def getReKeep(keeperId: Id[User], uriId: Id[NormalizedURI], rekeeperId: Id[User])(implicit r: RSession): Option[ReKeep] = {
    (for (r <- rows if (r.keeperId === keeperId && r.uriId === uriId && r.srcUserId === rekeeperId)) yield r).firstOption(r)
  }

  def getReKeepsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Seq[ReKeep] = {
    (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE && r.createdAt >= since)) yield r).list
  }

  def getAllReKeepsByKeeper(userId: Id[User])(implicit r: RSession): Seq[ReKeep] = {
    (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE)) yield r).sortBy(_.createdAt.desc).list
  }

  def getReKeepsByReKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Seq[ReKeep] = {
    (for (r <- rows if (r.srcUserId === userId && r.state === ReKeepStates.ACTIVE && r.createdAt >= since)) yield r).sortBy(_.createdAt.desc).list
  }

  def getAllReKeepsByReKeeper(userId: Id[User])(implicit r: RSession): Seq[ReKeep] = {
    (for (r <- rows if (r.srcUserId === userId && r.state === ReKeepStates.ACTIVE)) yield r).list
  }

  def getReKeepCountByKeeper(userId: Id[User])(implicit r: RSession): Int = {
    (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE)) yield r).length.run
  }

  def getReKeepCountsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Map[Id[Keep], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE && r.createdAt > since)) yield r)
      .groupBy(_.keepId)
      .map { case (kId, rk) => (kId, rk.length) }
    q.toMap
  }

  def getReKeepCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]])(implicit r: RSession): Map[Id[Keep], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.keepId.inSet(keepIds) && r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.keepId)
      .map { case (kId, rk) => (kId, rk.length) }
    q.toMap
  }

  def getUriReKeepsWithCountsByKeeper(userId: Id[User])(implicit r: RSession): Seq[(Id[NormalizedURI], Id[Keep], Id[User], Int)] = {
    sql"select uri_id, keep_id, keeper_id, count(*) c from rekeep where keeper_id=$userId group by uri_id order by keep_id desc".as[(Id[NormalizedURI], Id[Keep], Id[User], Int)].list
  }

  def getUriReKeepCountsByKeeper(userId: Id[User])(implicit r: RSession): Map[Id[NormalizedURI], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.uriId)
      .map { case (uriId, rk) => (uriId, rk.length) }
    q.toMap
  }

  def getReKeeps(keepIds: Set[Id[Keep]])(implicit r: RSession): Map[Id[Keep], Seq[ReKeep]] = {
    val q = (for (r <- rows if (r.state === ReKeepStates.ACTIVE && r.keepId.inSet(keepIds))) yield r)
    q.list.foldLeft(Map.empty[Id[Keep], Seq[ReKeep]]) { (a, c) =>
      a + (c.keepId -> (a.getOrElse(c.keepId, Seq.empty[ReKeep]) ++ Seq(c)))
    }
  }

  def getReKeepCountByURI(uriId: Id[NormalizedURI])(implicit r: RSession): Int = {
    sql"select count(distinct (src_user_id)) from rekeep where uri_id=$uriId".as[Int].first
  }

  def getReKeepCountsByURIs(uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Map[Id[NormalizedURI], Int] = timing(s"getReKeepCountsByURIs(sz=${uriIds.size})") {
    if (uriIds.isEmpty) Map.empty
    else {
      val valueMap = uriReKeepCountCache.bulkGetOrElse(uriIds.map(UriReKeepCountKey(_)).toSet) { keys =>
        val buf = collection.mutable.ArrayBuilder.make[(Id[NormalizedURI], Int)]
        val missing = keys.map(_.uriId)
        missing.grouped(20).foreach { ids =>
          val params = Seq.fill(ids.size)("?").mkString(",")
          val stmt = session.getPreparedStatement(s"select uri_id, count(distinct (src_user_id)) from rekeep where uri_id in ($params) group by uri_id;")
          ids.zipWithIndex.foreach {
            case (uriId, idx) =>
              stmt.setLong(idx + 1, uriId.id)
          }
          val res = timing(s"getReKeepCountsByURIs(sz=${ids.size};ids=$ids)") { stmt.execute() }
          if (!res) throw new SQLException(s"[getReKeepCountsByURIs] ($stmt) failed to execute")
          val rs = stmt.getResultSet()
          while (rs.next()) {
            val uriId = Id[NormalizedURI](rs.getLong(1))
            val count = rs.getInt(2)
            buf += (uriId -> count)
          }
        }
        val resMap = buf.result.toMap
        missing.map { uriId =>
          UriReKeepCountKey(uriId) -> resMap.getOrElse(uriId, 0)
        }.toMap
      }
      valueMap.map { case (k, v) => (k.uriId -> v) }
    }
  }

  def getAllReKeepCountsByUser()(implicit r: RSession): Map[Id[User], Int] = {
    val q = (for (r <- rows if (r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.keeperId)
      .map { case (uId, rk) => (uId, rk.length) }
    q.toMap
  }

  def getAllReKeepCountsByURI()(implicit r: RSession): Map[Id[NormalizedURI], Int] = {
    val q = (for (r <- rows if (r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.uriId)
      .map { case (uriId, rk) => (uriId, rk.length) }
    q.toMap
  }

  def getAllDirectReKeepCountsByKeep()(implicit r: RSession): Map[Id[Keep], Int] = {
    val q = (for (r <- rows if (r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.keepId)
      .map { case (keepId, rk) => (keepId, rk.length) }
    q.toMap
  }

  def getAllKeepers()(implicit r: RSession): Seq[Id[User]] = {
    sql"select distinct keeper_id from rekeep".as[Id[User]].list
  }

}
