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
          orgDomainOwnershipRepo.save(OrganizationDomainOwnership(organizationId = Id[Organization](1), domainId = Id[Domain](1)))
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
        val domainId = Id[Domain](1)
        val orgDomainOwnership = db.readWrite { implicit s =>
          orgDomainOwnershipRepo.save(OrganizationDomainOwnership(organizationId = orgId, domainId = domainId))
        }

        db.readOnlyMaster { implicit s =>
          orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, domainId) === Some(orgDomainOwnership)
        }
      }
    }

    "get all associated domain ownerships" in {
      withDb() { implicit injector =>
        val orgDomainOwnershipRepo = inject[OrganizationDomainOwnershipRepo]

        val org1Id = Id[Organization](1)

        val orgDomainOwnerships = db.readWrite { implicit s =>
          List(
            OrganizationDomainOwnership(organizationId = org1Id, domainId = Id[Domain](1)),
            OrganizationDomainOwnership(organizationId = org1Id, domainId = Id[Domain](2)),
            OrganizationDomainOwnership(organizationId = org1Id, domainId = Id[Domain](3)),
            OrganizationDomainOwnership(organizationId = Id[Organization](2), domainId = Id[Domain](2)),
            OrganizationDomainOwnership(organizationId = Id[Organization](2), domainId = Id[Domain](4))
          ).map(ownership => orgDomainOwnershipRepo.save(ownership))
        }

        db.readOnlyMaster { implicit s =>
          orgDomainOwnershipRepo.getOwnershipsForOrganization(org1Id) must containTheSameElementsAs(orgDomainOwnerships.filter(_.organizationId == org1Id))
        }
      }
    }

  }

}
