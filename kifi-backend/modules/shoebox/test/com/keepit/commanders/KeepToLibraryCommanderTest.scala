package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.actor.TestKitSupport
import com.keepit.heimdal.HeimdalContext
import com.keepit.integrity.{ URIMigration, UriIntegrityPlugin }
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike

import scala.util.Random

class KeepToLibraryCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty

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
    "be able to move keeps between libraries" in {
      "not cause db constraint violations" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib1 = LibraryFactory.library().withOwner(user).saved
            val lib2 = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withUser(user).withLibrary(lib1).saved
            ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib1.id.get) must beSome
            ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib2.id.get) must beNone

            for (i <- 1 to 10) {
              // lib1 -> lib2
              ktlCommander.removeKeepFromLibrary(keep.id.get, lib1.id.get)
              ktlCommander.internKeepInLibrary(keep, lib2, user.id.get)
              ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib1.id.get) must beNone
              ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib2.id.get) must beSome

              // lib2 -> lib1
              ktlCommander.removeKeepFromLibrary(keep.id.get, lib2.id.get)
              ktlCommander.internKeepInLibrary(keep, lib1, user.id.get)
              ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib1.id.get) must beSome
              ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib2.id.get) must beNone
            }
            ktlRepo.count === 2

            for (i <- 1 to 10) {
              ktlCommander.removeKeepFromLibrary(keep.id.get, lib1.id.get)
              ktlCommander.internKeepInLibrary(keep, lib1, user.id.get)
              ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib1.id.get) must beSome
              ktlRepo.getByKeepIdAndLibraryId(keep.id.get, lib2.id.get) must beNone
            }
            ktlRepo.count === 2
          }
          1 === 1
        }
      }
    }
    "keep up with uri migrations" in {
      "ensure that ktls stay in sync with their keeps" in {
        withDb(modules: _*) { implicit injector =>
          val uriRepo = inject[NormalizedURIRepo]

          // Setup a bunch of URIs
          val numUris = 100
          val (user, lib, origUris, dupUris) = db.readWrite { implicit session =>
            val urls = for (i <- 1 to numUris) yield s"http://${RandomStringUtils.randomAlphanumeric(10)}.com"
            val uris = urls.map { url => normalizedURIInterner.internByUri(url, contentWanted = true) }
            uriRepo.getByState(NormalizedURIStates.ACTIVE, -1).size === numUris

            val (origUris, dupUris) = Random.shuffle(uris).splitAt(numUris / 2)

            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            uris.foreach { uri =>
              KeepFactory.keep().withUser(user).withLibrary(lib).withUri(uri).saved
            }
            (user, lib, origUris, dupUris)
          }

          // Mark some of them as duplicate and schedule a URI migration
          val plugin = inject[UriIntegrityPlugin]
          plugin.onStart()
          for ((origUri, dupUri) <- origUris zip dupUris) {
            plugin.handleChangedUri(URIMigration(dupUri.id.get, origUri.id.get))
          }
          inject[ChangedURISeqAssigner].assignSequenceNumbers()

          // Do the migration
          plugin.batchURIMigration()

          // Make sure the KTLs did the right thing
          db.readOnlyMaster { implicit session =>
            val keeps = inject[KeepRepo].all
            keeps.count(_.isPrimary) === origUris.length
            keeps.count(_.isActive) === origUris.length
            keeps.count(!_.isPrimary) === dupUris.length
            keeps.map(_.uriId).toSet === origUris.map(_.id.get).toSet
            keeps.foreach { keep =>
              val ktls = ktlRepo.getAllByKeepId(keep.id.get, excludeStateOpt = None)
              ktls.size === 1
              ktls.head.isPrimary === keep.isPrimary
              ktls.head.state === keep.state
              ktls.head.uriId === keep.uriId
              ktls.head.visibility === keep.visibility
            }
          }
          1 === 1
        }
      }
    }
  }
}
