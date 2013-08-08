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
import com.keepit.model.CommentRepo
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


case class DisplayedDuplicate(id: Id[DuplicateDocument], normUriId: Id[NormalizedURI], url: String, percentMatch: Double)
case class DisplayedDuplicates(normUriId: Id[NormalizedURI], url: String, dupes: Seq[DisplayedDuplicate])

class DuplicateDocumentsController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  normalizedURIRepo: NormalizedURIRepo,
  duplicateDocumentRepo: DuplicateDocumentRepo,
  followRepo: FollowRepo,
  deeplinkRepo: DeepLinkRepo,
  commentRepo: CommentRepo,
  bookmarkRepo: BookmarkRepo,
  orphanCleaner: OrphanCleaner,
  dupeDetect: DuplicateDocumentDetection,
  duplicatesProcessor: DuplicateDocumentsProcessor)
    extends AdminController(actionAuthenticator) {

  def orphanCleanup() = AdminHtmlAction { implicit request =>
    Akka.future {
      db.readWrite { implicit session =>
        orphanCleaner.cleanNormalizedURIs(false)
        orphanCleaner.cleanScrapeInfo(false)
      }
    }
    Redirect(com.keepit.integrity.routes.DuplicateDocumentsController.documentIntegrity())
  }

  def documentIntegrity(page: Int = 0, size: Int = 50) = AdminHtmlAction { implicit request =>
    val dupes = db.readOnly { implicit conn =>
      duplicateDocumentRepo.getActive(page, size)
    }

    val groupedDupes = dupes.groupBy { case d => d.uri1Id }.toSeq.sortWith((a,b) => a._1.id < b._1.id)

    val loadedDupes = db.readOnly { implicit session =>
      groupedDupes map  { d =>
        val dupeRecords = d._2.map { sd =>
          DisplayedDuplicate(sd.id.get, sd.uri2Id, normalizedURIRepo.get(sd.uri2Id).url, sd.percentMatch)
        }
        DisplayedDuplicates(d._1, normalizedURIRepo.get(d._1).url, dupeRecords)
      }
    }

    Ok(html.admin.documentIntegrity(loadedDupes))
  }

  def handleDuplicate = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = body("action").head
    val id = Id[DuplicateDocument](body("id").head.toLong)
    duplicatesProcessor.handleDuplicates(Left[Id[DuplicateDocument], Id[NormalizedURI]](id), HandleDuplicatesAction(action))
    Ok
  }

  def handleDuplicates = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = body("action").head
    val id = Id[NormalizedURI](body("id").head.toLong)
    duplicatesProcessor.handleDuplicates(Right[Id[DuplicateDocument], Id[NormalizedURI]](id), HandleDuplicatesAction(action))
    Ok
  }

  def duplicateDocumentDetection = AdminHtmlAction { implicit request =>
    dupeDetect.asyncProcessDocuments()
    Redirect(com.keepit.integrity.routes.DuplicateDocumentsController.documentIntegrity())
  }

}
