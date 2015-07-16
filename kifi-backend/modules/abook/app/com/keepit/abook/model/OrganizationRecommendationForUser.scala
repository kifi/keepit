package com.keepit.abook.model

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.{ Model, Id }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.time._
import com.keepit.model.{ User, Organization }
import org.joda.time.DateTime

case class OrganizationRecommendationForUser(
    id: Option[Id[OrganizationRecommendationForUser]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    organizationId: Id[Organization],
    irrelevant: Boolean) extends Model[OrganizationRecommendationForUser] {
  def withId(id: Id[OrganizationRecommendationForUser]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[OrganizationRecommendationForUserRepoImpl])
trait OrganizationRecommendationForUserRepo extends Repo[OrganizationRecommendationForUser] {
  def recordIrrelevantRecommendation(userId: Id[User], organizationId: Id[Organization])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[Organization]]
}

@Singleton
class OrganizationRecommendationForUserRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[OrganizationRecommendationForUser] with OrganizationRecommendationForUserRepo {

  import db.Driver.simple._

  type RepoImpl = OrganizationRecommendationForUserTable
  class OrganizationRecommendationForUserTable(tag: Tag) extends RepoTable[OrganizationRecommendationForUser](db, tag, "organization_recommendation_for_user") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, organizationId, irrelevant) <> ((OrganizationRecommendationForUser.apply _).tupled, OrganizationRecommendationForUser.unapply _)
  }

  def table(tag: Tag) = new OrganizationRecommendationForUserTable(tag)
  initTable()

  override def deleteCache(recommendation: OrganizationRecommendationForUser)(implicit session: RSession): Unit = {}
  override def invalidateCache(recommendation: OrganizationRecommendationForUser)(implicit session: RSession): Unit = {}

  private val compiledGetByUserAndOrg = Compiled { (userId: Column[Id[User]], organizationId: Column[Id[Organization]]) =>
    for (row <- rows if row.userId === userId && row.organizationId === row.organizationId) yield row
  }

  private def internRecommendation(userId: Id[User], organizationId: Id[Organization], irrelevant: Boolean)(implicit session: RWSession): OrganizationRecommendationForUser = {
    compiledGetByUserAndOrg(userId, organizationId).firstOption match {
      case None => save(OrganizationRecommendationForUser(userId = userId, organizationId = organizationId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(userId: Id[User], organizationId: Id[Organization])(implicit session: RWSession): Unit = {
    internRecommendation(userId, organizationId, irrelevant = true)
  }

  private val compiledIrrelevantRecommendations = Compiled { userId: Column[Id[User]] =>
    for (row <- rows if row.userId === userId && row.irrelevant === true) yield row.organizationId
  }

  def getIrrelevantRecommendations(userId: Id[User])(implicit session: RSession): Set[Id[Organization]] = {
    compiledIrrelevantRecommendations(userId).list.toSet
  }
}
