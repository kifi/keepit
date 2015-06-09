package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.util.Success

class OrganizationRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Repo" should {
    "save organizations and get them by id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val org = db.readWrite { implicit s =>
          orgRepo.save(Organization(name = "Bob", ownerId = Id[User](1), handle = Some(PrimaryOrganizationHandle(OrganizationHandle("handle"), OrganizationHandle("handle")))))
        }

        db.readOnlyMaster { implicit s =>
          orgRepo.get(org.id.get) === org
        }
      }
    }

    "update organizations" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        db.readWrite { implicit s =>
          val org = orgRepo.save(Organization(name = "Kifi", ownerId = Id[User](1), handle = None))
          orgRepo.updateName(org.id.get, "Kiifii") must equalTo(Success("success"))
          orgRepo.updateDescription(org.id.get, Some("Keep stuff, and find it later")) must equalTo(Success("success"))

          val updatedOrg = orgRepo.get(org.id.get)
          updatedOrg.name must equalTo("Kiifii")
          updatedOrg.description must equalTo(Some("Keep stuff, and find it later"))
        }
      }
    }
  }
}
