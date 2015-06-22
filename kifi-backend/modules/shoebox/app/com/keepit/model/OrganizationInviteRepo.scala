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
  def getByOrgIdAndUserIdAndAuthToken(organizationId: Id[Organization], userId: Id[User], authToken: String, state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite]
  def getByOrgAndUserId(organizationId: Id[Organization], userId: Id[User], state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite]
  def getByInviter(inviterId: Id[User], state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite]
  def getByOrgIdAndUserId(organizationId: Id[Organization], userId: Id[User], state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite]
  def getLastSentByOrgIdAndInviterIdAndUserId(organizationId: Id[Organization], inviterId: Id[User], userId: Id[User], includeStates: Set[State[OrganizationInvite]])(implicit s: RSession): Option[OrganizationInvite]
  def getLastSentByOrgIdAndInviterIdAndEmailAddress(organizationId: Id[Organization], inviterId: Id[User], emailAddress: EmailAddress, includeStates: Set[State[OrganizationInvite]])(implicit s: RSession): Option[OrganizationInvite]
}

@Singleton
class OrganizationInviteRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends OrganizationInviteRepo with DbRepo[OrganizationInvite] {
  override def deleteCache(orgInvite: OrganizationInvite)(implicit session: RSession) {}
  override def invalidateCache(orgInvite: OrganizationInvite)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationInviteTable
  class OrganizationInviteTable(tag: Tag) extends RepoTable[OrganizationInvite](db, tag, "organization_invite") {
    implicit val organizationRoleMapper = MappedColumnType.base[OrganizationRole, String](_.value, OrganizationRole(_))

    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def inviterId = column[Id[User]]("inviter_id", O.NotNull)
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def emailAddress = column[Option[EmailAddress]]("email_address", O.Nullable)
    def role = column[OrganizationRole]("role", O.NotNull)
    def message = column[Option[String]]("message", O.Nullable)
    def authToken = column[String]("auth_token", O.NotNull)

    def applyFromDbRow(
      id: Option[Id[OrganizationInvite]],
      createdAt: DateTime,
      updatedAt: DateTime,
      state: State[OrganizationInvite],
      organizationId: Id[Organization],
      inviterId: Id[User],
      userId: Option[Id[User]],
      emailAddress: Option[EmailAddress],
      role: OrganizationRole,
      message: Option[String],
      authToken: String) = {
      OrganizationInvite(id, createdAt, updatedAt, state, organizationId, inviterId, userId, emailAddress, role, message, authToken)
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
        invite.role,
        invite.message,
        invite.authToken))
    }

    def * = (id.?, createdAt, updatedAt, state, organizationId, inviterId, userId, emailAddress, role, message, authToken) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationInviteTable(tag)
  initTable()

  def getByOrganizationCompiled = Compiled { (orgId: Column[Id[Organization]], limit: ConstColumn[Long], offset: ConstColumn[Long], state: Column[State[OrganizationInvite]]) =>
    (for { row <- rows if row.organizationId === orgId && row.state === state } yield row).drop(offset).take(limit)
  }

  def getByOrganization(organizationId: Id[Organization], limit: Limit, offset: Offset, state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite] = {
    getByOrganizationCompiled(organizationId, limit.value, offset.value, state).list
  }

  def getByOrgIdAndUserIdAndAuthTokenCompiled = Compiled { (orgId: Column[Id[Organization]], userId: Column[Id[User]], authToken: Column[String], state: Column[State[OrganizationInvite]]) =>
    (for (row <- rows if row.organizationId === orgId && row.state === state && row.userId === userId && row.authToken === authToken) yield row).sortBy(_.createdAt desc)
  }

  def getByOrgIdAndUserIdAndAuthToken(organizationId: Id[Organization], userId: Id[User], authToken: String, state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite] = {
    getByOrgIdAndUserIdAndAuthTokenCompiled(organizationId, userId, authToken, state).list
  }

  def getByOrgAndUserIdCompiled = Compiled { (orgId: Column[Id[Organization]], userId: Column[Id[User]], state: Column[State[OrganizationInvite]]) =>
    (for (row <- rows if row.organizationId === orgId && row.userId === userId && row.state === state) yield row)
  }

  def getByOrgAndUserId(organizationId: Id[Organization], userId: Id[User], state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite] = {
    getByOrgAndUserIdCompiled(organizationId, userId, state).list
  }

  def getByInviterIdCompiled = Compiled { (inviterId: Column[Id[User]], state: Column[State[OrganizationInvite]]) => (for { row <- rows if row.inviterId === inviterId && row.state === state } yield row) }

  def getByInviter(inviterId: Id[User], state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit session: RSession): Seq[OrganizationInvite] = {
    getByInviterIdCompiled(inviterId, state).list
  }

  def getByOrgIdAndUserIdCompiled(organizationId: Column[Id[Organization]], userId: Column[Id[User]], state: State[OrganizationInvite]) = Compiled {
    for (row <- rows if row.organizationId === organizationId && row.userId === userId && row.state === state) yield row
  }
  def getByOrgIdAndUserId(organizationId: Id[Organization], userId: Id[User], state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE)(implicit s: RSession): Seq[OrganizationInvite] = {
    getByOrgIdAndUserIdCompiled(organizationId, userId, state).list
  }

  def getLastSentByOrgIdAndInviterIdAndUserIdCompiled(organizationId: Column[Id[Organization]], inviterId: Column[Id[User]], userId: Column[Id[User]], includeStates: Set[State[OrganizationInvite]]) = Compiled {
    (for (row <- rows if row.organizationId === organizationId && row.inviterId === inviterId && row.userId === userId && row.state.inSet(includeStates)) yield row).sortBy(_.createdAt.desc)
  }

  def getLastSentByOrgIdAndInviterIdAndUserId(organizationId: Id[Organization], inviterId: Id[User], userId: Id[User],
    includeStates: Set[State[OrganizationInvite]])(implicit s: RSession): Option[OrganizationInvite] = {
    getLastSentByOrgIdAndInviterIdAndUserIdCompiled(organizationId, inviterId, userId, includeStates).firstOption
  }

  def getLastSentByOrgIdAndInviterIdAndEmailAddressCompiled(organizationId: Column[Id[Organization]], inviterId: Column[Id[User]], emailAddress: Column[EmailAddress], includeStates: Set[State[OrganizationInvite]]) = Compiled {
    (for (row <- rows if row.organizationId === organizationId && row.inviterId === inviterId && row.emailAddress === emailAddress && row.state.inSet(includeStates)) yield row).sortBy(_.createdAt.desc)
  }

  def getLastSentByOrgIdAndInviterIdAndEmailAddress(organizationId: Id[Organization], inviterId: Id[User],
    emailAddress: EmailAddress, includeStates: Set[State[OrganizationInvite]])(implicit s: RSession): Option[OrganizationInvite] = {
    getLastSentByOrgIdAndInviterIdAndEmailAddressCompiled(organizationId, inviterId, emailAddress, includeStates).firstOption
  }
}
