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
import com.keepit.scraper.DuplicateDocumentDetection

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
  dupeDetect: DuplicateDocumentDetection)
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

  def typedAction(action: String) = action match {
    case "ignore" => DuplicateDocumentStates.IGNORED
    case "merge" => DuplicateDocumentStates.MERGED
    case "unscrapable" => DuplicateDocumentStates.UNSCRAPABLE
  }

  def handleDuplicate = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = typedAction(body("action").head)
    val id = Id[DuplicateDocument](body("id").head.toLong)

    action match {
      case DuplicateDocumentStates.MERGED =>
        db.readOnly { implicit session =>
          val d = duplicateDocumentRepo.get(id)
          mergeUris(d.uri1Id, d.uri2Id)
        }
      case _ =>
    }

    db.readWrite { implicit session =>
      duplicateDocumentRepo.save(duplicateDocumentRepo.get(id).withState(action))
    }
    Ok
  }

  def mergeUris(parentId: Id[NormalizedURI], childId: Id[NormalizedURI]) = {
    // Collect all entities who refer to N2 and change the ref to N1.
    // Update the URL db entity with state: manual fix
    db.readWrite { implicit session =>
      // Bookmark
      bookmarkRepo.getByUri(childId).map { bookmark =>
        bookmarkRepo.save(bookmark.withNormUriId(parentId))
      }
      // Comment
      commentRepo.getByUri(childId).map { comment =>
        commentRepo.save(comment.withNormUriId(parentId))
      }

      // DeepLink
      deeplinkRepo.getByUri(childId).map { deeplink =>
        deeplinkRepo.save(deeplink.withNormUriId(parentId))
      }

      // Follow
      followRepo.getByUri(childId, excludeState = None).map { follow =>
        followRepo.save(follow.withNormUriId(parentId))
      }
    }
  }

  def handleDuplicates = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = body("action").head
    val id = Id[NormalizedURI](body("id").head.toLong)
    db.readWrite { implicit session =>
      duplicateDocumentRepo.getSimilarTo(id) map { dupe =>
        action match {
          case DuplicateDocumentStates.MERGED =>
              mergeUris(dupe.uri1Id, dupe.uri2Id)
          case _ =>
        }
        duplicateDocumentRepo.save(dupe.withState(typedAction(action)))
      }
    }
    Ok
  }

  def duplicateDocumentDetection = AdminHtmlAction { implicit request =>
    dupeDetect.asyncProcessDocuments()
    Redirect(com.keepit.integrity.routes.DuplicateDocumentsController.documentIntegrity())
  }

}
