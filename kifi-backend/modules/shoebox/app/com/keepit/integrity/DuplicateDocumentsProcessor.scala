package com.keepit.integrity

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.BookmarkRepo
import com.keepit.model.BookmarkRepoImpl
import com.keepit.model.DeepLinkRepo
import com.keepit.model.DuplicateDocument
import com.keepit.model.DuplicateDocumentRepo
import com.keepit.model.DuplicateDocumentStates
import com.keepit.model.FollowRepo
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIRepo
import com.keepit.model.NormalizedURIRepoImpl
import play.api.Play.current
import play.api.libs.concurrent.Akka
import views.html
import com.keepit.model.URLHistoryCause

@Singleton
class DuplicateDocumentsProcessor @Inject()(
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  normalizedURIRepo: NormalizedURIRepo,
  duplicateDocumentRepo: DuplicateDocumentRepo,
  followRepo: FollowRepo,
  deeplinkRepo: DeepLinkRepo,
  bookmarkRepo: BookmarkRepo,
  orphanCleaner: OrphanCleaner,
  dupeDetect: DuplicateDocumentDetection,
  uriIntegrityPlugin: UriIntegrityPlugin
){

  def mergeUris(old: Id[NormalizedURI], intoNew: Id[NormalizedURI]) = {
    uriIntegrityPlugin.handleChangedUri(MergedUri(oldUri = old, newUri = intoNew))
  }

  private def typedAction(dupAction: HandleDuplicatesAction) = {
    dupAction.action match {
      case "ignore" => DuplicateDocumentStates.IGNORED
      case "merge" => DuplicateDocumentStates.MERGED
      case "unscrapable" => DuplicateDocumentStates.UNSCRAPABLE
    }
  }

  def handleDuplicates(dupId: Either[Id[DuplicateDocument], Id[NormalizedURI]], dupAction: HandleDuplicatesAction) = {
    val dups = dupId match {
      case Left(id) => db.readOnly{ implicit s =>
        List(duplicateDocumentRepo.get(id))     // handle one
      }
      case Right(id) => db.readOnly{ implicit s =>
        duplicateDocumentRepo.getSimilarTo(id)  // handle all
      }
    }

    // according to asyncProcessDocuments, if we have N dups, their uri1Id are the same
    //  this is used as the new uri for all dups
    if (typedAction(dupAction) == DuplicateDocumentStates.MERGED) {
      dups.foreach(dup => mergeUris(old = dup.uri2Id, intoNew = dup.uri1Id))
      db.readWrite { implicit s =>
        dups.foreach { dup => duplicateDocumentRepo.save(dup.withState(typedAction(dupAction))) }
      }
    }
  }

}

case class HandleDuplicatesAction(action: String)

