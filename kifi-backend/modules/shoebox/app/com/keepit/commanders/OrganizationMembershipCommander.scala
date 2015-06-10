package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.BasicContact
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.social.BasicUser
import org.joda.time.DateTime

class OrganizationMembershipCommander @Inject() (db: Database,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo) {

  final case class MaybeOrganizationMember(member: Either[BasicUser, BasicContact], access: Option[OrganizationAccess], lastInvitedAt: Option[DateTime])

  // Offset and Count to prevent accidental reversal of arguments with same type (Long).
  def getMembersAndInvitees(orgId: Id[Organization], count: Count, offset: Offset, includeInvitees: Boolean): Seq[MaybeOrganizationMember] = {
    db.readOnlyMaster { implicit session =>
      val memberships = organizationMembershipRepo.getbyOrgId(orgId, count, offset)
      val invitees = includeInvitees match {
        case true =>
          val leftOverCount = Count(Math.max(count.value - memberships.length, 0))
          val leftOverOffset = Offset(Math.max(offset.value - memberships.length, 0))
          organizationInviteRepo.getByOrganization(orgId, leftOverCount, leftOverOffset)
        case false => Seq.empty[OrganizationInvite]
      }
      buildMaybeMembers(memberships, invitees).take(count.value.toInt)
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

  //  POST    /m/1/teams/:id/members/invite               // takes a list of userId / access tuple's => { members: [ {userId: USER_ID, access: ACCESS}, ...] }
  //  POST    /m/1/teams/:id/members/modify               // same as above
  //  POST    /m/1/teams/:id/members/remove               // takes a list of userIds => { members: [ USER_ID1, USER_ID2] }

}
