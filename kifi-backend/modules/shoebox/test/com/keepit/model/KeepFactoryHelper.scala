package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.KeepFactory.PartialKeep
import org.apache.commons.lang3.RandomStringUtils.random
import com.keepit.common.core._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryFactory._

object KeepFactoryHelper {

  implicit class KeepPersister(partialKeep: PartialKeep) {
    def saved(implicit injector: Injector, session: RWSession): Keep = {

      def fixUriReferences(candidate: Keep): Keep = {
        if (candidate.urlId.id < 0 && candidate.uriId.id < 0) {
          val unsavedUri: NormalizedURI = NormalizedURI.withHash(candidate.url, Some(s"${random(5)}")).copy(title = candidate.title)
          val uri = injector.getInstance(classOf[NormalizedURIRepo]).save(unsavedUri)
          val url = injector.getInstance(classOf[URLRepo]).save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))
          candidate.copy(uriId = uri.id.get, urlId = url.id.get)
        } else candidate
      }

      def fixLibraryReferences(candidate: Keep): Keep = {
        val libRepo = injector.getInstance(classOf[LibraryRepo])
        candidate.libraryId match {
          case Some(libraryId) =>
            val library = libRepo.get(candidate.libraryId.get)
            libRepo.save(library.copy(lastKept = Some(candidate.createdAt), keepCount = library.keepCount + 1))
          case None =>
          // This would be great. However, we have tests that test the number of libraries.
          // When keep.libraryId is not optional, this can be uncommented safely.
          //            val lib = libRepo.getAllByOwner(candidate.userId, Some(LibraryStates.INACTIVE)).headOption.getOrElse {
          //              library().withUser(candidate.userId).withVisibility(candidate.visibility).saved
          //            }
          //            libRepo.save(lib.copy(lastKept = Some(candidate.createdAt)))
        }
        candidate
      }

      val keep = {
        val candidate = partialKeep.get
        fixUriReferences(candidate) |> fixLibraryReferences
      }
      injector.getInstance(classOf[KeepRepo]).save(keep.copy(id = None))
    }

  }

  implicit class KeepsPersister(partialKeeps: Seq[PartialKeep]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[Keep] = {
      partialKeeps.map(u => u.saved)
    }
  }
}
