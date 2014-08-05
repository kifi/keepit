package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent }
import com.keepit.model.User
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession

import com.google.inject.{ ImplementedBy, Singleton, Inject }

@ImplementedBy(classOf[UserRecommendationGenerationStateRepoImpl])
trait UserRecommendationGenerationStateRepo extends DbRepo[UserRecommendationGenerationState] {

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[UserRecommendationGenerationState]

}

@Singleton
class UserRecommendationGenerationStateRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[UserRecommendationGenerationState] with UserRecommendationGenerationStateRepo {

  import db.Driver.simple._

  type RepoImpl = UserRecommendationGenerationStateTable
  class UserRecommendationGenerationStateTable(tag: Tag) extends RepoTable[UserRecommendationGenerationState](db, tag, "user_recommendation_generation_state") {
    def seq = column[SequenceNumber[SeedItem]]("seq", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, seq, userId) <> ((UserRecommendationGenerationState.apply _).tupled, UserRecommendationGenerationState.unapply _)
  }

  def table(tag: Tag) = new UserRecommendationGenerationStateTable(tag)
  initTable()

  def deleteCache(model: UserRecommendationGenerationState)(implicit session: RSession): Unit = {}
  def invalidateCache(model: UserRecommendationGenerationState)(implicit session: RSession): Unit = {}

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[UserRecommendationGenerationState] = {
    (for (row <- rows if row.userId === userId) yield row).firstOption
  }

}
