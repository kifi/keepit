package com.keepit.abook.model

import com.keepit.model.{ SocialUserInfo, User }
import com.keepit.common.db.{ Model, Id }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

// todo(Léo): Ideally, this could be merged into Invitation if inviting ever moves from Shoebox to ABook
case class LinkedInInviteRecommendation(
    id: Option[Id[LinkedInInviteRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    linkedInAccountId: Id[SocialUserInfo],
    irrelevant: Boolean) extends Model[LinkedInInviteRecommendation] {
  def withId(id: Id[LinkedInInviteRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[LinkedInInviteRecommendationRepoImpl])
trait LinkedInInviteRecommendationRepo extends Repo[LinkedInInviteRecommendation] {
  def recordIrrelevantRecommendation(userId: Id[User], linkedInAccountId: Id[SocialUserInfo])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[SocialUserInfo]]
}

@Singleton
class LinkedInInviteRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LinkedInInviteRecommendation] with LinkedInInviteRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = LinkedInInviteRecommendationTable
  class LinkedInInviteRecommendationTable(tag: Tag) extends RepoTable[LinkedInInviteRecommendation](db, tag, "linked_in_invite_recommendation") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def linkedInAccountId = column[Id[SocialUserInfo]]("linked_in_account_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, linkedInAccountId, irrelevant) <> ((LinkedInInviteRecommendation.apply _).tupled, LinkedInInviteRecommendation.unapply _)
  }

  def table(tag: Tag) = new LinkedInInviteRecommendationTable(tag)
  initTable()

  override def deleteCache(recommendation: LinkedInInviteRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(recommendation: LinkedInInviteRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByUserAndLinkedInAccount = Compiled { (userId: Column[Id[User]], linkedInAccountId: Column[Id[SocialUserInfo]]) =>
    for (row <- rows if row.userId === userId && row.linkedInAccountId === linkedInAccountId) yield row
  }

  private def internLinkedInInviteRecommendation(userId: Id[User], linkedInAccountId: Id[SocialUserInfo], irrelevant: Boolean)(implicit session: RWSession): LinkedInInviteRecommendation = {
    compiledGetByUserAndLinkedInAccount(userId, linkedInAccountId).firstOption match {
      case None => save(LinkedInInviteRecommendation(userId = userId, linkedInAccountId = linkedInAccountId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(userId: Id[User], linkedInAccountId: Id[SocialUserInfo])(implicit session: RWSession): Unit = {
    internLinkedInInviteRecommendation(userId, linkedInAccountId, true)
  }

  private val compiledIrrelevantRecommendations = Compiled { userId: Column[Id[User]] =>
    for (row <- rows if row.userId === userId && row.irrelevant === true) yield row.linkedInAccountId
  }

  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[SocialUserInfo]] = {
    compiledIrrelevantRecommendations(userId).list.toSet
  }
}
