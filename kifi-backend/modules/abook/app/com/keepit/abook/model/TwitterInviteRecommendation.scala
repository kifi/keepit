package com.keepit.abook.model

import com.keepit.model.{ SocialUserInfo, User }
import com.keepit.common.db.{ Model, Id }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

// todo(Léo): Ideally, this could be merged into Invitation if inviting ever moves from Shoebox to ABook
case class TwitterInviteRecommendation(
    id: Option[Id[TwitterInviteRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    twitterAccountId: Id[SocialUserInfo],
    irrelevant: Boolean) extends Model[TwitterInviteRecommendation] {
  def withId(id: Id[TwitterInviteRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[TwitterInviteRecommendationRepoImpl])
trait TwitterInviteRecommendationRepo extends Repo[TwitterInviteRecommendation] {
  def recordIrrelevantRecommendation(userId: Id[User], twitterAccountId: Id[SocialUserInfo])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[SocialUserInfo]]
}

@Singleton
class TwitterInviteRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[TwitterInviteRecommendation] with TwitterInviteRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = TwitterInviteRecommendationTable
  class TwitterInviteRecommendationTable(tag: Tag) extends RepoTable[TwitterInviteRecommendation](db, tag, "twitter_invite_recommendation") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def twitterAccountId = column[Id[SocialUserInfo]]("twitter_account_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, twitterAccountId, irrelevant) <> ((TwitterInviteRecommendation.apply _).tupled, TwitterInviteRecommendation.unapply _)
  }

  def table(tag: Tag) = new TwitterInviteRecommendationTable(tag)
  initTable()

  override def deleteCache(emailAccount: TwitterInviteRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(emailAccount: TwitterInviteRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByUserAndTwitterAccount = Compiled { (userId: Column[Id[User]], twitterAccountId: Column[Id[SocialUserInfo]]) =>
    for (row <- rows if row.userId === userId && row.twitterAccountId === twitterAccountId) yield row
  }

  private def internTwitterInviteRecommendation(userId: Id[User], twitterAccountId: Id[SocialUserInfo], irrelevant: Boolean)(implicit session: RWSession): TwitterInviteRecommendation = {
    compiledGetByUserAndTwitterAccount(userId, twitterAccountId).firstOption match {
      case None => save(TwitterInviteRecommendation(userId = userId, twitterAccountId = twitterAccountId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(userId: Id[User], twitterAccountId: Id[SocialUserInfo])(implicit session: RWSession): Unit = {
    internTwitterInviteRecommendation(userId, twitterAccountId, true)
  }

  private val compiledIrrelevantRecommendations = Compiled { userId: Column[Id[User]] =>
    for (row <- rows if row.userId === userId && row.irrelevant === true) yield row.twitterAccountId
  }

  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[SocialUserInfo]] = {
    compiledIrrelevantRecommendations(userId).list.toSet
  }
}
