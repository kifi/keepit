package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ States, Id, State }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[OrganizationInviteRepoImpl])
trait OrganizationInviteRepo extends Repo[OrganizationInvite] {
  def getByOrganization(organizationId: Id[Organization], limit: Limit, offset: Offset, state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite]
  def getByInviter(inviterId: Id[User]): Seq[OrganizationInvite] = ???
}

@Singleton
class OrganizationInviteRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends OrganizationInviteRepo with DbRepo[OrganizationInvite] {
  override def deleteCache(orgInvite: OrganizationInvite)(implicit session: RSession) {}
  override def invalidateCache(orgInvite: OrganizationInvite)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationInviteTable
  class OrganizationInviteTable(tag: Tag) extends RepoTable[OrganizationInvite](db, tag, "organization_invite") {
    implicit val organizationAccessMapper = MappedColumnType.base[OrganizationAccess, String](_.value, OrganizationAccess(_))

    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def inviterId = column[Id[User]]("inviter_id", O.NotNull)
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def emailAddress = column[Option[EmailAddress]]("email_address", O.Nullable)
    def access = column[OrganizationAccess]("access", O.NotNull)
    def message = column[Option[String]]("message", O.Nullable)

    def applyFromDbRow(
      id: Option[Id[OrganizationInvite]],
      createdAt: DateTime,
      updatedAt: DateTime,
      state: State[OrganizationInvite],
      organizationId: Id[Organization],
      inviterId: Id[User],
      userId: Option[Id[User]],
      emailAddress: Option[EmailAddress],
      access: OrganizationAccess,
      message: Option[String]) = {
      OrganizationInvite(id, createdAt, updatedAt, state, organizationId, inviterId, userId, emailAddress, access, message)
    }

    def unapplyToDbRow(invite: OrganizationInvite) = {
      Some((invite.id,
        invite.createdAt,
        invite.updatedAt,
        invite.state,
        invite.organizationId,
        invite.inviterId,
        invite.userId,
        invite.emailAddress,
        invite.access,
        invite.message))
    }

    def * = (id.?, createdAt, updatedAt, state, organizationId, inviterId, userId, emailAddress, access, message) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationInviteTable(tag)
  initTable()

  def getByOrganizationCompiled = Compiled { (orgId: Column[Id[Organization]], limit: ConstColumn[Long], offset: ConstColumn[Long], state: Column[State[OrganizationInvite]]) =>
    (for { row <- rows if row.organizationId === orgId && row.state === state } yield row).drop(offset).take(limit)
  }

  def getByOrganization(organizationId: Id[Organization], limit: Limit, offset: Offset, state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite] = {
    getByOrganizationCompiled(organizationId, limit.value, offset.value, state).list
  }
}
