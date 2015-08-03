package com.keepit.model

import com.keepit.classify.Domain
import com.keepit.common.db.Id
import com.keepit.test.{ ShoeboxTestInjector, TestInjector }
import org.specs2.mutable.Specification

class OrganizationDomainOwnershipRepoTest extends Specification with ShoeboxTestInjector {

  "OrganizationDomainOwnershipRepo" should {

    "save organization domain ownerships and get them by id" in {
      withDb() { implicit injector =>
        val orgDomainOwnershipRepo = inject[OrganizationDomainOwnershipRepo]

        val orgDomainOwnership = db.readWrite { implicit s =>
          orgDomainOwnershipRepo.save(OrganizationDomainOwnership(organizationId = Id[Organization](1), domainHostname = "kifi.com"))
        }

        db.readOnlyMaster { implicit s =>
          orgDomainOwnershipRepo.get(orgDomainOwnership.id.get) === orgDomainOwnership
        }
      }
    }

    "get domain ownerships by org and domain id" in {
      withDb() { implicit injector =>
        val orgDomainOwnershipRepo = inject[OrganizationDomainOwnershipRepo]

        val orgId = Id[Organization](1)
        val domainHostname = "kifi.com"
        val orgDomainOwnership = db.readWrite { implicit s =>
          orgDomainOwnershipRepo.save(OrganizationDomainOwnership(organizationId = orgId, domainHostname = domainHostname))
        }

        db.readOnlyMaster { implicit s =>
          orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, domainHostname = "kifi.com") === Some(orgDomainOwnership)
        }
      }
    }

    "get all associated domain ownerships" in {
      withDb() { implicit injector =>
        val orgDomainOwnershipRepo = inject[OrganizationDomainOwnershipRepo]

        val org1Id = Id[Organization](1)

        val orgDomainOwnerships = db.readWrite { implicit s =>
          List(
            OrganizationDomainOwnership(organizationId = org1Id, domainHostname = "kifi.com"),
            OrganizationDomainOwnership(organizationId = org1Id, domainHostname = "kifi1.com"),
            OrganizationDomainOwnership(organizationId = org1Id, domainHostname = "kifi2.com"),
            OrganizationDomainOwnership(organizationId = Id[Organization](2), domainHostname = "kifi1.com"),
            OrganizationDomainOwnership(organizationId = Id[Organization](2), domainHostname = "kifi3.com")
          ).map(ownership => orgDomainOwnershipRepo.save(ownership))
        }

        db.readOnlyMaster { implicit s =>
          orgDomainOwnershipRepo.getOwnershipsForOrganization(org1Id) must containTheSameElementsAs(orgDomainOwnerships.filter(_.organizationId == org1Id))
        }
      }
    }

  }

}
