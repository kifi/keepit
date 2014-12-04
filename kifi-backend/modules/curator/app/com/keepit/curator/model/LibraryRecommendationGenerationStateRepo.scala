package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent }
import com.keepit.model.User
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession

import com.google.inject.{ ImplementedBy, Singleton, Inject }

@ImplementedBy(classOf[LibraryRecommendationGenerationStateRepoImpl])
trait LibraryRecommendationGenerationStateRepo extends DbRepo[LibraryRecommendationGenerationState] {

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[LibraryRecommendationGenerationState]

}

@Singleton
class LibraryRecommendationGenerationStateRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[LibraryRecommendationGenerationState] with LibraryRecommendationGenerationStateRepo {

  import db.Driver.simple._

  type RepoImpl = LibraryRecommendationGenerationStateTable
  class LibraryRecommendationGenerationStateTable(tag: Tag) extends RepoTable[LibraryRecommendationGenerationState](db, tag, "library_recommendation_generation_state") {
    def seq = column[SequenceNumber[CuratorLibraryInfo]]("seq", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, seq, userId) <> ((LibraryRecommendationGenerationState.apply _).tupled, LibraryRecommendationGenerationState.unapply)
  }

  def table(tag: Tag) = new LibraryRecommendationGenerationStateTable(tag)
  initTable()

  def deleteCache(model: LibraryRecommendationGenerationState)(implicit session: RSession): Unit = {}
  def invalidateCache(model: LibraryRecommendationGenerationState)(implicit session: RSession): Unit = {}

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[LibraryRecommendationGenerationState] = {
    (for (row <- rows if row.userId === userId) yield row).firstOption
  }

}
