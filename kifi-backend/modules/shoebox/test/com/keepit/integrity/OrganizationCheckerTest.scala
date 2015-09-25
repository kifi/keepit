package com.keepit.integrity

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class OrganizationCheckerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  "OrganizationChecker" should {
    "fix the db when an org is deleted improperly" in {
      withDb(modules: _*) { implicit injector =>
        val (org, owner, members, systemLibs, userLibs) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val members = UserFactory.users(10).saved
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(members).saved
          val userLibs = members.flatMap { user => LibraryFactory.libraries(3).map(_.withOwner(user).withOrganization(org)).saved }
          val systemLibs = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.SYSTEM_ORG_GENERAL)

          // Now we do the REALLY BAD THING (TM)
          orgRepo.deactivate(org)

          (org, owner, members, systemLibs, userLibs)
        }

        val users = owner +: members

        // Make sure it actually is broken
        db.readOnlyMaster { implicit session =>
          orgMembershipRepo.getAllByOrgId(org.id.get).size === users.size
          libraryRepo.getBySpace(org.id.get) === systemLibs ++ userLibs
        }

        // Let's see if the checker will fix it
        inject[OrganizationSequenceNumberAssigner].assignSequenceNumbers()
        organizationChecker.check()

        // Make sure it got fixed
        db.readOnlyMaster { implicit session =>
          orgMembershipRepo.getAllByOrgId(org.id.get) === Set.empty
          libraryRepo.getBySpace(org.id.get) === Set.empty
        }
      }
    }
    "add in system libraries to orgs that do not have them" in {
      withDb(modules: _*) { implicit injector =>
        val (org, owner, members, libsToBreak) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val members = UserFactory.users(10).saved
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(members).saved
          val libsToBreak = libraryRepo.getBySpace(org.id.get)
          (org, owner, members, libsToBreak)
        }

        val users = owner +: members

        // Break it
        libsToBreak.foreach { lib =>
          Await.result(libraryCommander.unsafeAsyncDeleteLibrary(lib.id.get), Duration.Inf)
        }

        // Make sure it's broken
        db.readOnlyMaster { implicit session =>
          libraryRepo.getBySpace(org.id.get) === Set.empty
          libsToBreak.foreach { lib =>
            libraryMembershipRepo.getWithLibraryId(lib.id.get) === Seq.empty
          }
        }

        // Let's see if the checker will fix it
        inject[OrganizationSequenceNumberAssigner].assignSequenceNumbers()
        organizationChecker.check()

        // Make sure it got fixed
        db.readOnlyMaster { implicit session =>
          val systemLibs = libraryRepo.getBySpace(org.id.get)
          val orgGeneralLib = systemLibs.find(_.kind == LibraryKind.SYSTEM_ORG_GENERAL).get
          libraryMembershipRepo.getWithLibraryId(orgGeneralLib.id.get).map(_.userId).toSet === users.map(_.id.get).toSet
        }
      }
    }
  }
}
