package com.keepit.eliza.model

import com.keepit.common.db.{Model, Id}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.model.NormalizedURI
import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.time._
import MessagingTypeMappers._

import scala.slick.lifted.Query

import com.google.inject.{Inject, Singleton, ImplementedBy}

import org.joda.time.DateTime

@ImplementedBy(classOf[UriRenormalizationTrackingRepoImpl])
trait UriRenormalizationTrackingRepo extends Repo[UriRenormalizationEvent] {

  def getCurrentSequenceNumber()(implicit session: RSession): Long

  def addNew(sequenceNumber: Long, numIdsChanged: Long, idsRetired: Seq[Id[NormalizedURI]])(implicit session: RWSession) : Unit
}

class UriRenormalizationTrackingRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent
  ) extends DbRepo[UriRenormalizationEvent] with UriRenormalizationTrackingRepo {

  override val table = new RepoTable[UriRenormalizationEvent](db, "uri_renormalization_event") {
    def sequenceNumber = column[Long]("sequence_number", O.NotNull)
    def numIdsChanged = column[Long]("num_ids_changed", O.NotNull)
    def idsRetired = column[Seq[Id[NormalizedURI]]]("ids_retired", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ sequenceNumber ~ numIdsChanged ~ idsRetired <> (UriRenormalizationEvent.apply _, UriRenormalizationEvent.unapply _)
  }

  import db.Driver.Implicit._

  override def deleteCache(model: UriRenormalizationEvent)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: UriRenormalizationEvent)(implicit session: RSession): Unit = {}

  def getCurrentSequenceNumber()(implicit session: RSession): Long = {
    Query(table.map(_.sequenceNumber).max).first.getOrElse(0)
  }

  def addNew(sequenceNumber: Long, numIdsChanged: Long, idsRetired: Seq[Id[NormalizedURI]])(implicit session: RWSession) : Unit = {
    super.save(UriRenormalizationEvent(sequenceNumber=sequenceNumber, numIdsChanged=numIdsChanged, idsRetired=idsRetired))
  }
}
