package com.keepit.eliza.model

import com.keepit.common.db.{ SequenceNumber, Model, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.model.{ ChangedURI, NormalizedURI }
import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.time._
import MessagingTypeMappers._

import scala.slick.lifted.Query

import com.google.inject.{ Inject, Singleton, ImplementedBy }

import org.joda.time.DateTime

case class UriRenormalizationEvent(
  id: Option[Id[UriRenormalizationEvent]] = None,
  createdAt: DateTime = currentDateTime,
  updateAt: DateTime = currentDateTime,
  sequenceNumber: SequenceNumber[ChangedURI],
  numIdsChanged: Long,
  idsRetired: Seq[Id[NormalizedURI]])
    extends Model[UriRenormalizationEvent] {

  def withId(id: Id[UriRenormalizationEvent]): UriRenormalizationEvent = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt = updateTime)
}
