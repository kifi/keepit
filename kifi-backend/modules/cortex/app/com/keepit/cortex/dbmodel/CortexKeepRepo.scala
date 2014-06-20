package com.keepit.cortex.dbmodel

import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.State
import com.keepit.model.Keep
import com.keepit.model.KeepSource
import com.keepit.model.URL
import com.keepit.model.User
import com.keepit.common.db.SequenceNumber


@ImplementedBy(classOf[CortexKeepRepoImpl])
trait CortexKeepRepo extends DbRepo[CortexKeep] with SeqNumberFunction[CortexKeep] {
  def getSince(seq: SequenceNumber[CortexKeep], limit: Int)(implicit session: RSession): Seq[CortexKeep]
}

@Singleton
class CortexKeepRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock,
  airbrake: AirbrakeNotifier

) extends DbRepo[CortexKeep] with CortexKeepRepo with SeqNumberDbFunction[CortexKeep]{

  import db.Driver.simple._

  type RepoImpl = CortexKeepTable

  class CortexKeepTable(tag: Tag) extends RepoTable[CortexKeep](db, tag, "cortex_keep") with SeqNumberColumn[CortexKeep]{
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def isPrivate = column[Boolean]("is_private", O.NotNull)
    def source = column[KeepSource]("source", O.NotNull)
    def * = (id.?, createdAt, updatedAt, keepId, userId, uriId, isPrivate, state, source, seq) <> ((CortexKeep.apply _).tupled, CortexKeep.unapply _)
  }

  def table(tag:Tag) = new CortexKeepTable(tag)
  initTable()

  override def invalidateCache(keep: CortexKeep)(implicit session: RSession): Unit = {}

  override def deleteCache(uri: CortexKeep)(implicit session: RSession): Unit = {}

  override def getSince(seq: SequenceNumber[CortexKeep], limit: Int)(implicit session: RSession): Seq[CortexKeep] = super.getBySequenceNumber(seq, limit)
}
