package com.keepit.cortex.dbmodel

import com.google.inject.{ ImplementedBy, Provider, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.State
import com.keepit.common.db.SequenceNumber
import scala.slick.jdbc.StaticQuery
import org.joda.time.DateTime

@ImplementedBy(classOf[CortexKeepRepoImpl])
trait CortexKeepRepo extends DbRepo[CortexKeep] with SeqNumberFunction[CortexKeep] {
  def getSince(seq: SequenceNumber[CortexKeep], limit: Int)(implicit session: RSession): Seq[CortexKeep]
  def getMaxSeq()(implicit session: RSession): SequenceNumber[CortexKeep]
  def getByKeepId(id: Id[Keep])(implicit session: RSession): Option[CortexKeep]
  def countRecentUserKeeps(userId: Id[User], since: DateTime)(implicit session: RSession): Int
}

@Singleton
class CortexKeepRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[CortexKeep] with CortexKeepRepo with SeqNumberDbFunction[CortexKeep] {

  import db.Driver.simple._

  type RepoImpl = CortexKeepTable

  class CortexKeepTable(tag: Tag) extends RepoTable[CortexKeep](db, tag, "cortex_keep") with SeqNumberColumn[CortexKeep] {
    def keptAt = column[DateTime]("kept_at", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def isPrivate = column[Boolean]("is_private", O.NotNull)
    def source = column[KeepSource]("source", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.Nullable)
    def visibility = column[LibraryVisibility]("visibility", O.Nullable)
    def * = (id.?, createdAt, updatedAt, keptAt, keepId, userId, uriId, isPrivate, state, source, seq, libraryId.?, visibility.?) <> ((CortexKeep.apply _).tupled, CortexKeep.unapply _)
  }

  def table(tag: Tag) = new CortexKeepTable(tag)
  initTable()

  def invalidateCache(keep: CortexKeep)(implicit session: RSession): Unit = {}

  def deleteCache(uri: CortexKeep)(implicit session: RSession): Unit = {}

  def getSince(seq: SequenceNumber[CortexKeep], limit: Int)(implicit session: RSession): Seq[CortexKeep] = super.getBySequenceNumber(seq, limit)

  def getMaxSeq()(implicit session: RSession): SequenceNumber[CortexKeep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val sql = sql"select max(seq) from cortex_keep"
    SequenceNumber[CortexKeep](sql.as[Long].first max 0L)
  }

  def getByKeepId(id: Id[Keep])(implicit session: RSession): Option[CortexKeep] = {
    val q = for {
      r <- rows if r.keepId === id
    } yield r

    q.firstOption
  }

  def countRecentUserKeeps(userId: Id[User], since: DateTime)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select count(*) from cortex_keep where user_id = ${userId.id} and kept_at > ${since}"""
    q.as[Int].first
  }
}
