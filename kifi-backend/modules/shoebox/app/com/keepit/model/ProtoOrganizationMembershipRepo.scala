package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.Clock

@ImplementedBy(classOf[ProtoOrganizationMembershipRepoImpl])
trait ProtoOrganizationMembershipRepo extends Repo[ProtoOrganizationMembership] {
  def getByProtoOrganization(protoOrgId: Id[ProtoOrganization], limit: Limit, offset: Offset, state: State[ProtoOrganizationMembership] = ProtoOrganizationMembershipStates.ACTIVE)(implicit session: RSession): Seq[ProtoOrganizationMembership]
  def getAllByProtoOrganization(protoOrgId: Id[ProtoOrganization], state: State[ProtoOrganizationMembership] = ProtoOrganizationMembershipStates.ACTIVE)(implicit session: RSession): Seq[ProtoOrganizationMembership]
  def deactivate(model: ProtoOrganizationMembership)(implicit session: RWSession): Unit
}

@Singleton
class ProtoOrganizationMembershipRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends ProtoOrganizationMembershipRepo with DbRepo[ProtoOrganizationMembership] with Logging {
  override def deleteCache(protoOrgMembership: ProtoOrganizationMembership)(implicit session: RSession) {}
  override def invalidateCache(protoOrgMembership: ProtoOrganizationMembership)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = ProtoOrganizationMembershipTable
  class ProtoOrganizationMembershipTable(tag: Tag) extends RepoTable[ProtoOrganizationMembership](db, tag, "proto_organization_membership") {

    def protoOrgId = column[Id[ProtoOrganization]]("proto_organization_id", O.NotNull)
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def emailAddress = column[Option[EmailAddress]]("email_address", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, protoOrgId, userId, emailAddress) <> ((ProtoOrganizationMembership.apply _).tupled, ProtoOrganizationMembership.unapply)
  }

  def table(tag: Tag) = new ProtoOrganizationMembershipTable(tag)
  initTable()

  def getByProtoOrganization(protoOrgId: Id[ProtoOrganization], limit: Limit, offset: Offset, state: State[ProtoOrganizationMembership] = ProtoOrganizationMembershipStates.ACTIVE)(implicit s: RSession): Seq[ProtoOrganizationMembership] = {
    (for { row <- rows if row.protoOrgId === protoOrgId && row.state === state } yield row).drop(offset.value).take(limit.value).list
  }
  def getAllByProtoOrganization(protoOrgId: Id[ProtoOrganization], state: State[ProtoOrganizationMembership] = ProtoOrganizationMembershipStates.ACTIVE)(implicit s: RSession): Seq[ProtoOrganizationMembership] = {
    getByProtoOrganization(protoOrgId, limit = Limit(Int.MaxValue), offset = Offset(0), state = state)
  }

  def deactivate(model: ProtoOrganizationMembership)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
