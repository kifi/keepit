package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.actor.TestKitSupport
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._

class KeepToLibraryCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  implicit def ktlCommander(implicit injector: Injector) = inject[KeepToLibraryCommander]
  implicit def ktlRepo(implicit injector: Injector) = inject[KeepToLibraryRepo]

  def modules = Seq()

  "KeepToLibraryCommander" should {
    "intern keeps in libraries" in {
      "add a keep if it isn't in the library" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withLibrary(userLib).saved

            val otherLib = LibraryFactory.library().withOwner(user).saved
            val ktl = ktlCommander.internKeepInLibrary(keep, otherLib, user.id.get)

            ktl.keepId === keep.id.get
            ktl.addedBy === user.id.get
            ktl.libraryId === otherLib.id.get
          }
          1 === 1
        }
      }
      "do nothing if the keep is already in the target library" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user1 = UserFactory.user().saved
            val user2 = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user1).withCollaborators(Seq(user2)).saved
            val keep = KeepFactory.keep().withUser(user1).withLibrary(lib).saved

            // user1 re-interns the keep
            ktlCommander.internKeepInLibrary(keep, lib, user1.id.get).addedBy === user1.id.get
            ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib.id.get).get.addedBy === user1.id.get

            // user2 re-interns the keep
            ktlCommander.internKeepInLibrary(keep, lib, user2.id.get).addedBy === user1.id.get
            ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib.id.get).get.addedBy === user1.id.get
          }
          1 === 1
        }
      }
    }
    "remove keeps from libraries" in {
      "remove a keep from a library" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withUser(user).withLibrary(userLib).saved
            ktlCommander.removeKeepFromLibrary(keep.id.get, userLib.id.get) must beSuccessfulTry
          }
          1 === 1
        }
      }
      "bail if the keep isn't in the library" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withUser(user).withLibrary(userLib).saved

            val rando = UserFactory.user().saved
            val randoOrg = OrganizationFactory.organization().withOwner(rando).saved
            val randoSecretLib = LibraryFactory.library().withOwner(rando).secret().saved
            val randoPublicLib = LibraryFactory.library().withOwner(rando).published().saved
            val randoOrgLib = LibraryFactory.library().withOwner(rando).withOrganization(randoOrg).orgVisible().saved

            for (lib <- Seq(randoPublicLib, randoSecretLib, randoOrgLib)) {
              ktlCommander.removeKeepFromLibrary(keep.id.get, lib.id.get) must beFailedTry(KeepToLibraryFail.NOT_IN_LIBRARY)
            }
          }
          1 === 1
        }
      }
    }
  }
}
