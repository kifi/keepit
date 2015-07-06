package com.keepit.abook.model

import com.keepit.model.User
import com.keepit.common.db.{ Model, Id }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

// todo(Léo): Ideally, this could be merged into Invitation if inviting ever moves from Shoebox to ABook
case class UserEmailInviteRecommendation(
    id: Option[Id[UserEmailInviteRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    emailAccountId: Id[EmailAccount],
    irrelevant: Boolean) extends Model[UserEmailInviteRecommendation] {
  def withId(id: Id[UserEmailInviteRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[UserEmailInviteRecommendationRepoImpl])
trait UserEmailInviteRecommendationRepo extends Repo[UserEmailInviteRecommendation] {
  def recordIrrelevantRecommendation(userId: Id[User], emailAccountId: Id[EmailAccount])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[EmailAccount]]
}

@Singleton
class UserEmailInviteRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[UserEmailInviteRecommendation] with UserEmailInviteRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = UserEmailInviteRecommendationTable
  class UserEmailInviteRecommendationTable(tag: Tag) extends RepoTable[UserEmailInviteRecommendation](db, tag, "email_invite_recommendation") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def emailAccountId = column[Id[EmailAccount]]("email_account_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, emailAccountId, irrelevant) <> ((UserEmailInviteRecommendation.apply _).tupled, UserEmailInviteRecommendation.unapply _)
  }

  def table(tag: Tag) = new UserEmailInviteRecommendationTable(tag)
  initTable()

  override def deleteCache(recommendation: UserEmailInviteRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(recommendation: UserEmailInviteRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByUserAndEmailAccount = Compiled { (userId: Column[Id[User]], emailAccountId: Column[Id[EmailAccount]]) =>
    for (row <- rows if row.userId === userId && row.emailAccountId === emailAccountId) yield row
  }

  private def internEmailInviteRecommendation(userId: Id[User], emailAccountId: Id[EmailAccount], irrelevant: Boolean)(implicit session: RWSession): UserEmailInviteRecommendation = {
    compiledGetByUserAndEmailAccount(userId, emailAccountId).firstOption match {
      case None => save(UserEmailInviteRecommendation(userId = userId, emailAccountId = emailAccountId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(userId: Id[User], emailAccountId: Id[EmailAccount])(implicit session: RWSession): Unit = {
    internEmailInviteRecommendation(userId, emailAccountId, true)
  }

  private val compiledIrrelevantRecommendations = Compiled { userId: Column[Id[User]] =>
    for (row <- rows if row.userId === userId && row.irrelevant === true) yield row.emailAccountId
  }

  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[EmailAccount]] = {
    compiledIrrelevantRecommendations(userId).list.toSet
  }
}
