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
  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[CuratorKeepInfo]
  def getKeepersByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Id[User]]
  def checkDiscoverableByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean
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
    def discoverable = column[Boolean]("discoverable", O.NotNull)
    def * = (id.?, createdAt, updatedAt, uriId, userId, keepId, state, discoverable) <> ((CuratorKeepInfo.apply _).tupled, CuratorKeepInfo.unapply _)
  }

  def table(tag: Tag) = new CuratorKeepInfoTable(tag)
  initTable()

  def deleteCache(model: CuratorKeepInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: CuratorKeepInfo)(implicit session: RSession): Unit = {}

  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[CuratorKeepInfo] = {
    (for (row <- rows if row.keepId === keepId) yield row).firstOption
  }

  def getKeepersByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Id[User]] = {
    (for (row <- rows if row.uriId === uriId && row.state === CuratorKeepInfoStates.ACTIVE) yield row.userId).list
  }

  def checkDiscoverableByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    (for (row <- rows if row.uriId === uriId && row.state === CuratorKeepInfoStates.ACTIVE && row.discoverable) yield row.id).firstOption.isDefined
  }

}
