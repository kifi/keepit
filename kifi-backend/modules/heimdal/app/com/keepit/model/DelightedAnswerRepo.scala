package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[DelightedAnswerRepoImpl])
trait DelightedAnswerRepo extends Repo[DelightedAnswer] {
  def getLastAnswerDateForUser(userId: Id[User])(implicit session: RSession): Option[DateTime]
}

@Singleton
class DelightedAnswerRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    delightedUserRepo: DelightedUserRepoImpl) extends DbRepo[DelightedAnswer] with DelightedAnswerRepo {

  import db.Driver.simple._

  type RepoImpl = DelightedAnswerTable
  class DelightedAnswerTable(tag: Tag) extends RepoTable[DelightedAnswer](db, tag, "delighted_answer") {
    def delightedUserId = column[Id[DelightedUser]]("delighted_user_id", O.NotNull)
    def date = column[DateTime]("date", O.NotNull)
    def score = column[Int]("score", O.NotNull)
    def comment = column[String]("comment", O.Nullable)
    def * = (id.?, createdAt, updatedAt, delightedUserId, date, score, comment.?) <> ((DelightedAnswer.apply _).tupled, DelightedAnswer.unapply _)
  }

  def table(tag: Tag) = new DelightedAnswerTable(tag)
  initTable()

  override def deleteCache(model: DelightedAnswer)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: DelightedAnswer)(implicit session: RSession): Unit = {}

  def getLastAnswerDateForUser(userId: Id[User])(implicit session: RSession): Option[DateTime] = {
    Query((for {
      u <- delightedUserRepo.rows
      a <- rows if a.delightedUserId == u.id
    } yield a.date).max).first
  }
}
