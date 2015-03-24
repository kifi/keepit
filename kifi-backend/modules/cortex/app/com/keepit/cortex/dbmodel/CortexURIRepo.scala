package com.keepit.cortex.dbmodel

import com.google.inject.{ ImplementedBy, Provider, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.SequenceNumber
import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[CortexURIRepoImpl])
trait CortexURIRepo extends DbRepo[CortexURI] with SeqNumberFunction[CortexURI] {
  def getSince(seq: SequenceNumber[CortexURI], limit: Int)(implicit session: RSession): Seq[CortexURI]
  def getMaxSeq()(implicit session: RSession): SequenceNumber[CortexURI]
  def getByURIId(uid: Id[NormalizedURI])(implicit session: RSession): Option[CortexURI]
}

@Singleton
class CortexURIRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[CortexURI] with CortexURIRepo with SeqNumberDbFunction[CortexURI] {

  import db.Driver.simple._

  type RepoImpl = CortexURITable

  class CortexURITable(tag: Tag) extends RepoTable[CortexURI](db, tag, "cortex_uri") with SeqNumberColumn[CortexURI] {
    def uriId = column[Id[NormalizedURI]]("uri_id")
    def * = (id.?, createdAt, updatedAt, uriId, state, seq) <> ((CortexURI.apply _).tupled, CortexURI.unapply _)
  }

  def table(tag: Tag) = new CortexURITable(tag)
  initTable()

  def invalidateCache(uri: CortexURI)(implicit session: RSession): Unit = {}

  def deleteCache(uri: CortexURI)(implicit session: RSession): Unit = {}

  def getSince(seq: SequenceNumber[CortexURI], limit: Int)(implicit session: RSession): Seq[CortexURI] = super.getBySequenceNumber(seq, limit)

  def getMaxSeq()(implicit session: RSession): SequenceNumber[CortexURI] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val sql = sql"select max(seq) from cortex_uri"
    SequenceNumber[CortexURI](sql.as[Long].first max 0L)
  }

  def getByURIId(uid: Id[NormalizedURI])(implicit session: RSession): Option[CortexURI] = {
    val q = (for { r <- rows if r.uriId === uid } yield r)
    q.firstOption
  }

}

