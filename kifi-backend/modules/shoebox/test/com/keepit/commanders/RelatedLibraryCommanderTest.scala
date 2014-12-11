package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.cortex.FakeCortexServiceClientImpl
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class RelatedLibraryCommanderTest extends Specification with ShoeboxTestInjector {

  val fakeCortex = new FakeCortexServiceClientImpl(null) {
    override def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt): Future[Seq[Id[Library]]] = {
      if (libId == Id[Library](1)) return Future.successful((2 to 5).map { Id[Library](_) })
      else return Future.successful(Seq())
    }
  }

  "RelatedLibraryCommander" should withDb() { implicit injector =>

    def setup(): Unit = {
      val libRepo = inject[LibraryRepo]
      val libMemRepo = inject[LibraryMembershipRepo]

      db.readWrite { implicit s =>
        (1 to 10).foreach { i =>
          val lib = Library(name = s"Library ${i}", ownerId = Id[User](i), visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("slug"), memberCount = i)
          libRepo.save(lib)
        }
      }

      db.readWrite { implicit s =>
        libMemRepo.save(LibraryMembership(libraryId = Id[Library](2), userId = Id[User](1), access = LibraryAccess.READ_WRITE, showInSearch = true))
        libMemRepo.save(LibraryMembership(libraryId = Id[Library](3), userId = Id[User](2), access = LibraryAccess.READ_WRITE, showInSearch = true))
        libMemRepo.save(LibraryMembership(libraryId = Id[Library](3), userId = Id[User](3), access = LibraryAccess.READ_ONLY, showInSearch = true))
      }
    }

    step { setup() }

    "query related libraries" in {

      val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], null, null, fakeCortex)

      val libsF = commander.relatedLibraries(Id[Library](1))
      Await.result(libsF, FiniteDuration(5, SECONDS)).sortBy(_.id.get).map { _.id.get.id } === List(2, 3, 4, 5)

      val libsF2 = commander.relatedLibraries(Id[Library](6))
      Await.result(libsF2, FiniteDuration(5, SECONDS)).sortBy(_.id.get).map { _.id.get.id } === List()
    }

    "get top followed libraries" in {

      val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], null, null, fakeCortex)

      val libsF = commander.topFollowedLibraries(5, 10)
      Await.result(libsF, FiniteDuration(5, SECONDS)).sortBy(_.id.get).map { _.id.get.id } === List(6, 7, 8, 9, 10)
    }

    "be smart when no related libraries were found" in {

      val commander = new RelatedLibraryCommanderImpl(db, inject[LibraryRepo], null, null, fakeCortex)

      val libsF = commander.suggestedLibraries(Id[Library](1))
      Await.result(libsF, FiniteDuration(5, SECONDS)).sortBy(_.id.get).map { _.id.get.id }.toList === List(2, 3, 4, 5)

      val libsF2 = commander.suggestedLibraries(Id[Library](6))
      Await.result(libsF2, FiniteDuration(5, SECONDS)).sortBy(_.id.get).map { _.id.get.id }.toList === List(7, 8, 9, 10)

      val libsF3 = commander.suggestedLibraries(Id[Library](2))
      Await.result(libsF3, FiniteDuration(5, SECONDS)).sortBy(_.id.get).map { _.id.get.id }.toList === List(6, 7, 8, 9, 10)

    }
  }

}
