package com.keepit.model


import com.google.inject.{Inject, Singleton}

import com.keepit.common.db.slick.{DbRepo, DataBaseComponent, ExternalIdColumnDbFunction}
import com.keepit.common.time.Clock
import com.keepit.common.db.{ModelWithExternalId, Id, ExternalId}
import com.keepit.common.time._

import org.joda.time.DateTime

case class FeatureWaitlistEntry(
  id: Option[Id[FeatureWaitlistEntry]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[FeatureWaitlistEntry] = ExternalId(),
  email: String,
  feature: String,
  userAgent: String
) extends ModelWithExternalId[FeatureWaitlistEntry] {

  def withId(id: Id[FeatureWaitlistEntry]): FeatureWaitlistEntry = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt=updateTime)
}

@Singleton
class FeatureWaitlistRepo @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[FeatureWaitlistEntry] with ExternalIdColumnDbFunction[FeatureWaitlistEntry] {

  override val table = new RepoTable[FeatureWaitlistEntry](db, "feature_waitlist") with ExternalIdColumn[FeatureWaitlistEntry] {
    def email = column[String]("email", O.NotNull)
    def feature = column[String]("feature", O.NotNull)
    def userAgent = column[String]("user_agent", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ email ~ feature ~ userAgent <> (FeatureWaitlistEntry.apply _, FeatureWaitlistEntry.unapply _)
  }

}
