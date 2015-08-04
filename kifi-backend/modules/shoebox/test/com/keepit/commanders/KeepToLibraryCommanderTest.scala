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
            val userLib = LibraryFactory.library().withUser(user).saved
            val keep = KeepFactory.keep().withLibrary(userLib).saved

            val otherLib = LibraryFactory.library().withUser(user).saved
            (user, keep, otherLib)
          }

          val link = db.readWrite { implicit session =>
            val maybeAttachResponse = ktlCommander.attach(KeepToLibraryAttachRequest(keep.id.get, otherLib.id.get, user.id.get))
            maybeAttachResponse.isRight === true
            maybeAttachResponse.right.get.link
          }

          link.keepId === keep.id.get
          link.keeperId === user.id.get
          link.libraryId === otherLib.id.get
        }
      }
      "bail if the user doesn't have write permission" in {
        withDb(modules: _*) { implicit injector =>
          val (user, keep, libs) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withUser(user).saved
            val keep = KeepFactory.keep().withLibrary(userLib).saved

            val rando = UserFactory.user().saved
            val randoOrg = OrganizationFactory.organization().withOwner(rando).saved
            val randoSecretLib = LibraryFactory.library().withUser(rando).secret().saved
            val randoPublicLib = LibraryFactory.library().withUser(rando).published().saved
            val randoOrgLib = LibraryFactory.library().withUser(rando).withOrganization(randoOrg).orgVisible().saved
            (user, keep, Seq(randoPublicLib, randoSecretLib, randoOrgLib))
          }

          db.readWrite { implicit session =>
            for (lib <- libs) {
              ktlCommander.attach(KeepToLibraryAttachRequest(keep.id.get, lib.id.get, user.id.get)) must beLeft
            }
          }
          1 === 1
        }
      }
      "bail if the keep is already in the target library" in {
        withDb(modules: _*) { implicit injector =>
          val (user, keep, lib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withUser(user).saved
            val keep = KeepFactory.keep().withLibrary(userLib).saved

            val rando = UserFactory.user().saved
            val randoLib = LibraryFactory.library().withUser(rando).withCollaborators(Seq(user)).saved

            ktlCommander.attach(KeepToLibraryAttachRequest(keep.id.get, randoLib.id.get, user.id.get)) must beRight
            ktlRepo.getCountByLibraryId(randoLib.id.get) === 1

            (user, keep, randoLib)
          }

          db.readWrite { implicit session =>
            ktlCommander.attach(KeepToLibraryAttachRequest(keep.id.get, lib.id.get, user.id.get)) must beLeft
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
            val userLib = LibraryFactory.library().withUser(user).saved
            val keep = KeepFactory.keep().withLibrary(userLib).saved
            (user, keep, userLib)
          }

          db.readWrite { implicit session =>
            ktlCommander.detach(KeepToLibraryDetachRequest(keep.id.get, userLib.id.get, user.id.get)) must beRight
          }
          1 === 1
        }
      }
      "bail if the link doesn't exist" in {
        withDb(modules: _*) { implicit injector =>
          val (user, keep, libs) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val userLib = LibraryFactory.library().withUser(user).saved
            val keep = KeepFactory.keep().withLibrary(userLib).saved

            val rando = UserFactory.user().saved
            val randoOrg = OrganizationFactory.organization().withOwner(rando).saved
            val randoSecretLib = LibraryFactory.library().withUser(rando).secret().saved
            val randoPublicLib = LibraryFactory.library().withUser(rando).published().saved
            val randoOrgLib = LibraryFactory.library().withUser(rando).withOrganization(randoOrg).orgVisible().saved
            (user, keep, Seq(randoPublicLib, randoSecretLib, randoOrgLib))
          }

          db.readWrite { implicit session =>
            for (lib <- libs) {
              ktlCommander.detach(KeepToLibraryDetachRequest(keep.id.get, lib.id.get, user.id.get)) must beLeft
            }
          }
          1 === 1
        }
      }
    }
  }
}
