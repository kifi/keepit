package com.keepit.curator.model

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.model._

@ImplementedBy(classOf[UriRecoFeedbackRepoImpl])
trait UriRecoFeedbackRepo extends DbRepo[UriRecoFeedback]

@Singleton
class UriRecoFeedbackRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[UriRecoFeedback] with UriRecoFeedbackRepo {

  import db.Driver.simple._

  type RepoImpl = UriRecoFeedbackTable

  implicit def feedbackTypeMapper = MappedColumnType.base[UriRecoFeedbackValue, String](
    { feedback => feedback.value },
    { value => UriRecoFeedbackValue(value) }
  )

  class UriRecoFeedbackTable(tag: Tag) extends RepoTable[UriRecoFeedback](db, tag, "uri_reco_feedback") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def feedback = column[UriRecoFeedbackValue]("feedback", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, uriId, feedback, state) <> ((UriRecoFeedback.apply _).tupled, UriRecoFeedback.unapply _)
  }

  def table(tag: Tag) = new UriRecoFeedbackTable(tag)
  initTable()

  def invalidateCache(model: UriRecoFeedback)(implicit session: RSession): Unit = {}

  def deleteCache(model: UriRecoFeedback)(implicit session: RSession): Unit = {}
}
