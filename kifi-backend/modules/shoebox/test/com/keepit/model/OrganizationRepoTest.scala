package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class OrganizationRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Repo" should {
    "save organizations and get them by id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val org = db.readWrite { implicit s =>
          orgRepo.save(Organization(name = "Bob", ownerId = Id[User](1), slug = OrganizationSlug("slug")))
        }

        db.readOnlyMaster { implicit s =>
          orgRepo.get(org.id.get) === org
        }
      }
    }
  }
}
