package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.model.{ ProtoOrganizationMembershipRepo, UserFactory }
import com.keepit.model.UserFactoryHelper._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class ProtoOrganizationCommanderTest extends Specification with ShoeboxTestInjector {
  "ProtoOrganizationCommander" should {
    "create a proto org" in {
      withDb() { implicit injector =>
        val owner = db.readWrite { implicit session =>
          UserFactory.user().saved
        }
        val protoOrg = commander.createProtoOrganization(owner.id.get, "Kifi")
        protoOrg.id.isDefined === true
        protoOrg.ownerId === owner.id.get
        protoOrg.name === "Kifi"
      }
    }
    "add members to a proto org" in {
      withDb() { implicit injector =>
        val protoOrgMembershipRepo = inject[ProtoOrganizationMembershipRepo]
        val (owner, users) = db.readWrite { implicit session =>
          (UserFactory.user().saved, UserFactory.users(10).saved)
        }
        val protoOrg = commander.createProtoOrganization(owner.id.get, "Kifi")
        protoOrg.id.isDefined === true
        protoOrg.ownerId === owner.id.get
        protoOrg.name === "Kifi"

        commander.addMembers(protoOrg.id.get, users.map(_.id.get).toSet)

        db.readOnlyMaster { implicit session =>
          protoOrgMembershipRepo.getAllByProtoOrganization(protoOrg.id.get).toSet === users.map(_.id.get).toSet ++ Set(owner.id.get)
        }
      }
    }
  }
  private def commander(implicit injector: Injector) = inject[ProtoOrganizationCommander]
}
