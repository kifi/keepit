package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class OrganizationInviteRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Invite Repo" should {
    "save invites and get them by id" in {
      withDb() { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        val org = db.readWrite { implicit s =>
          orgInviteRepo.save(OrganizationInvite(organizationId = Id[Organization](1), inviterId = Id[User](1), userId = Some(Id[User](10)), access = OrganizationAccess.READ_WRITE))
        }

        db.readOnlyMaster { implicit s =>
          orgInviteRepo.get(org.id.get) === org
        }
      }
    }
  }
}
