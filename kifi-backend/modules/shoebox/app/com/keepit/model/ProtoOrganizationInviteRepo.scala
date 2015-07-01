package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.Clock

@ImplementedBy(classOf[ProtoOrganizationInviteRepoImpl])
trait ProtoOrganizationInviteRepo extends Repo[ProtoOrganizationInvite] {
  def getByProtoOrganization(protoOrgId: Id[ProtoOrganization], limit: Limit, offset: Offset, state: State[ProtoOrganizationInvite] = ProtoOrganizationInviteStates.ACTIVE)(implicit session: RSession): Seq[ProtoOrganizationInvite]
  def getAllByProtoOrganization(protoOrgId: Id[ProtoOrganization], state: State[ProtoOrganizationInvite] = ProtoOrganizationInviteStates.ACTIVE)(implicit session: RSession): Seq[ProtoOrganizationInvite]
  def deactivate(model: ProtoOrganizationInvite)(implicit session: RWSession): Unit
}

@Singleton
class ProtoOrganizationInviteRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends ProtoOrganizationInviteRepo with DbRepo[ProtoOrganizationInvite] with Logging {
  override def deleteCache(protoOrgInvite: ProtoOrganizationInvite)(implicit session: RSession) {}
  override def invalidateCache(protoOrgInvite: ProtoOrganizationInvite)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = ProtoOrganizationInviteTable
  class ProtoOrganizationInviteTable(tag: Tag) extends RepoTable[ProtoOrganizationInvite](db, tag, "proto_organization_invite") {

    def protoOrgId = column[Id[ProtoOrganization]]("proto_org_id", O.NotNull)
    def inviterId = column[Id[User]]("inviter_id", O.NotNull)
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def emailAddress = column[Option[EmailAddress]]("email_address", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, protoOrgId, inviterId, userId, emailAddress) <> ((ProtoOrganizationInvite.apply _).tupled, ProtoOrganizationInvite.unapply)
  }

  def table(tag: Tag) = new ProtoOrganizationInviteTable(tag)
  initTable()

  def getByProtoOrganization(protoOrgId: Id[ProtoOrganization], limit: Limit, offset: Offset, state: State[ProtoOrganizationInvite] = ProtoOrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[ProtoOrganizationInvite] = {
    (for { row <- rows if row.protoOrgId === protoOrgId && row.state === state } yield row).drop(offset.value).take(limit.value).list
  }
  def getAllByProtoOrganization(protoOrgId: Id[ProtoOrganization], state: State[ProtoOrganizationInvite] = ProtoOrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[ProtoOrganizationInvite] = {
    getByProtoOrganization(protoOrgId, limit = Limit(Int.MaxValue), offset = Offset(0), state = state)
  }

  def deactivate(model: ProtoOrganizationInvite)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
