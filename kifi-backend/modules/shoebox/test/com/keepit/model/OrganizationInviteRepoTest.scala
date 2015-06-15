package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.collection.immutable.IndexedSeq

class OrganizationInviteRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Invite Repo" should {
    "save invites and get them by id" in {
      withDb() { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        val org = db.readWrite { implicit s =>
          orgInviteRepo.save(OrganizationInvite(organizationId = Id[Organization](1), inviterId = Id[User](1), userId = Some(Id[User](10)), role = OrganizationRole.OWNER))
        }

        db.readOnlyMaster { implicit s =>
          orgInviteRepo.get(org.id.get) === org
        }
      }
    }

    "get by inviter id" in {
      withDb() { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        val inviterId = Id[User](1)
        val userIds: IndexedSeq[Some[Id[User]]] = 10 to 20 map (i => Some(Id[User](i)))
        db.readWrite { implicit s =>
          for (inviteeId <- userIds) {
            orgInviteRepo.save(OrganizationInvite(organizationId = Id[Organization](1), inviterId = inviterId,
              userId = inviteeId, role = OrganizationRole.MEMBER))
          }
        }

        val invitesById = db.readOnlyMaster { implicit session => orgInviteRepo.getByInviter(inviterId) }
        invitesById.length === userIds.length
        invitesById.map(_.inviterId).toSet === Set(inviterId)
        invitesById.map(_.userId).diff(userIds) === List.empty[Id[User]]
      }
    }
  }
}
