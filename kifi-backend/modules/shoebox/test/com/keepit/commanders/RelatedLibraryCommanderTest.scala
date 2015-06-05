package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientImpl
import com.keepit.curator.LibraryQualityHelper
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class RelatedLibraryCommanderTest extends Specification with ShoeboxTestInjector {

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

  val fakeCortex = new FakeCortexServiceClientImpl(null) {
    override def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt): Future[Seq[Id[Library]]] = {
      if (libId == Id[Library](1)) return Future.successful((2 to 5).map { Id[Library](_) })
      else if (libId == Id[Library](10)) return Future.successful(Seq(Id[Library](11)))
      else return Future.successful(Seq())
    }
  }

  "RelatedLibraryCommander" should {

    def setup(implicit injector: Injector): Unit = {
      val libRepo = inject[LibraryRepo]
      val userRepo = inject[UserRepo]
      val libMemRepo = inject[LibraryMembershipRepo]

      db.readWrite { implicit s =>
        (1 to 10).foreach { i => UserFactory.user().withName("test" + i, "foo").withUsername("whatever" + i).saved }

        (1 to 10).foreach { i =>
          val lib = Library(name = s"Library ${i}", ownerId = Id[User](i), visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("slug"), memberCount = i)
          libRepo.save(lib)

          // user i owns library i
          libMemRepo.save(LibraryMembership(libraryId = Id[Library](i), userId = Id[User](i), access = LibraryAccess.READ_WRITE))

          // a few followers for library i
          (1 until i).foreach { j =>
            libMemRepo.save(LibraryMembership(libraryId = Id[Library](i), userId = Id[User](j), access = LibraryAccess.READ_ONLY))
          }
        }

        // 1 secret library
        libRepo.save(Library(name = s"Library 11", ownerId = Id[User](1), visibility = LibraryVisibility.SECRET, slug = LibrarySlug("slug"), memberCount = 1))

        // 1 other library from user 1
        libRepo.save(Library(name = s"Library 12", ownerId = Id[User](1), visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("slug"), memberCount = 3))

      }
    }

    "query related libraries" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)
        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], inject[LibraryMembershipRepo], inject[LibraryCommander], fakeCortex, inject[UserCommander], inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null)

        val libsF = commander.topicRelatedLibraries(Id[Library](1))
        Await.result(libsF, FiniteDuration(5, SECONDS)).map { _.library }.sortBy(_.id.get).map { _.id.get.id } === List(2, 3, 4, 5)
        Await.result(libsF, FiniteDuration(5, SECONDS)).map { _.kind }.toSet === Set(RelatedLibraryKind.TOPIC)

        val libsF2 = commander.topicRelatedLibraries(Id[Library](6))
        Await.result(libsF2, FiniteDuration(5, SECONDS)).map { _.library }.sortBy(_.id.get).map { _.id.get.id } === List()

      }
    }

    "do not show non-publised libraries" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)
        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], inject[LibraryMembershipRepo], inject[LibraryCommander], fakeCortex, inject[UserCommander], inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null)
        val libsF = commander.topicRelatedLibraries(Id[Library](10))
        Await.result(libsF, FiniteDuration(5, SECONDS)).map { _.library }.sortBy(_.id.get).map { _.id.get.id } === List()
      }
    }

    "get libraries from same owner" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)
        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], inject[LibraryMembershipRepo], inject[LibraryCommander], fakeCortex, inject[UserCommander], inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null)
        val libsF = commander.librariesFromSameOwner(Id[Library](1), minFollow = 1)
        Await.result(libsF, FiniteDuration(5, SECONDS)).map { _.library.id.get.id } === List(12)
      }
    }

    "get top followed libraries" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)
        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], null, null, fakeCortex, inject[UserCommander], inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null)

        val libsF = commander.topFollowedLibraries(5)
        Await.result(libsF, FiniteDuration(5, SECONDS)).map { _.library }.sortBy(_.id.get).map { _.id.get.id } === List(6, 7, 8, 9, 10)
        Await.result(libsF, FiniteDuration(5, SECONDS)).map { _.kind }.toSet === Set(RelatedLibraryKind.POPULAR)

      }
    }

    "fill in topic related libs, same owner libs, and popular libs in order" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)
        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], null, null, fakeCortex, inject[UserCommander], inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null)

        val libsF = commander.suggestedLibraries(Id[Library](1))
        Await.result(libsF, FiniteDuration(5, SECONDS)).map { _.library }.sortBy(_.id.get).map { _.id.get.id }.toList === List(2, 3, 4, 5) ++ List(6, 7, 8, 9, 10)

        val libsF2 = commander.suggestedLibraries(Id[Library](6))
        Await.result(libsF2, FiniteDuration(5, SECONDS)).map { _.library }.sortBy(_.id.get).map { _.id.get.id }.toList === List(7, 8, 9, 10)

        val libsF3 = commander.suggestedLibraries(Id[Library](2))
        Await.result(libsF3, FiniteDuration(5, SECONDS)).map { _.library }.sortBy(_.id.get).map { _.id.get.id }.toList === List(6, 7, 8, 9, 10)

      }
    }

    "get full library info for non-user" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)
        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], inject[LibraryMembershipRepo], inject[LibraryCommander], fakeCortex, inject[UserCommander], inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null)
        val resF = commander.suggestedLibrariesInfo(Id[Library](1), None)
        val res = Await.result(resF, FiniteDuration(5, SECONDS))._1
        res.seq.sortBy(_.numFollowers).map { _.numFollowers } === List(1, 2, 3, 4, _: Int) // last one is random
        res.seq.sortBy(_.numFollowers).map { _.owner.firstName }.take(4) === List(2, 3, 4, 5).map { i => "test" + i }
      }
    }

    "do not show libraries user already know" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)
        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], inject[LibraryMembershipRepo], inject[LibraryCommander], fakeCortex, inject[UserCommander], inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null)
        val resF = commander.suggestedLibrariesInfo(Id[Library](1), Some(Id[User](1)))
        Await.result(resF, FiniteDuration(5, SECONDS))._1.size === 0

        val resF2 = commander.suggestedLibrariesInfo(Id[Library](2), Some(Id[User](8)))
        val res2 = Await.result(resF2, FiniteDuration(5, SECONDS))._1
        res2.seq.sortBy(_.numFollowers).map { _.owner.firstName } === List(6, 7).map { i => "test" + i }

        val resF3 = commander.suggestedLibrariesInfo(Id[Library](6), Some(Id[User](8)))
        val res3 = Await.result(resF3, FiniteDuration(5, SECONDS))._1
        res3.seq.sortBy(_.numFollowers).map { _.owner.firstName } === List(7).map { i => "test" + i }
      }
    }

    "do not show libraries from fake users" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)

        val userCommander = inject[UserCommander]
        val experimentCommander = inject[LocalUserExperimentCommander]
        experimentCommander.addExperimentForUser(Id[User](6), ExperimentType.FAKE)
        experimentCommander.getExperimentsByUser(Id[User](6)).contains(ExperimentType.FAKE) === true

        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], inject[LibraryMembershipRepo], inject[LibraryCommander], fakeCortex, userCommander, inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null)
        val resF = commander.suggestedLibrariesInfo(Id[Library](1), Some(Id[User](1)))
        Await.result(resF, FiniteDuration(5, SECONDS))._1.size === 0

        val resF2 = commander.suggestedLibrariesInfo(Id[Library](2), Some(Id[User](8)))
        val res2 = Await.result(resF2, FiniteDuration(5, SECONDS))._1
        res2.seq.sortBy(_.numFollowers).map { _.owner.firstName } === List(7).map { i => "test" + i }

      }
    }

    "dedup" in {
      withDb(modules: _*) { implicit injector =>
        setup(injector)
        val fakeCortex2 = new FakeCortexServiceClientImpl(null) {
          override def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt): Future[Seq[Id[Library]]] = {
            if (libId == Id[Library](1)) Future.successful(List(2, 12).map { Id[Library](_) })
            else Future.successful(Seq())
          }
        }

        val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], null, null, fakeCortex2, inject[UserCommander], inject[LibraryQualityHelper], inject[RelatedLibrariesCache], inject[TopFollowedLibrariesCache], null) {
          override val DEFAULT_MIN_FOLLOW = 2
        }

        inject[RelatedLibrariesCache].remove(RelatedLibariesKey(Id[Library](1)))
        inject[TopFollowedLibrariesCache].remove(new TopFollowedLibrariesKey())
        val libsF = commander.suggestedLibraries(Id[Library](1))
        val libs = Await.result(libsF, FiniteDuration(5, SECONDS))
        libs.take(2).map { _.library }.map { _.id.get.id }.toList === List(2, 12) // 12 occurs as similar library
        libs.drop(2).map { _.library }.sortBy(_.id.get).map { _.id.get.id }.toList === List(3, 4, 5, 6, 7, 8, 9, 10) // 12 is deduped, even though it's from same owner, and "popular" (DEFAULT_MIN_FOLLOW = 2)

      }
    }
  }

}
