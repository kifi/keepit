package com.keepit.model

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, ExternalIdColumnDbFunction }
import com.keepit.common.time.Clock
import com.keepit.common.db.{ ModelWithExternalId, Id, ExternalId }
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick.DBSession.RSession

case class FeatureWaitlistEntry(
    id: Option[Id[FeatureWaitlistEntry]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[FeatureWaitlistEntry] = ExternalId(),
    email: String,
    feature: String,
    userAgent: String) extends ModelWithExternalId[FeatureWaitlistEntry] {

  def withId(id: Id[FeatureWaitlistEntry]): FeatureWaitlistEntry = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
}

@Singleton
class FeatureWaitlistRepo @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[FeatureWaitlistEntry] with ExternalIdColumnDbFunction[FeatureWaitlistEntry] {
  import db.Driver.simple._

  type RepoImpl = FeatureWaitlistTable

  class FeatureWaitlistTable(tag: Tag) extends RepoTable[FeatureWaitlistEntry](db, tag, "feature_waitlist") with ExternalIdColumn[FeatureWaitlistEntry] {
    def email = column[String]("email", O.NotNull)
    def feature = column[String]("feature", O.NotNull)
    def userAgent = column[String]("user_agent", O.NotNull)
    def * = (id.?, createdAt, updatedAt, externalId, email, feature, userAgent) <> (FeatureWaitlistEntry.tupled, FeatureWaitlistEntry.unapply _)
  }

  def table(tag: Tag) = new FeatureWaitlistTable(tag)
  initTable()

  override def deleteCache(model: FeatureWaitlistEntry)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: FeatureWaitlistEntry)(implicit session: RSession): Unit = {}

}
