package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.model.{ OrganizationFactory, ProtoOrganizationMembershipRepo, UserFactory }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class ProtoOrganizationCommanderTest extends Specification with ShoeboxTestInjector {
  "ProtoOrganizationCommander" should {
    "add proto members to an org" in {
      withDb() { implicit injector =>
        val protoOrgMembershipRepo = inject[ProtoOrganizationMembershipRepo]
        val (org, owner, users) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val users = UserFactory.users(10).saved
          val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).saved
          (org, owner, users)
        }

        commander.addProtoMembers(org.id.get, users.map(_.id.get).toSet)

        db.readOnlyMaster { implicit session =>
          protoOrgMembershipRepo.getAllByOrgId(org.id.get).toSet === users.map(_.id.get).toSet ++ Set(owner.id.get)
        }
      }
    }
  }
  private def commander(implicit injector: Injector) = inject[ProtoOrganizationCommander]
}
