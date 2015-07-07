package com.keepit.abook.model

import com.keepit.model.{ Organization, User }
import com.keepit.common.db.{ Model, Id }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

case class OrganizationEmailInviteRecommendation(
    id: Option[Id[OrganizationEmailInviteRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    organizationId: Id[Organization],
    emailAccountId: Id[EmailAccount],
    irrelevant: Boolean) extends Model[OrganizationEmailInviteRecommendation] {
  def withId(id: Id[OrganizationEmailInviteRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[OrganizationEmailInviteRecommendationRepoImpl])
trait OrganizationEmailInviteRecommendationRepo extends Repo[OrganizationEmailInviteRecommendation] {
  def recordIrrelevantRecommendation(orgId: Id[Organization], emailAccountId: Id[EmailAccount])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(orgId: Id[Organization])(implicit session: RSession): Set[Id[EmailAccount]]
}

@Singleton
class OrganizationEmailInviteRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[OrganizationEmailInviteRecommendation] with OrganizationEmailInviteRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = OrganizationEmailInviteRecommendationTable
  class OrganizationEmailInviteRecommendationTable(tag: Tag) extends RepoTable[OrganizationEmailInviteRecommendation](db, tag, "organization_email_invite_recommendation") {
    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def emailAccountId = column[Id[EmailAccount]]("email_account_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, organizationId, emailAccountId, irrelevant) <> ((OrganizationEmailInviteRecommendation.apply _).tupled, OrganizationEmailInviteRecommendation.unapply _)
  }

  def table(tag: Tag) = new OrganizationEmailInviteRecommendationTable(tag)
  initTable()

  override def deleteCache(recommendation: OrganizationEmailInviteRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(recommendation: OrganizationEmailInviteRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByOrganizationAndEmailAccount = Compiled { (organizationId: Column[Id[Organization]], emailAccountId: Column[Id[EmailAccount]]) =>
    for (row <- rows if row.organizationId === organizationId && row.emailAccountId === emailAccountId) yield row
  }

  private def internEmailInviteRecommendation(organizationId: Id[Organization], emailAccountId: Id[EmailAccount], irrelevant: Boolean)(implicit session: RWSession): OrganizationEmailInviteRecommendation = {
    compiledGetByOrganizationAndEmailAccount(organizationId, emailAccountId).firstOption match {
      case None => save(OrganizationEmailInviteRecommendation(organizationId = organizationId, emailAccountId = emailAccountId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(organizationId: Id[Organization], emailAccountId: Id[EmailAccount])(implicit session: RWSession): Unit = {
    internEmailInviteRecommendation(organizationId, emailAccountId, true)
  }

  private val compiledIrrelevantRecommendations = Compiled { organizationId: Column[Id[Organization]] =>
    for (row <- rows if row.organizationId === organizationId && row.irrelevant === true) yield row.emailAccountId
  }

  def getIrrelevantRecommendations(organizationId: Id[Organization])(implicit session: RSession): Set[Id[EmailAccount]] = {
    compiledIrrelevantRecommendations(organizationId).list.toSet
  }
}
