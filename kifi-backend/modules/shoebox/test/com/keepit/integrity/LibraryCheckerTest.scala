package com.keepit.integrity

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.{ Id }
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.{ SpecificationLike }

class LibraryCheckerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
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

  "library checker" should {
    "check keep count" in {
      withDb(modules: _*) { implicit injector =>
        val libraryChecker = inject[LibraryChecker]
        val libRepo = inject[LibraryRepo]
        val library = db.readWrite { implicit session =>
          val library = LibraryFactory.library().withId(Id[Library](1)).saved
          KeepFactory.keeps(20).map(_.withLibrary(library)).saved
          // Uh oh, looks like the count is wrong!
          libRepo.save(library.copy(keepCount = 5))
        }
        libraryChecker.syncLibraryLastKeptAndKeepCount()
        val updatedLibrary = db.readOnlyMaster { implicit session => libRepo.get(library.id.get) }
        library.keepCount === 5
        updatedLibrary.keepCount === 20
      }
    }
    "check last kept time" in {
      withDb(modules: _*) { implicit injector =>
        val tenYearsAgo = Some(DateTime.now().minusYears(10))

        val libraryChecker = inject[LibraryChecker]
        val libRepo = inject[LibraryRepo]
        val (library, keeps) = db.readWrite { implicit session =>
          val library = LibraryFactory.library().withId(Id[Library](1)).saved
          val keeps = KeepFactory.keeps(20).map(_.withLibrary(library)).saved
          // Uh oh, looks like the lastKept time is wrong!
          (libRepo.save(library.copy(lastKept = tenYearsAgo)), keeps)
        }
        libraryChecker.syncLibraryLastKeptAndKeepCount()
        val updatedLibrary = db.readOnlyMaster { implicit session => libRepo.get(library.id.get) }
        library.lastKept === tenYearsAgo
        updatedLibrary.lastKept must beSome(keeps.last.keptAt)
      }
    }

    "check member count" in {
      withDb(modules: _*) { implicit injector =>
        val wrongCount = 5

        val libraryChecker = inject[LibraryChecker]
        val libRepo = inject[LibraryRepo]
        val library = db.readWrite { implicit session =>
          val library = LibraryFactory.library().withId(Id[Library](1)).saved
          val user = UserFactory.user().saved
          LibraryMembershipFactory.memberships(19).map(_.withLibraryFollower(library, user)).saved
          // Uh oh, looks like the count is wrong!
          libRepo.save(library.copy(memberCount = wrongCount))
        }
        inject[LibraryMembershipSequenceNumberAssigner].assignSequenceNumbers()
        db.readOnlyMaster { implicit session => libRepo.get(library.id.get) }.memberCount === wrongCount
        libraryChecker.syncLibraryMemberCounts()
        val updatedLibrary = db.readOnlyMaster { implicit session => libRepo.get(library.id.get) }
        updatedLibrary.memberCount === 20

        val systemValueRepo = inject[SystemValueRepo]
        val (seqNum, memberSeqNum) = db.readOnlyMaster { implicit session =>
          val seqNum = systemValueRepo.getSequenceNumber(libraryChecker.MEMBER_COUNT_NAME).get
          val memberSeqNum = libraryMembershipRepo.all.map(_.seq).max
          (seqNum, memberSeqNum)
        }
        // The last membership seq num is equal to the seqnum we set into libraryChecker.MEMBER_COUNT_NAME systemValueRepo sequence number.
        seqNum === memberSeqNum
      }
    }

    "check visibility" in {
      withDb(modules: _*) { implicit injector =>
        val libraryChecker = inject[LibraryChecker]
        db.readWrite { implicit session =>
          LibraryFactory.library().withId(Id[Library](1)).discoverable().saved
          KeepFactory.keep().withId(Id[Keep](1)).withLibraryId((Id[Library](1), LibraryVisibility.PUBLISHED, None)).saved

          ktlRepo.getAllByKeepId(Id[Keep](1)).head.visibility === LibraryVisibility.PUBLISHED
        }

        libraryChecker.keepVisibilityCheck(Id[Library](1)) === 1

        db.readOnlyReplica { implicit s => ktlRepo.getAllByKeepId(Id[Keep](1)).head.visibility === LibraryVisibility.DISCOVERABLE }
      }
    }
    "fix deleted libraries" in {
      withDb(modules: _*) { implicit injector =>
        val (owner, lib, keeps) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val lib = LibraryFactory.library().withOwner(owner).saved
          val keeps = KeepFactory.keeps(10).map(_.withUser(owner).withLibrary(lib)).saved
          libraryRepo.deactivate(lib)
          val weirdlyDeadLib = libraryRepo.get(lib.id.get)
          (owner, weirdlyDeadLib, keeps)
        }

        // make sure things are broken
        db.readOnlyMaster { implicit session =>
          libraryRepo.get(lib.id.get).state === LibraryStates.INACTIVE
          keepRepo.getByIds(keeps.map(_.id.get).toSet).values.foreach { k => k.state === KeepStates.ACTIVE }
          ktlRepo.getAllByLibraryId(lib.id.get).foreach { ktl => ktl.state === KeepToLibraryStates.ACTIVE }
          ktuRepo.getAllByUserId(owner.id.get).foreach { ktu => ktu.state === KeepToUserStates.ACTIVE }
        }

        inject[LibrarySequenceNumberAssigner].assignSequenceNumbers()
        libraryChecker.syncOnLibraryDelete()

        // Make sure they're fixed
        db.readOnlyMaster { implicit session =>
          libraryRepo.get(lib.id.get).state === LibraryStates.INACTIVE
          keepRepo.getByIds(keeps.map(_.id.get).toSet).values.foreach { k => k.state === KeepStates.INACTIVE }
          ktlRepo.getAllByLibraryId(lib.id.get).foreach { ktl => ktl.state === KeepToLibraryStates.INACTIVE }
          ktuRepo.getAllByUserId(owner.id.get).foreach { ktu => ktu.state === KeepToUserStates.INACTIVE } // TODO(ryan): keepscussions should make this part fail
        }
        1 === 1
      }
    }
  }
}
