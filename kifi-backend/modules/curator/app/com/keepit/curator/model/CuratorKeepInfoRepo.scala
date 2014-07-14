package com.keepit.curator.model

import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent }
import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURI, Keep }
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

import com.google.inject.{ ImplementedBy, Singleton, Inject }

import org.joda.time.DateTime

@ImplementedBy(classOf[CuratorKeepInfoRepoImpl])
trait CuratorKeepInfoRepo extends DbRepo[CuratorKeepInfo] {
}

@Singleton
class CuratorKeepInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CuratorKeepInfo] with CuratorKeepInfoRepo {

  import db.Driver.simple._

  type RepoImpl = CuratorKeepInfoTable
  class CuratorKeepInfoTable(tag: Tag) extends RepoTable[CuratorKeepInfo](db, tag, "curator_keep_info") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def isPrivate = column[Boolean]("is_private", O.NotNull)
    def * = (id.?, createdAt, updatedAt, uriId, userId, keepId, isPrivate, state) <> ((CuratorKeepInfo.apply _).tupled, CuratorKeepInfo.unapply _)
  }

  def table(tag: Tag) = new CuratorKeepInfoTable(tag)
  initTable()

  def deleteCache(model: CuratorKeepInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: CuratorKeepInfo)(implicit session: RSession): Unit = {}

}
