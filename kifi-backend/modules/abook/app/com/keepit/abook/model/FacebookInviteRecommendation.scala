package com.keepit.abook.model

import com.keepit.model.{ SocialUserInfo, User }
import com.keepit.common.db.{ Model, Id }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

// todo(Léo): Ideally, this could be merged into Invitation if inviting ever moves from Shoebox to ABook
case class FacebookInviteRecommendation(
    id: Option[Id[FacebookInviteRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    facebookAccountId: Id[SocialUserInfo],
    irrelevant: Boolean) extends Model[FacebookInviteRecommendation] {
  def withId(id: Id[FacebookInviteRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[FacebookInviteRecommendationRepoImpl])
trait FacebookInviteRecommendationRepo extends Repo[FacebookInviteRecommendation] {
  def recordIrrelevantRecommendation(userId: Id[User], facebookAccountId: Id[SocialUserInfo])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[SocialUserInfo]]
}

@Singleton
class FacebookInviteRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[FacebookInviteRecommendation] with FacebookInviteRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = FacebookInviteRecommendationTable
  class FacebookInviteRecommendationTable(tag: Tag) extends RepoTable[FacebookInviteRecommendation](db, tag, "facebook_invite_recommendation") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def facebookAccountId = column[Id[SocialUserInfo]]("facebook_account_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, facebookAccountId, irrelevant) <> ((FacebookInviteRecommendation.apply _).tupled, FacebookInviteRecommendation.unapply _)
  }

  def table(tag: Tag) = new FacebookInviteRecommendationTable(tag)
  initTable()

  override def deleteCache(recommendation: FacebookInviteRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(recommendation: FacebookInviteRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByUserAndFacebookAccount = Compiled { (userId: Column[Id[User]], facebookAccountId: Column[Id[SocialUserInfo]]) =>
    for (row <- rows if row.userId === userId && row.facebookAccountId === facebookAccountId) yield row
  }

  private def internFacebookInviteRecommendation(userId: Id[User], facebookAccountId: Id[SocialUserInfo], irrelevant: Boolean)(implicit session: RWSession): FacebookInviteRecommendation = {
    compiledGetByUserAndFacebookAccount(userId, facebookAccountId).firstOption match {
      case None => save(FacebookInviteRecommendation(userId = userId, facebookAccountId = facebookAccountId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(userId: Id[User], facebookAccountId: Id[SocialUserInfo])(implicit session: RWSession): Unit = {
    internFacebookInviteRecommendation(userId, facebookAccountId, true)
  }

  private val compiledIrrelevantRecommendations = Compiled { userId: Column[Id[User]] =>
    for (row <- rows if row.userId === userId && row.irrelevant === true) yield row.facebookAccountId
  }

  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[SocialUserInfo]] = {
    compiledIrrelevantRecommendations(userId).list.toSet
  }
}
