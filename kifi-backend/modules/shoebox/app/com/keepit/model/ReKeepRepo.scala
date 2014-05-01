package com.keepit.model

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db._
import com.keepit.common.time._
import com.google.inject.{Singleton, ImplementedBy, Inject}
import org.joda.time.DateTime

@ImplementedBy(classOf[ReKeepRepoImpl])
trait ReKeepRepo extends Repo[ReKeep] {
  // TBD
}

@Singleton
class ReKeepRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[ReKeep] with ReKeepRepo {

  import db.Driver.simple._

  type RepoImpl = ReKeepsTable
  class ReKeepsTable(tag: Tag) extends RepoTable[ReKeep](db, tag, "rekeep") {
    def keeperId = column[Id[User]]("keeper_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def uriId  = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def srcUserId = column[Id[User]]("src_user_id", O.NotNull)
    def srcKeepId = column[Id[Keep]]("src_keep_id", O.NotNull)
    def attributionFactor = column[Int]("attr_factor", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, keeperId, keepId, uriId, srcUserId, srcKeepId, attributionFactor) <> ((ReKeep.apply _).tupled, ReKeep.unapply)
  }

  def table(tag:Tag) = new ReKeepsTable(tag)
  initTable()

  def deleteCache(model: ReKeep)(implicit session: RSession): Unit = {}
  def invalidateCache(model: ReKeep)(implicit session: RSession): Unit = {}
}