package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.OrganizationFactory._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._

import scala.collection.immutable.IndexedSeq

class OrganizationInviteRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Invite Repo" should {
    "save invites and get them by id" in {
      withDb() { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        val org = db.readWrite { implicit s =>
          orgInviteRepo.save(OrganizationInvite(organizationId = Id[Organization](1), inviterId = Id[User](1), userId = Some(Id[User](10)), role = OrganizationRole.ADMIN))
        }

        db.readOnlyMaster { implicit s =>
          orgInviteRepo.get(org.id.get) === org
        }
      }
    }

    "get by inviter id" in {
      withDb() { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        val org = organization().saved
        val inviter = user().saved
        val users = UserFactory.users(10).saved
        db.readWrite { implicit s =>
          users.foreach { invitee =>
            orgInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get,
              userId = invitee.id, role = OrganizationRole.MEMBER))
          }
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
        val org = organization().saved
        val inviter = user().saved
        val users = UserFactory.users(10).saved
        db.readWrite { implicit session =>
          users.foreach { invitee =>
            orgInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get,
              userId = invitee.id, role = OrganizationRole.MEMBER))
          }
          orgInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get,
            userId = None, role = OrganizationRole.MEMBER))
        }
        val invites = db.readOnlyMaster { implicit session => orgInviteRepo.getByOrganizationAndDecision(organizationId = org.id.get, decision = InvitationDecision.PENDING, offset = Offset(0)) }
      }
    }
  }
}
