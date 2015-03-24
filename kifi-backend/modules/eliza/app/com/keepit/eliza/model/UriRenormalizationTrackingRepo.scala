package com.keepit.eliza.model

import com.keepit.common.db.{ SequenceNumber, Model, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.model.{ ChangedURI, NormalizedURI }
import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.time._
import play.api.libs.json.{ Json, JsArray, JsNumber }
import com.google.inject.{ Inject, Singleton, ImplementedBy }

import org.joda.time.DateTime

@ImplementedBy(classOf[UriRenormalizationTrackingRepoImpl])
trait UriRenormalizationTrackingRepo extends Repo[UriRenormalizationEvent] {

  def getCurrentSequenceNumber()(implicit session: RSession): SequenceNumber[ChangedURI]

  def addNew(sequenceNumber: SequenceNumber[ChangedURI], numIdsChanged: Long, idsRetired: Seq[Id[NormalizedURI]])(implicit session: RWSession): Unit
}

@Singleton
class UriRenormalizationTrackingRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent) extends DbRepo[UriRenormalizationEvent] with UriRenormalizationTrackingRepo {

  import db.Driver.simple._
  implicit def seqNormalizedUriIdMapper[M <: Model[M]] = MappedColumnType.base[Seq[Id[NormalizedURI]], String]({ dest =>
    Json.stringify(JsArray(dest.map(x => JsNumber(x.id))))
  }, { source =>
    Json.parse(source) match {
      case x: JsArray => {
        x.value.map(_.as[Id[NormalizedURI]])
      }
      case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for Seq of Normalized URI ids: $source")
    }
  })

  type RepoImpl = UriRenormalizationEventTable
  class UriRenormalizationEventTable(tag: Tag) extends RepoTable[UriRenormalizationEvent](db, tag, "uri_renormalization_event") {
    def sequenceNumber = column[SequenceNumber[ChangedURI]]("sequence_number", O.NotNull)
    def numIdsChanged = column[Long]("num_ids_changed", O.NotNull)
    def idsRetired = column[Seq[Id[NormalizedURI]]]("ids_retired", O.NotNull)
    def * = (id.?, createdAt, updatedAt, sequenceNumber, numIdsChanged, idsRetired) <> ((UriRenormalizationEvent.apply _).tupled, UriRenormalizationEvent.unapply _)
  }
  def table(tag: Tag) = new UriRenormalizationEventTable(tag)

  override def deleteCache(model: UriRenormalizationEvent)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: UriRenormalizationEvent)(implicit session: RSession): Unit = {}

  def getCurrentSequenceNumber()(implicit session: RSession): SequenceNumber[ChangedURI] = {
    import scala.slick.jdbc.StaticQuery.interpolation
    val sql = sql"select max(sequence_number) as max from uri_renormalization_event"
    sql.as[SequenceNumber[ChangedURI]].firstOption.getOrElse(SequenceNumber.ZERO)
  }

  def addNew(sequenceNumber: SequenceNumber[ChangedURI], numIdsChanged: Long, idsRetired: Seq[Id[NormalizedURI]])(implicit session: RWSession): Unit = {
    super.save(UriRenormalizationEvent(sequenceNumber = sequenceNumber, numIdsChanged = numIdsChanged, idsRetired = idsRetired))
  }
}
