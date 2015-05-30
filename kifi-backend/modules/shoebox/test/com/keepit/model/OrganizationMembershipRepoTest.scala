package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class OrganizationMembershipRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Member Repo" should {
    "save members and get them by id" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val org = db.readWrite { implicit s =>
          orgMemberRepo.save(OrganizationMembership(organizationId = Id[Organization](1), userId = Id[User](1), access = OrganizationAccess.OWNER))
        }

        db.readOnlyMaster { implicit s =>
          orgMemberRepo.get(org.id.get) === org
        }
      }
    }
  }
}
