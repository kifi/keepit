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

      val libRepo = injector.getInstance(classOf[LibraryRepo])
      val keep = partialKeep.keep |> fixUriReferences

      val finalKeep = {
        val libIds = partialKeep.explicitLibs.map(_.id.get) ++ partialKeep.implicitLibs.map(_._1)
        val userIds = keep.userId.toSet
        injector.getInstance(classOf[KeepRepo]).save(keep.copy(id = None).withLibraries(libIds.toSet).withParticipants(userIds))
      }
      val libraries = {
        partialKeep.explicitLibs ++ partialKeep.implicitLibs.map {
          case imp @ (libId, vis, orgId) => Try(libRepo.get(libId)).toOption match {
            case Some(lib) if lib.visibility == vis && lib.organizationId == orgId => lib
            case None => libRepo.save(LibraryFactory.library().withVisibility(vis).withOrganizationIdOpt(orgId).get)
            case _ => throw new RuntimeException(s"Keep has implicit library $imp but db does not match")
          }
        }
      }

      def incrementKeepCount(libId: Id[Library]) = {
        val lib = libRepo.get(libId)
        libRepo.save(lib.copy(keepCount = lib.keepCount + 1))
      }

      libraries.foreach { library =>
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
        incrementKeepCount(library.id.get)
      }
      finalKeep.recipients.users.foreach { userId =>
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
      finalKeep.recipients.emails.foreach { emailAddress =>
        val kte = KeepToEmail(
          keepId = finalKeep.id.get,
          emailAddress = emailAddress,
          addedAt = finalKeep.keptAt,
          addedBy = finalKeep.userId,
          uriId = finalKeep.uriId,
          lastActivityAt = finalKeep.lastActivityAt
        )
        injector.getInstance(classOf[KeepToEmailRepo]).save(kte)
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
