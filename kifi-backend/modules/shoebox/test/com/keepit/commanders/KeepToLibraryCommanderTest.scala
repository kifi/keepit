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
    "attach keeps to libraries" in {
      "properly process an attach request" in {
        withDb(modules: _*) { implicit injector =>
          val (user, keep, otherLib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withLibrary(userLib).saved

            val otherLib = LibraryFactory.library().withOwner(user).saved
            (user, keep, otherLib)
          }

          val ktl = db.readWrite { implicit session =>
            val maybeAttachResponse = ktlCommander.internKeepToLibrary(KeepToLibraryInternRequest(keep.id.get, otherLib.id.get, user.id.get))
            maybeAttachResponse.isRight === true
            maybeAttachResponse.right.get.ktl
          }

          ktl.keepId === keep.id.get
          ktl.addedBy === user.id.get
          ktl.libraryId === otherLib.id.get
        }
      }
      "bail if the user doesn't have write permission" in {
        withDb(modules: _*) { implicit injector =>
          val (user, keep, libs) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withLibrary(userLib).saved

            val rando = UserFactory.user().saved
            val randoOrg = OrganizationFactory.organization().withOwner(rando).saved
            val randoSecretLib = LibraryFactory.library().withOwner(rando).secret().saved
            val randoPublicLib = LibraryFactory.library().withOwner(rando).published().saved
            val randoOrgLib = LibraryFactory.library().withOwner(rando).withOrganization(randoOrg).orgVisible().saved
            (user, keep, Seq(randoPublicLib, randoSecretLib, randoOrgLib))
          }

          db.readWrite { implicit session =>
            for (lib <- libs) {
              ktlCommander.internKeepToLibrary(KeepToLibraryInternRequest(keep.id.get, lib.id.get, user.id.get)) must beLeft
            }
          }
          1 === 1
        }
      }
      "do nothing if the keep is already in the target library" in {
        withDb(modules: _*) { implicit injector =>
          val (user1, user2, keep, lib) = db.readWrite { implicit session =>
            val user1 = UserFactory.user().saved
            val user2 = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user1).withCollaborators(Seq(user2)).saved
            val keep = KeepFactory.keep().withUser(user1).withLibrary(lib).saved
            (user1, user2, keep, lib)
          }

          db.readWrite { implicit session =>
            // user1 re-interns the keep
            ktlCommander.internKeepToLibrary(KeepToLibraryInternRequest(keep.id.get, lib.id.get, user1.id.get)) must beRight
            ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib.id.get).get.addedBy === user1.id.get

            // user2 re-interns the keep
            ktlCommander.internKeepToLibrary(KeepToLibraryInternRequest(keep.id.get, lib.id.get, user2.id.get)) must beRight
            ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib.id.get).get.addedBy === user1.id.get
          }
          1 === 1
        }
      }
    }
    "detach keeps from libraries" in {
      "properly process a detach request" in {
        withDb(modules: _*) { implicit injector =>
          val (user, keep, userLib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withUser(user).withLibrary(userLib).saved
            (user, keep, userLib)
          }

          db.readWrite { implicit session =>
            ktlCommander.removeKeepFromLibrary(KeepToLibraryRemoveRequest(keep.id.get, userLib.id.get, user.id.get)) must beRight
          }
          1 === 1
        }
      }
      "bail if the keep isn't in the library" in {
        withDb(modules: _*) { implicit injector =>
          val (user, keep, libs) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withUser(user).withLibrary(userLib).saved

            val rando = UserFactory.user().saved
            val randoOrg = OrganizationFactory.organization().withOwner(rando).saved
            val randoSecretLib = LibraryFactory.library().withOwner(rando).secret().saved
            val randoPublicLib = LibraryFactory.library().withOwner(rando).published().saved
            val randoOrgLib = LibraryFactory.library().withOwner(rando).withOrganization(randoOrg).orgVisible().saved
            (user, keep, Seq(randoPublicLib, randoSecretLib, randoOrgLib))
          }

          db.readWrite { implicit session =>
            for (lib <- libs) {
              ktlCommander.removeKeepFromLibrary(KeepToLibraryRemoveRequest(keep.id.get, lib.id.get, user.id.get)) must beLeft
            }
          }
          1 === 1
        }
      }
    }
  }
}
