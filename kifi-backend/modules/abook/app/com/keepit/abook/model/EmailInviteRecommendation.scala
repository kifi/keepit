package com.keepit.abook.model

import com.keepit.model.User
import com.keepit.common.db.{ Model, Id }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

// todo(Léo): Ideally, this could be merged into Invitation if inviting ever moves from Shoebox to ABook
case class EmailInviteRecommendation(
    id: Option[Id[EmailInviteRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    emailAccountId: Id[EmailAccount],
    irrelevant: Boolean) extends Model[EmailInviteRecommendation] {
  def withId(id: Id[EmailInviteRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[EmailInviteRecommendationRepoImpl])
trait EmailInviteRecommendationRepo extends Repo[EmailInviteRecommendation] {
  def recordIrrelevantRecommendation(userId: Id[User], emailAccountId: Id[EmailAccount])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[EmailAccount]]
}

@Singleton
class EmailInviteRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[EmailInviteRecommendation] with EmailInviteRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = EmailInviteRecommendationTable
  class EmailInviteRecommendationTable(tag: Tag) extends RepoTable[EmailInviteRecommendation](db, tag, "email_invite_recommendation") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def emailAccountId = column[Id[EmailAccount]]("email_account_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, emailAccountId, irrelevant) <> ((EmailInviteRecommendation.apply _).tupled, EmailInviteRecommendation.unapply _)
  }

  def table(tag: Tag) = new EmailInviteRecommendationTable(tag)
  initTable()

  override def deleteCache(recommendation: EmailInviteRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(recommendation: EmailInviteRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByUserAndEmailAccount = Compiled { (userId: Column[Id[User]], emailAccountId: Column[Id[EmailAccount]]) =>
    for (row <- rows if row.userId === userId && row.emailAccountId === emailAccountId) yield row
  }

  private def internEmailInviteRecommendation(userId: Id[User], emailAccountId: Id[EmailAccount], irrelevant: Boolean)(implicit session: RWSession): EmailInviteRecommendation = {
    compiledGetByUserAndEmailAccount(userId, emailAccountId).firstOption match {
      case None => save(EmailInviteRecommendation(userId = userId, emailAccountId = emailAccountId, irrelevant = irrelevant))
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
