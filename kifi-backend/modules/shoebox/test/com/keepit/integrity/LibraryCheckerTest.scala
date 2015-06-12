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
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.{ SpecificationLike, Specification }

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
          // Uh oh, looks like the count is wrong!
          (libRepo.save(library.copy(lastKept = tenYearsAgo)), keeps)
        }
        libraryChecker.syncLibraryLastKeptAndKeepCount()
        val updatedLibrary = db.readOnlyMaster { implicit session => libRepo.get(library.id.get) }
        library.lastKept === tenYearsAgo
        updatedLibrary.lastKept must beSome(keeps(19).createdAt)
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
      }
    }
  }
}
