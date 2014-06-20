package com.keepit.cortex.dbmodel

import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.SequenceNumber


@ImplementedBy(classOf[CortexURIRepoImpl])
trait CortexURIRepo extends DbRepo[CortexURI] with SeqNumberFunction[CortexURI]{
  def getSince(seq: SequenceNumber[CortexURI], limit: Int)(implicit session: RSession): Seq[CortexURI]
}

@Singleton
class CortexURIRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock,
  airbrake: AirbrakeNotifier
) extends DbRepo[CortexURI] with CortexURIRepo with SeqNumberDbFunction[CortexURI] {

  import db.Driver.simple._

  type RepoImpl = CortexURITable

  class CortexURITable(tag: Tag) extends RepoTable[CortexURI](db, tag, "cortex_uri") with SeqNumberColumn[CortexURI] {
    def uriId = column[Id[NormalizedURI]]("uri_id")
    def title = column[String]("title")
    def url = column[String]("url", O.NotNull)
    def * = (id.?, createdAt,updatedAt, uriId, title.?, url, state, seq)  <> ((CortexURI.apply _).tupled, CortexURI.unapply _)
  }

  def table(tag:Tag) = new CortexURITable(tag)
  initTable()

  override def invalidateCache(uri: CortexURI)(implicit session: RSession): Unit = {}

  override def deleteCache(uri: CortexURI)(implicit session: RSession): Unit = {}

  override def getSince(seq: SequenceNumber[CortexURI], limit: Int)(implicit session: RSession): Seq[CortexURI] = super.getBySequenceNumber(seq, limit)
}

