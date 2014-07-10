package com.keepit.integrity

import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model.DuplicateDocument
import com.keepit.model.DuplicateDocumentRepo
import com.keepit.model.DuplicateDocumentStates
import com.keepit.model.NormalizedURI

@Singleton
class DuplicateDocumentsProcessor @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    duplicateDocumentRepo: DuplicateDocumentRepo,
    uriIntegrityPlugin: UriIntegrityPlugin) extends Logging {

  def mergeUris(old: Id[NormalizedURI], intoNew: Id[NormalizedURI]) = {
    uriIntegrityPlugin.handleChangedUri(URIMigration(oldUri = old, newUri = intoNew))
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
      case Left(id) => db.readOnlyMaster { implicit s =>
        List(duplicateDocumentRepo.get(id)) // handle one
      }
      case Right(id) => db.readOnlyMaster { implicit s =>
        duplicateDocumentRepo.getSimilarTo(id) // handle all
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

