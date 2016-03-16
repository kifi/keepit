package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.KeepFactory.PartialKeep
import org.apache.commons.lang3.RandomStringUtils.random

import scala.util.Try

object KeepFactoryHelper {

  implicit class KeepPersister(partialKeep: PartialKeep) {
    def saved(implicit injector: Injector, session: RWSession): Keep = {

      def fixUriReferences(candidate: Keep): Keep = {
        if (candidate.uriId.id < 0) {
          val unsavedUri: NormalizedURI = NormalizedURI.withHash(candidate.url, Some(s"${random(5)}")).copy(title = candidate.title)
          val uri = injector.getInstance(classOf[NormalizedURIRepo]).save(unsavedUri)
          candidate.copy(uriId = uri.id.get)
        } else candidate
      }

      def fixLibraryReferences(candidate: Keep): Keep = {
        val libRepo = injector.getInstance(classOf[LibraryRepo])
        candidate.libraryId match {
          case Some(libraryId) =>
            val library = libRepo.get(libraryId)
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

      val keep = partialKeep.keep |> fixUriReferences |> fixLibraryReferences

      val libIds = keep.libraryId.toSet ++ partialKeep.explicitLibs.map(_.id.get) ++ partialKeep.implicitLibs.map(_._1)
      val userIds = keep.userId.toSet
      val finalKeep = injector.getInstance(classOf[KeepRepo]).save(keep.copy(id = None).withLibraries(libIds).withParticipants(userIds))
      val libraries = finalKeep.libraryId.toSet[Id[Library]].map(libId => injector.getInstance(classOf[LibraryRepo]).get(libId))

      partialKeep.explicitLibs.foreach { library =>
        val ktl = KeepToLibrary(
          keepId = finalKeep.id.get,
          libraryId = library.id.get,
          addedAt = finalKeep.keptAt,
          addedBy = finalKeep.userId,
          uriId = finalKeep.uriId,
          visibility = library.visibility,
          organizationId = library.organizationId,
          lastActivityAt = finalKeep.lastActivityAt
        )
        injector.getInstance(classOf[KeepToLibraryRepo]).save(ktl)
      }
      partialKeep.implicitLibs.foreach {
        case (libId, vis, orgId) =>
          val ktl = KeepToLibrary(
            keepId = finalKeep.id.get,
            libraryId = libId,
            addedAt = finalKeep.keptAt,
            addedBy = finalKeep.userId,
            uriId = finalKeep.uriId,
            visibility = vis,
            organizationId = orgId,
            lastActivityAt = finalKeep.lastActivityAt
          )
          injector.getInstance(classOf[KeepToLibraryRepo]).save(ktl)
      }
      finalKeep.connections.users.foreach { userId =>
        val ktu = KeepToUser(
          keepId = finalKeep.id.get,
          userId = userId,
          addedAt = finalKeep.keptAt,
          addedBy = finalKeep.userId,
          uriId = finalKeep.uriId,
          lastActivityAt = finalKeep.lastActivityAt
        )
        injector.getInstance(classOf[KeepToUserRepo]).save(ktu)
      }
      finalKeep
    }

  }

  implicit class KeepsPersister(partialKeeps: Seq[PartialKeep]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[Keep] = {
      partialKeeps.map(u => u.saved)
    }
  }
}
