package com.keepit.curator.model

import java.sql.Blob

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.time.Clock
import com.keepit.curator.feedback.ByteArrayCounter
import com.keepit.model.User
import javax.sql.rowset.serial.SerialBlob

@ImplementedBy(classOf[UserRecoFeedbackCounterRepoImpl])
trait UserRecoFeedbackCounterRepo extends DbRepo[UserRecoFeedbackCounter] {
  def getByUser(userId: Id[User])(implicit session: RSession): Option[UserRecoFeedbackCounter]
}

@Singleton
class UserRecoFeedbackCounterRepoImpl @Inject() (
    val db: DataBaseComponent,
    val userCache: UserRecoFeedbackCounterUserCache,
    val clock: Clock) extends DbRepo[UserRecoFeedbackCounter] with UserRecoFeedbackCounterRepo {

  import db.Driver.simple._

  type RepoImpl = UserRecoFeedbackCounterTable

  private implicit def counterTypeMapper = MappedColumnType.base[ByteArrayCounter, Blob](
    { counter => new SerialBlob(counter.getBytes()) },
    { blob => val len = blob.length().toInt; val arr = blob.getBytes(1, len); new ByteArrayCounter(arr) }
  )

  private implicit def userToUserKey(userId: Id[User]) = UserRecoFeedbackCounterUserKey(userId)

  class UserRecoFeedbackCounterTable(tag: Tag) extends RepoTable[UserRecoFeedbackCounter](db, tag, "user_reco_feedback_counter") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def upVotes = column[ByteArrayCounter]("up_votes", O.NotNull)
    def downVotes = column[ByteArrayCounter]("down_votes", O.NotNull)
    def posSignals = column[ByteArrayCounter]("pos_signals", O.NotNull)
    def negSignals = column[ByteArrayCounter]("neg_signals", O.NotNull)
    def votesRescaleCount = column[Int]("votes_rescale_count", O.NotNull)
    def signalsRescaleCount = column[Int]("signals_rescale_count", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, upVotes, downVotes, posSignals, negSignals, votesRescaleCount, signalsRescaleCount, state) <> ((UserRecoFeedbackCounter.apply _).tupled, UserRecoFeedbackCounter.unapply _)
  }

  def table(tag: Tag) = new UserRecoFeedbackCounterTable(tag)
  initTable()

  def invalidateCache(model: UserRecoFeedbackCounter)(implicit session: RSession): Unit = {
    userCache.set(model.userId, model)
  }

  def deleteCache(model: UserRecoFeedbackCounter)(implicit session: RSession): Unit = {
    userCache.remove(model.userId)
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Option[UserRecoFeedbackCounter] = {
    userCache.getOrElseOpt(userId) {
      (for (r <- rows if r.userId === userId) yield r).list.headOption
    }
  }

  override def save(model: UserRecoFeedbackCounter)(implicit session: RWSession): UserRecoFeedbackCounter = {
    invalidateCache(model)
    super.save(model)
  }

}
