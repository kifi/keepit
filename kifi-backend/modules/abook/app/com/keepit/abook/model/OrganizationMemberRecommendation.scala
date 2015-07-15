package com.keepit.abook.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, Model }
import com.keepit.common.time._
import com.keepit.model.{ Organization, User }
import org.joda.time.DateTime

case class OrganizationMemberRecommendation(
    id: Option[Id[OrganizationMemberRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    organizationId: Id[Organization],
    memberId: Id[User],
    recommendedUserId: Option[Id[User]],
    recommendedEmailAccountId: Option[Id[EmailAccount]],
    irrelevant: Boolean) extends Model[OrganizationMemberRecommendation] {
  def withId(id: Id[OrganizationMemberRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[OrganizationMemberRecommendationRepoImpl])
trait OrganizationMemberRecommendationRepo extends Repo[OrganizationMemberRecommendation] {
  def recordIrrelevantRecommendation(organizationId: Id[Organization], memberId: Id[User], recommendedUserId: Option[Id[User]], recommendedEmailAccountId: Option[Id[EmailAccount]])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(organizationId: Id[Organization])(implicit session: RSession): Set[(Option[Id[User]], Option[Id[EmailAccount]])]
}

@Singleton
class OrganizationMemberRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[OrganizationMemberRecommendation] with OrganizationMemberRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = OrganizationMemberRecommendationTable
  class OrganizationMemberRecommendationTable(tag: Tag) extends RepoTable[OrganizationMemberRecommendation](db, tag, "organization_member_recommendation") {
    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def memberId = column[Id[User]]("member_id", O.NotNull)
    def recommendedUserId = column[Option[Id[User]]]("recommended_user_id", O.Nullable)
    def recommendedEmailAccountId = column[Option[Id[EmailAccount]]]("recommended_email_account_id", O.Nullable)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, organizationId, memberId, recommendedUserId, recommendedEmailAccountId, irrelevant) <> ((OrganizationMemberRecommendation.apply _).tupled, OrganizationMemberRecommendation.unapply _)
  }

  def table(tag: Tag) = new OrganizationMemberRecommendationTable(tag)
  initTable()

  override def deleteCache(memberReco: OrganizationMemberRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(memberReco: OrganizationMemberRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByOrgAndMember = Compiled { (organizationId: Column[Id[Organization]], memberId: Column[Id[User]], recommendedUserId: Column[Option[Id[User]]], recommendedEmailAccountId: Column[Option[Id[EmailAccount]]]) =>
    for (row <- rows if row.organizationId === organizationId && row.memberId === memberId && row.recommendedUserId === recommendedUserId && row.recommendedEmailAccountId === recommendedEmailAccountId) yield row
  }

  private def internMemberRecommendation(organizationId: Id[Organization], memberId: Id[User], recommendedUserId: Option[Id[User]], recommendedEmailAccountId: Option[Id[EmailAccount]], irrelevant: Boolean)(implicit session: RWSession): OrganizationMemberRecommendation = {
    compiledGetByOrgAndMember(organizationId, memberId, recommendedUserId, recommendedEmailAccountId).firstOption match {
      case None => save(OrganizationMemberRecommendation(organizationId = organizationId, memberId = memberId, recommendedUserId = recommendedUserId, recommendedEmailAccountId = recommendedEmailAccountId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(organizationId: Id[Organization], memberId: Id[User], recommendedUserId: Option[Id[User]], recommendedEmailAccountId: Option[Id[EmailAccount]])(implicit session: RWSession): Unit = {
    internMemberRecommendation(organizationId, memberId, recommendedUserId, recommendedEmailAccountId, true)
  }

  private val compiledIrrelevantRecommendations = Compiled { organizationId: Column[Id[Organization]] =>
    for (row <- rows if row.organizationId === organizationId && row.irrelevant === true) yield row
  }

  def getIrrelevantRecommendations(organizationId: Id[Organization])(implicit session: RSession): Set[(Option[Id[User]], Option[Id[EmailAccount]])] = {
    compiledIrrelevantRecommendations(organizationId).list.map(reco => (reco.recommendedUserId, reco.recommendedEmailAccountId)).toSet
  }
}
