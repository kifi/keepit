package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.OrganizationFactory._
import com.keepit.model.OrganizationInviteFactory._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.OrganizationInviteFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._

import scala.collection.immutable.IndexedSeq

class OrganizationInviteRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Invite Repo" should {
    "save invites and get them by id" in {
      withDb() { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        val invite = db.readWrite { implicit s =>
          val owner = UserFactory.user().withName("Benjamin", "Button").saved
          val org = OrganizationFactory.organization().withOwner(owner).saved
          val invitee = UserFactory.user().withName("Carol", "Cardigan").saved
          val invite = orgInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = owner.id.get, userId = Some(invitee.id.get), role = OrganizationRole.ADMIN))
          invite
        }

        db.readOnlyMaster { implicit s =>
          orgInviteRepo.get(invite.id.get) === invite
        }
      }
    }

    "get by inviter id" in {
      withDb() { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        val (inviter, users) = db.readWrite { implicit session =>
          val inviter = user().saved
          val org = organization().withOwner(inviter).saved
          val users = UserFactory.users(10).saved
          users.foreach { invitee =>
            orgInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get,
              userId = invitee.id, role = OrganizationRole.MEMBER))
          }
          (inviter, users)
        }

        val invitesById = db.readOnlyMaster { implicit session => orgInviteRepo.getByInviter(inviter.id.get) }
        invitesById.length === users.length
        invitesById.map(_.inviterId).toSet === Set(inviter.id.get)
        invitesById.map(_.userId).diff(users.map(_.id)) === List.empty[Id[User]]
      }
    }

    "ignore anonymous invites when getting pending invitees" in {
      withDb() { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        val org = db.readWrite { implicit session =>
          val inviter = user().saved
          val org = organization().withOwner(inviter).saved
          val users = UserFactory.users(10).saved
          users.foreach { invitee =>
            orgInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get,
              userId = invitee.id, role = OrganizationRole.MEMBER))
          }
          orgInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get, userId = None, emailAddress = None, role = OrganizationRole.MEMBER))
          org
        }
        val directInvites = db.readOnlyMaster { implicit session =>
          orgInviteRepo.getByOrganizationAndDecision(organizationId = org.id.get, decision = InvitationDecision.PENDING, offset = Offset(0), limit = Limit(Int.MaxValue), includeAnonymous = false)
        }
        directInvites.length === 10
        directInvites.exists(invite => invite.userId.isEmpty && invite.emailAddress.isEmpty) === false

        val allInvites = db.readOnlyMaster { implicit session =>
          orgInviteRepo.getByOrganizationAndDecision(organizationId = org.id.get, decision = InvitationDecision.PENDING, offset = Offset(0), limit = Limit(Int.MaxValue), includeAnonymous = true)
        }
        allInvites.length === 11
        allInvites.exists(invite => invite.userId.isEmpty && invite.emailAddress.isEmpty) === true
      }
    }

    "get organization counts of pending invites" in withDb() { implicit injector =>
      val orgIds = db.readWrite { implicit session =>
        val inviter = user().saved
        val orgs = organizations(3).map(org => org.withOwner(inviter).saved)

        orgs.foreach { org =>
          OrganizationInviteFactory.organizationInvite().withDecision(InvitationDecision.PENDING).withOrganization(org).saved
          OrganizationInviteFactory.organizationInvite().withDecision(InvitationDecision.ACCEPTED).withOrganization(org).saved
          OrganizationInviteFactory.organizationInvite().withDecision(InvitationDecision.DECLINED).withOrganization(org).saved
        }

        orgs.map(_.id.get)
      }

      val orgInviteRepo = inject[OrganizationInviteRepo]
      db.readOnlyMaster { implicit session =>
        orgInviteRepo.getDecisionCountsGroupedByOrganization(Set(InvitationDecision.PENDING)) === Seq((orgIds(0), 1), (orgIds(1), 1), (orgIds(2), 1))
        orgInviteRepo.getDecisionCountsGroupedByOrganization(Set(InvitationDecision.ACCEPTED, InvitationDecision.DECLINED)) === Seq((orgIds(0), 2), (orgIds(1), 2), (orgIds(2), 2))
        orgInviteRepo.getDecisionCountsGroupedByOrganization(Set()) === Seq.empty
      }
    }
  }
}
