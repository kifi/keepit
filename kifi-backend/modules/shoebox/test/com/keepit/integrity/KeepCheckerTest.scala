package com.keepit.integrity

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.KeepSequenceNumberAssigner
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class KeepCheckerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
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

  "KeepChecker" should {
    "fix broken ktl uris" in {
      withDb(modules: _*) { implicit injector =>
        val (keep, library) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val library = LibraryFactory.library().withOwner(user).saved
          val keep = KeepFactory.keep().withUser(user).withLibrary(library).saved
          // Somehow this KTL got messed up
          ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get).foreach { ktl =>
            ktlRepo.save(ktl.withUriId(Id[NormalizedURI](42)))
          }
          (keep, library)
        }

        db.readOnlyMaster { implicit session =>
          ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get).get.uriId !== keep.uriId
        }

        // Let's see if the checker will fix it
        inject[KeepSequenceNumberAssigner].assignSequenceNumbers()
        keepChecker.check()

        db.readOnlyMaster { implicit session =>
          ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get).get.uriId === keep.uriId
        }
      }
    }
    "fix broken ktl states" in {
      withDb(modules: _*) { implicit injector =>
        val (keep, library) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val library = LibraryFactory.library().withOwner(user).saved
          val keep = KeepFactory.keep().withUser(user).withLibrary(library).saved

          keepCommander.deactivateKeep(keep)
          // Somehow this KTL got messed up
          ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get, excludeStateOpt = None).foreach { ktl =>
            ktlRepo.save(ktl.withState(KeepToLibraryStates.ACTIVE))
          }
          (keep, library)
        }

        // Make sure it actually is broken
        db.readOnlyMaster { implicit session =>
          ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get, excludeStateOpt = None).get.state !== KeepToLibraryStates.INACTIVE
          inject[SystemValueRepo].all.foreach(println)
        }

        // Let's see if the checker will fix it
        inject[KeepSequenceNumberAssigner].assignSequenceNumbers()
        keepChecker.check()

        // Make sure it got fixed
        db.readOnlyMaster { implicit session =>
          ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get, excludeStateOpt = None).get.state === KeepToLibraryStates.INACTIVE
        }
      }
    }
    "fix broken library hashes" in {
      withDb(modules: _*) { implicit injector =>
        val (keep, library) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val library = LibraryFactory.library().withOwner(user).saved
          val keep = KeepFactory.keep().withUser(user).withLibrary(library).saved

          // Somehow this library hash got messed up
          val brokenKeep = keepRepo.save(keep.withLibraries(Set.empty))
          (brokenKeep, library)
        }

        db.readOnlyMaster { implicit session =>
          keepRepo.get(keep.id.get).librariesHash !== Some(LibrariesHash(Set(library.id.get)))
        }

        // Let's see if the checker will fix it
        inject[KeepSequenceNumberAssigner].assignSequenceNumbers()
        keepChecker.check()

        db.readOnlyMaster { implicit session =>
          keepRepo.get(keep.id.get).librariesHash === Some(LibrariesHash(Set(library.id.get)))
        }
      }
    }
    "fix broken participant hashes" in {
      withDb(modules: _*) { implicit injector =>
        val (keep, user) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val library = LibraryFactory.library().withOwner(user).saved
          val keep = KeepFactory.keep().withUser(user).withLibrary(library).saved

          // Somehow this library hash got messed up
          val brokenKeep = keepRepo.save(keep.withParticipants(Set.empty))
          (brokenKeep, user)
        }

        db.readOnlyMaster { implicit session =>
          keepRepo.get(keep.id.get).participantsHash !== Some(ParticipantsHash(Set(user.id.get)))
        }

        // Let's see if the checker will fix it
        inject[KeepSequenceNumberAssigner].assignSequenceNumbers()
        keepChecker.check()

        db.readOnlyMaster { implicit session =>
          keepRepo.get(keep.id.get).participantsHash === Some(ParticipantsHash(Set(user.id.get)))
        }
      }
    }
  }
}
