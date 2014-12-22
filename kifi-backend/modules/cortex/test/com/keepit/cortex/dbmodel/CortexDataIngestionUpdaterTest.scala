package com.keepit.cortex.dbmodel

import org.specs2.mutable.Specification
import com.keepit.cortex.CortexTestInjector
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.model._
import com.keepit.common.db.{ ExternalId, SequenceNumber, Id }
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.model.NormalizedURIStates

class CortexDataIngestionUpdaterTest extends Specification with CortexTestInjector {
  "cortex data ingestion updater " should {

    "be able to get data from shoebox client" in {
      withDb() { implicit injector =>
        val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val cortexURIRepo = inject[CortexURIRepo]
        val cortexKeepRepo = inject[CortexKeepRepo]
        val cortexLibRepo = inject[CortexLibraryRepo]
        val cortexLibMemRepo = inject[CortexLibraryMembershipRepo]
        val updater = new CortexDataIngestionUpdater(db, shoebox, cortexURIRepo, cortexKeepRepo, cortexLibRepo, cortexLibMemRepo)

        val savedURIs = shoebox.saveURIs(
          NormalizedURI(url = "url1", urlHash = UrlHash("h1"), seq = SequenceNumber[NormalizedURI](1L)),
          NormalizedURI(url = "url2", urlHash = UrlHash("h2"), seq = SequenceNumber[NormalizedURI](2L)),
          NormalizedURI(url = "url3", urlHash = UrlHash("h3"), seq = SequenceNumber[NormalizedURI](3L))
        )

        var updates = Await.result(updater.updateURIRepo(100), FiniteDuration(5, SECONDS))
        updates === 3

        db.readOnlyMaster { implicit s =>
          cortexURIRepo.all.size === 3
          cortexURIRepo.getSince(SequenceNumber[CortexURI](-1), 100).map { _.seq.value } === List(1, 2, 3)
        }

        shoebox.saveURIs(savedURIs(0).copy(state = NormalizedURIStates.INACTIVE))
        updates = Await.result(updater.updateURIRepo(100), FiniteDuration(5, SECONDS))
        updates === 1

        var changed = db.readOnlyMaster { implicit s =>
          cortexURIRepo.all.size === 3
          cortexURIRepo.getSince(SequenceNumber[CortexURI](3), 100)
        }.headOption.get

        changed.seq.value === 4L
        changed.id.get.id === 1
        changed.uriId.id === 1
        changed.state.value === "inactive"

        shoebox.saveBookmarks(
          Keep(uriId = Id[NormalizedURI](1), url = "url1", urlId = Id[URL](1), source = KeepSource.keeper,
            userId = Id[User](1), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(Id[Library](1)), inDisjointLib = true),
          Keep(uriId = Id[NormalizedURI](2), url = "url1", urlId = Id[URL](2), source = KeepSource.bookmarkImport,
            userId = Id[User](2), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(Id[Library](1)), inDisjointLib = true)
        )

        Await.result(updater.updateKeepRepo(100), FiniteDuration(5, SECONDS)) === 2

        db.readOnlyMaster { implicit s =>
          cortexKeepRepo.all.map { _.source } === List(KeepSource.keeper, KeepSource.bookmarkImport)
        }

        val library = Library(name = "foo", ownerId = Id[User](1), visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("foo"), memberCount = 1)
        shoebox.saveLibraries(
          library,
          library.copy(name = "bar", slug = LibrarySlug("bar"))
        )

        Await.result(updater.updateLibraryRepo(100), FiniteDuration(5, SECONDS)) === 2

        db.readOnlyReplica { implicit s =>
          cortexLibRepo.getByLibraryId(Id[Library](1)).get.seq.value === 1
          cortexLibRepo.getByLibraryId(Id[Library](2)).get.seq.value === 2
        }

        val libMem = LibraryMembership(libraryId = Id[Library](1), userId = Id[User](1), access = LibraryAccess.OWNER)
        shoebox.saveLibraryMemberships(
          libMem,
          libMem.copy(libraryId = Id[Library](2)),
          libMem.copy(libraryId = Id[Library](2), userId = Id[User](2), access = LibraryAccess.READ_ONLY)
        )

        Await.result(updater.updateLibraryMembershipRepo(100), FiniteDuration(5, SECONDS)) === 3
        Await.result(updater.updateLibraryRepo(100), FiniteDuration(5, SECONDS)) === 2

        db.readOnlyReplica { implicit s =>
          cortexLibRepo.getByLibraryId(Id[Library](1)).get.seq.value === 3
          cortexLibRepo.getByLibraryId(Id[Library](2)).get.seq.value === 5
        }
      }
    }
  }
}
