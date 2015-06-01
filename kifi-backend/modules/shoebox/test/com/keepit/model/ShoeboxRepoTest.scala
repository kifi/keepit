package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.test.Helpers._

class ShoeboxRepoTest extends Specification with ShoeboxApplicationInjector {
  args(skipAll = true)

  "Shoebox Repos " should {
    "save and retrieve models" in {
      running(new ShoeboxApplication()) {
        // OrganizationRepo
        val organizationRepo = inject[OrganizationRepo]
        db.readWrite { implicit session =>
          val org = Organization(id = Some(Id(2)), name = "OrgName", ownerId = Id[User](3), slug = OrganizationSlug("slug"))
          val saved = organizationRepo.save(org)
          organizationRepo.get(saved.id.get).id.get === 2
        }
      }
    }
  }
}
