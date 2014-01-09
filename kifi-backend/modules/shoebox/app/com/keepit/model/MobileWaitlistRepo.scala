package com.keepit.model


import com.google.inject.{Inject, Singleton}

import com.keepit.common.db.slick.{DbRepo, DataBaseComponent, ExternalIdColumnDbFunction}
import com.keepit.common.time.Clock
import com.keepit.common.db.{ModelWithExternalId, Id, ExternalId}
import com.keepit.common.time._

import org.joda.time.DateTime

case class MobileWaitlistEntry(
  id: Option[Id[MobileWaitlistEntry]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[MobileWaitlistEntry] = ExternalId(),
  email: String,
  userAgent: String
) extends ModelWithExternalId[MobileWaitlistEntry] {

  def withId(id: Id[MobileWaitlistEntry]): MobileWaitlistEntry = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt=updateTime)
}

@Singleton
class MobileWaitlistRepo @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[MobileWaitlistEntry] with ExternalIdColumnDbFunction[MobileWaitlistEntry] {

  override val table = new RepoTable[MobileWaitlistEntry](db, "mobile_waitlist") with ExternalIdColumn[MobileWaitlistEntry] {
    def email = column[String]("message_text", O.NotNull)
    def userAgent = column[String]("message_text", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ email ~ userAgent <> (MobileWaitlistEntry.apply _, MobileWaitlistEntry.unapply _)
  }

}
