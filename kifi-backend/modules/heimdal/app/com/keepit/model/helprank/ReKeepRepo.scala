package com.keepit.model.helprank

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime

import scala.slick.jdbc.StaticQuery.interpolation

@ImplementedBy(classOf[ReKeepRepoImpl])
trait ReKeepRepo extends Repo[ReKeep] {
  def getReKeep(keeperId: Id[User], uriId: Id[NormalizedURI], rekeeperId: Id[User])(implicit r: RSession): Option[ReKeep]
  def getReKeepsByKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Seq[ReKeep]
  def getAllReKeepsByKeeper(userId: Id[User])(implicit r: RSession): Seq[ReKeep]
  def getReKeepsByReKeeper(userId: Id[User], since: DateTime = currentDateTime.minusMonths(1))(implicit r: RSession): Seq[ReKeep]
  def getAllReKeepsByReKeeper(userId: Id[User])(implicit r: RSession): Seq[ReKeep]
  def getReKeepCountByKeeper(userId: Id[User])(implicit r: RSession): Int
  def getReKeepCountsByKeeper(userId: Id[User])(implicit r: RSession): Map[Id[Keep], Int]
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
class ReKeepRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[ReKeep] with ReKeepRepo {

  import db.Driver.simple._

  type RepoImpl = ReKeepsTable
  class ReKeepsTable(tag: Tag) extends RepoTable[ReKeep](db, tag, "re_keep") {
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

  def deleteCache(model: ReKeep)(implicit session: RSession): Unit = {}
  def invalidateCache(model: ReKeep)(implicit session: RSession): Unit = {}

  def getReKeep(keeperId: Id[User], uriId: Id[NormalizedURI], rekeeperId: Id[User])(implicit r: RSession): Option[ReKeep] = {
    (for (r <- rows if (r.keeperId === keeperId && r.uriId === uriId && r.srcUserId === rekeeperId)) yield r).firstOption()
  }

  def getReKeepsByKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Seq[ReKeep] = {
    (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE && r.createdAt >= since)) yield r).list()
  }

  def getAllReKeepsByKeeper(userId: Id[User])(implicit r: RSession): Seq[ReKeep] = {
    (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE)) yield r).sortBy(_.createdAt.desc).list()
  }

  def getReKeepsByReKeeper(userId: Id[User], since: DateTime)(implicit r: RSession): Seq[ReKeep] = {
    (for (r <- rows if (r.srcUserId === userId && r.state === ReKeepStates.ACTIVE && r.createdAt >= since)) yield r).sortBy(_.createdAt.desc).list()
  }

  def getAllReKeepsByReKeeper(userId: Id[User])(implicit r: RSession): Seq[ReKeep] = {
    (for (r <- rows if (r.srcUserId === userId && r.state === ReKeepStates.ACTIVE)) yield r).list()
  }

  def getReKeepCountByKeeper(userId: Id[User])(implicit r: RSession): Int = {
    (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE)) yield r).length.run
  }

  def getReKeepCountsByKeeper(userId: Id[User])(implicit r: RSession): Map[Id[Keep], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.keepId)
      .map { case (kId, rk) => (kId, rk.length) }
    q.toMap()
  }

  def getReKeepCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]])(implicit r: RSession): Map[Id[Keep], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.keepId.inSet(keepIds) && r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.keepId)
      .map { case (kId, rk) => (kId, rk.length) }
    q.toMap()
  }

  def getUriReKeepsWithCountsByKeeper(userId: Id[User])(implicit r: RSession): Seq[(Id[NormalizedURI], Id[Keep], Id[User], Int)] = {
    sql"select uri_id, keep_id, keeper_id, count(*) c from re_keep where keeper_id=$userId group by uri_id order by keep_id desc".as[(Id[NormalizedURI], Id[Keep], Id[User], Int)].list()
  }

  def getUriReKeepCountsByKeeper(userId: Id[User])(implicit r: RSession): Map[Id[NormalizedURI], Int] = {
    val q = (for (r <- rows if (r.keeperId === userId && r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.uriId)
      .map { case (uriId, rk) => (uriId, rk.length) }
    q.toMap()
  }

  def getReKeeps(keepIds: Set[Id[Keep]])(implicit r: RSession): Map[Id[Keep], Seq[ReKeep]] = {
    val q = (for (r <- rows if (r.state === ReKeepStates.ACTIVE && r.keepId.inSet(keepIds))) yield r)
    q.list().foldLeft(Map.empty[Id[Keep], Seq[ReKeep]]) { (a, c) =>
      a + (c.keepId -> (a.getOrElse(c.keepId, Seq.empty[ReKeep]) ++ Seq(c)))
    }
  }

  def getReKeepCountByURI(uriId: Id[NormalizedURI])(implicit r: RSession): Int = {
    val q = (for (r <- rows if (r.uriId === uriId && r.state === ReKeepStates.ACTIVE)) yield r)
      .groupBy(_.srcKeepId)
      .map { case (srcKeepId, rk) => (srcKeepId, rk.length) }
    q.length.run
  }

  def getReKeepCountsByURIs(uriIds: Set[Id[NormalizedURI]])(implicit r: RSession): Map[Id[NormalizedURI], Int] = { // todo(ray): optimize
    uriIds.map { uriId => uriId -> getReKeepCountByURI(uriId) } toMap
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
    sql"select distinct keeper_id from re_keep".as[Id[User]].list()
  }

}