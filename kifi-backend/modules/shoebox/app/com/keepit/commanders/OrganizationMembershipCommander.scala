package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.BasicContact
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.http.Status._

final case class MaybeOrganizationMember(member: Either[BasicUser, BasicContact], access: Option[OrganizationAccess], lastInvitedAt: Option[DateTime])
final case class OrganizationFail(status: Int, message: String)

class OrganizationMembershipCommander @Inject() (db: Database,
    organizationRepo: OrganizationRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo) {

  // Offset and Count to prevent accidental reversal of arguments with same type (Long).
  def getMembersAndInvitees(orgId: Id[Organization], count: Count, offset: Offset, includeInvitees: Boolean): Seq[MaybeOrganizationMember] = {
    db.readOnlyMaster { implicit session =>
      val members = organizationMembershipRepo.getbyOrgId(orgId, count, offset)
      val invitees = includeInvitees match {
        case true =>
          val leftOverCount = Count(Math.max(count.value - members.length, 0))
          val leftOverOffset = if (members.isEmpty) {
            val totalCountForOrg = organizationMembershipRepo.countByOrgId(orgId)
            Offset(offset.value - totalCountForOrg)
          } else {
            Offset(0)
          }
          organizationInviteRepo.getByOrganization(orgId, leftOverCount, leftOverOffset)
        case false => Seq.empty[OrganizationInvite]
      }
      buildMaybeMembers(members, invitees)
    }
  }

  private def buildMaybeMembers(members: Seq[OrganizationMembership], invitees: Seq[OrganizationInvite]): Seq[MaybeOrganizationMember] = {
    val (invitedUserIds, invitedEmailAddresses) = invitees.partition(_.userId.nonEmpty)
    val usersMap = db.readOnlyMaster { implicit session =>
      basicUserRepo.loadAllActive((members.map(_.userId) ++ invitedUserIds.map(_.userId.get)).toSet)
    }

    val membersNotIncludingOwner = members.filterNot(_.isOwner).flatMap { member =>
      usersMap.get(member.userId) map { basicUser =>
        MaybeOrganizationMember(member = Left(basicUser), access = Some(member.access), lastInvitedAt = Some(member.updatedAt))
      }
    }

    val invitedByUserId = invitedUserIds flatMap { invitedById =>
      usersMap.get(invitedById.userId.get) map { basicUser =>
        MaybeOrganizationMember(member = Left(basicUser), access = Some(invitedById.access), lastInvitedAt = Some(invitedById.updatedAt))
      }
    }

    val invitedByEmailAddress = invitedEmailAddresses map { invitedByAddress =>
      val contact = BasicContact(invitedByAddress.emailAddress.get)
      MaybeOrganizationMember(member = Right(contact), access = Some(invitedByAddress.access), lastInvitedAt = Some(invitedByAddress.updatedAt))
    }

    membersNotIncludingOwner ++ invitedByUserId ++ invitedByEmailAddress
  }

  def modifyMemberships(orgId: Id[Organization], requestorId: Id[User], modifications: Seq[(Id[User], OrganizationAccess)]): Either[OrganizationFail, Seq[OrganizationMembership]] = ???

  case class MemberRemovals(failedToRemove: Seq[Id[User]], removed: Seq[Id[User]])
  def removeMembers(orgId: Id[Organization], requestorId: Id[User], removals: Seq[Id[User]]): MemberRemovals = ???
}
