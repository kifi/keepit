package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.heimdal.DelightedAnswerSource
import org.joda.time.DateTime

@ImplementedBy(classOf[DelightedAnswerRepoImpl])
trait DelightedAnswerRepo extends Repo[DelightedAnswer] {
  def getByDelightedExtAnswerId(delightedExtAnswerId: String)(implicit session: RSession): Option[DelightedAnswer]
}

@Singleton
class DelightedAnswerRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    delightedUserRepo: DelightedUserRepoImpl) extends DbRepo[DelightedAnswer] with DelightedAnswerRepo with ExternalIdColumnDbFunction[DelightedAnswer] {

  import db.Driver.simple._

  type RepoImpl = DelightedAnswerTable
  class DelightedAnswerTable(tag: Tag) extends RepoTable[DelightedAnswer](db, tag, "delighted_answer") with ExternalIdColumn[DelightedAnswer] {
    def delightedExtAnswerId = column[String]("delighted_ext_answer_id", O.NotNull)
    def delightedUserId = column[Id[DelightedUser]]("delighted_user_id", O.NotNull)
    def date = column[DateTime]("date", O.NotNull)
    def score = column[Int]("score", O.NotNull)
    def comment = column[String]("comment", O.Nullable)
    def source = column[DelightedAnswerSource]("source", O.NotNull)
    def * = (id.?, createdAt, updatedAt, externalId, delightedExtAnswerId, delightedUserId, date, score, comment.?, source) <> ((DelightedAnswer.apply _).tupled, DelightedAnswer.unapply _)
  }

  def table(tag: Tag) = new DelightedAnswerTable(tag)
  initTable()

  override def deleteCache(model: DelightedAnswer)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: DelightedAnswer)(implicit session: RSession): Unit = {}

  def getByDelightedExtAnswerId(delightedExtAnswerId: String)(implicit session: RSession): Option[DelightedAnswer] = {
    (for { u <- rows if u.delightedExtAnswerId === delightedExtAnswerId } yield u).firstOption
  }

  override def save(answer: DelightedAnswer)(implicit session: RWSession): DelightedAnswer = {
    val savedAnswer = super.save(answer)
    // Update last_answer_date field in delighted_user if necessary
    val mostRecent = delightedUserRepo.getLastInteractedDate(savedAnswer.delightedUserId) getOrElse START_OF_TIME
    if (mostRecent < savedAnswer.date) {
      delightedUserRepo.setLastInteractedDate(savedAnswer.delightedUserId, savedAnswer.date)
    }
    savedAnswer
  }
}
