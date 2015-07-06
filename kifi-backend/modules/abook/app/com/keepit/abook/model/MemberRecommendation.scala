package com.keepit.abook.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, Model }
import com.keepit.common.time._
import com.keepit.model.{ Organization, User }
import org.joda.time.DateTime

case class MemberRecommendation(
    id: Option[Id[MemberRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    organizationId: Id[Organization],
    memberId: Id[User],
    irrelevant: Boolean) extends Model[MemberRecommendation] {
  def withId(id: Id[MemberRecommendation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[MemberRecommendationRepoImpl])
trait MemberRecommendationRepo extends Repo[MemberRecommendation] {
  def recordIrrelevantRecommendation(organizationId: Id[Organization], memberId: Id[User])(implicit session: RWSession): Unit
  def getIrrelevantRecommendations(organizationId: Id[Organization])(implicit session: RSession): Set[Id[User]]
}

@Singleton
class MemberRecommendationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[MemberRecommendation] with MemberRecommendationRepo {

  import db.Driver.simple._

  type RepoImpl = MemberRecommendationTable
  class MemberRecommendationTable(tag: Tag) extends RepoTable[MemberRecommendation](db, tag, "member_recommendation") {
    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def memberId = column[Id[User]]("member_id", O.NotNull)
    def irrelevant = column[Boolean]("irrelevant", O.NotNull)
    def * = (id.?, createdAt, updatedAt, organizationId, memberId, irrelevant) <> ((MemberRecommendation.apply _).tupled, MemberRecommendation.unapply _)
  }

  def table(tag: Tag) = new MemberRecommendationTable(tag)
  initTable()

  override def deleteCache(memberReco: MemberRecommendation)(implicit session: RSession): Unit = {}
  override def invalidateCache(memberReco: MemberRecommendation)(implicit session: RSession): Unit = {}

  private val compiledGetByOrgAndMember = Compiled { (organizationId: Column[Id[Organization]], memberId: Column[Id[User]]) =>
    for (row <- rows if row.organizationId === organizationId && row.memberId === memberId) yield row
  }

  private def internMemberRecommendation(organizationId: Id[Organization], memberId: Id[User], irrelevant: Boolean)(implicit session: RWSession): MemberRecommendation = {
    compiledGetByOrgAndMember(organizationId, memberId).firstOption match {
      case None => save(MemberRecommendation(organizationId = organizationId, memberId = memberId, irrelevant = irrelevant))
      case Some(recommendation) if recommendation.irrelevant == irrelevant => recommendation
      case Some(differentRecommendation) => save(differentRecommendation.copy(irrelevant = irrelevant))
    }
  }

  def recordIrrelevantRecommendation(organizationId: Id[Organization], memberId: Id[User])(implicit session: RWSession): Unit = {
    internMemberRecommendation(organizationId, memberId, true)
  }

  private val compiledIrrelevantRecommendations = Compiled { organizationId: Column[Id[Organization]] =>
    for (row <- rows if row.organizationId === organizationId && row.irrelevant === true) yield row.memberId
  }

  def getIrrelevantRecommendations(organizationId: Id[Organization])(implicit session: RSession): Set[Id[User]] = {
    compiledIrrelevantRecommendations(organizationId).list.toSet
  }
}
