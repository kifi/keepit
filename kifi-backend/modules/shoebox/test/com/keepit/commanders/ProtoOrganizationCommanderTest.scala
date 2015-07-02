package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.model.UserFactory
import com.keepit.model.UserFactoryHelper._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class ProtoOrganizationCommanderTest extends Specification with ShoeboxTestInjector {
  "ProtoOrganizationCommander" should {
    "create a proto org" in {
      withDb() { implicit injector =>
        println("foo!")
        val owner = db.readWrite { implicit session =>
          UserFactory.user().saved
        }
        val protoOrg = commander.createProtoOrganization(owner.id.get, "Kifi")
        protoOrg.id.isDefined === true
        protoOrg.ownerId === owner.id.get
        protoOrg.name === "Kifi"
      }
    }

  }
  private def commander(implicit injector: Injector) = inject[ProtoOrganizationCommander]
}
