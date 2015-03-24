package com.keepit.abook.model

import com.keepit.model.User
import com.keepit.common.db.{ Model, Id }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

// todo(Léo): Ideally, this could be merged into FriendRequest if friend request handling ever moves from Shoebox to ABook
case class FriendRecommendation(
    id: Option[Id[FriendRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    friendId: Id[User],
    irrelevant: Boolean) extends Model[FriendRecommendation] {
  def withId(id: Id[FriendRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[FriendRecommendationRepoImpl])
trait FriendRecommendationRepo extends Repo[FriendRecommendation] {
  def recordIrrelevantRecommendation(userId: Id[User], friendId: Id[User])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[User]]
}

@Singleton
class FriendRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[FriendRecommendation] with FriendRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = FriendRecommendationTable
  class FriendRecommendationTable(tag: Tag) extends RepoTable[FriendRecommendation](db, tag, "friend_recommendation") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def friendId = column[Id[User]]("friend_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, friendId, irrelevant) <> ((FriendRecommendation.apply _).tupled, FriendRecommendation.unapply _)
  }

  def table(tag: Tag) = new FriendRecommendationTable(tag)
  initTable()

  override def deleteCache(emailAccount: FriendRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(emailAccount: FriendRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByUserAndFriend = Compiled { (userId: Column[Id[User]], friendId: Column[Id[User]]) =>
    for (row <- rows if row.userId === userId && row.friendId === friendId) yield row
  }

  private def internFriendRecommendation(userId: Id[User], friendId: Id[User], irrelevant: Boolean)(implicit session: RWSession): FriendRecommendation = {
    compiledGetByUserAndFriend(userId, friendId).firstOption match {
      case None => save(FriendRecommendation(userId = userId, friendId = friendId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(userId: Id[User], friendId: Id[User])(implicit session: RWSession): Unit = {
    internFriendRecommendation(userId, friendId, true)
  }

  private val compiledIrrelevantRecommendations = Compiled { userId: Column[Id[User]] =>
    for (row <- rows if row.userId === userId && row.irrelevant === true) yield row.friendId
  }

  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[User]] = {
    compiledIrrelevantRecommendations(userId).list.toSet
  }
}
