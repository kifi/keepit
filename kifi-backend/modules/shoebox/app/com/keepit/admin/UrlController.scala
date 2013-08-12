package com.keepit.controllers.admin


import play.api.Play.current
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.healthcheck.BabysitterTimeout
import com.keepit.common.mail._
import play.api.libs.concurrent.Akka
import scala.concurrent.duration._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.integrity.OrphanCleaner
import com.keepit.integrity.DuplicateDocumentDetection
import com.keepit.integrity.DuplicateDocumentsProcessor
import com.keepit.integrity.HandleDuplicatesAction
import com.keepit.integrity.UriIntegrityPlugin
import com.keepit.integrity.ChangedUri

class UrlController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  clock: Clock,
  postOffice: LocalPostOffice,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  collectionRepo: CollectionRepo,
  commentRepo: CommentRepo,
  deepLinkRepo: DeepLinkRepo,
  followRepo: FollowRepo,
  normalizedUriFactory: NormalizedURIFactory,
  duplicateDocumentRepo: DuplicateDocumentRepo,
  orphanCleaner: OrphanCleaner,
  dupeDetect: DuplicateDocumentDetection,
  duplicatesProcessor: DuplicateDocumentsProcessor,
  uriIntegrityPlugin: UriIntegrityPlugin)
    extends AdminController(actionAuthenticator) {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminHtmlAction { implicit request =>
    Ok(html.admin.adminDashboard())
  }

  case class ChangedNormURI(context: String, url: String, currURI: String, from: Long, to: Long, toUri: String)

  def renormalize(readOnly: Boolean = true, domain: Option[String] = None) = AdminHtmlAction { implicit request =>
    Akka.future {
      try {
        val result = doRenormalize(readOnly, domain).replaceAll("\n","\n<br>")
        db.readWrite { implicit s =>
          postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
           subject = "Renormalization Report", htmlBody = result, category = PostOffice.Categories.ADMIN))
        }
      } catch {
        case ex: Throwable => log.error(ex.getMessage, ex)
      }
    }
    Ok("Started! Will email %s".format(EmailAddresses.ENG))
  }

  def doRenormalize(readOnly: Boolean = true, domain: Option[String] = None) = {
    // Processes all models that reference a `NormalizedURI`, and renormalizes all URLs.
    val changedURIs = scala.collection.mutable.MutableList[ChangedNormURI]()
    val (urlsSize, changes) = db.readWrite {implicit session =>

      val urls = domain match {
        case Some(domainStr) => urlRepo.getByDomain(domainStr)
        case None => urlRepo.all
      }

      val urlsSize = urls.size
      val changes = scala.collection.mutable.Map[String, Int]()
      changes += (("url", 0))

      urls map { url =>
        if (url.state == URLStates.ACTIVE) {
          val (normalizedUri, reason) = uriRepo.getByUri(url.url) match {
            // if nuri exists by current normalization rule, and if url.normalizedUriId was pointing to a different nuri, we need to merge
            case Some(nuri) => (nuri, URLHistoryCause.MERGE)
            // No normalized URI exists for this url, create one
            case None => {
              val tmp = normalizedUriFactory(url.url)
              val nuri = if (!readOnly) uriRepo.save(tmp) else tmp
              (nuri, URLHistoryCause.SPLIT)
            }
          }

          // in readOnly mode, id maybe empty
          if (normalizedUri.id.isEmpty || url.normalizedUriId.id != normalizedUri.id.get.id) {
            changes("url") += 1
            if (!readOnly) uriIntegrityPlugin.handleChangedUri(ChangedUri(oldUri = url.normalizedUriId, newUri = normalizedUri.id.get, reason))
          }
        }
      }
      (urlsSize, changes)
    }

    "%s urls processed, changes:<br>\n<br>\n%s".format(urlsSize, changes)
  }

  private def fixCommentSeqNum: Unit = {
    import com.keepit.model.CommentStates
    log.info("started comment seq num fix")
    var count = 0
    var done = false
    while (!done) {
      db.readWrite { implicit session =>
        val comments = commentRepo.getCommentsChanged(SequenceNumber.MinValue, 100)
        val lastCount = count
        done = comments.isEmpty || comments.exists{ comment =>
          if (comment.seq.value != 0L) true
          else {
            commentRepo.save(comment)
            count += 1
            false
          }
        }
        log.info(s"... fixed seq num of ${count - lastCount} comments")
      }
    }
    log.info(s"finished comment seq num fix: ${count}")
  }

  def fixSeqNum = AdminHtmlAction { request =>
    Akka.future {
      try {
        fixCommentSeqNum
      } catch {
        case ex: Throwable => log.error(ex.getMessage, ex)
      }
    }
    Ok("sequence number fix started")
  }

  def orphanCleanup() = AdminHtmlAction { implicit request =>
    Akka.future {
      db.readWrite { implicit session =>
        orphanCleaner.cleanNormalizedURIs(false)
        orphanCleaner.cleanScrapeInfo(false)
      }
    }
    Redirect(com.keepit.controllers.admin.routes.UrlController.documentIntegrity())
  }

  def documentIntegrity(page: Int = 0, size: Int = 50) = AdminHtmlAction { implicit request =>
    val dupes = db.readOnly { implicit conn =>
      duplicateDocumentRepo.getActive(page, size)
    }

    val groupedDupes = dupes.groupBy { case d => d.uri1Id }.toSeq.sortWith((a,b) => a._1.id < b._1.id)

    val loadedDupes = db.readOnly { implicit session =>
      groupedDupes map  { d =>
        val dupeRecords = d._2.map { sd =>
          DisplayedDuplicate(sd.id.get, sd.uri2Id, uriRepo.get(sd.uri2Id).url, sd.percentMatch)
        }
        DisplayedDuplicates(d._1, uriRepo.get(d._1).url, dupeRecords)
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
    Redirect(com.keepit.controllers.admin.routes.UrlController.documentIntegrity())
  }

}


case class DisplayedDuplicate(id: Id[DuplicateDocument], normUriId: Id[NormalizedURI], url: String, percentMatch: Double)
case class DisplayedDuplicates(normUriId: Id[NormalizedURI], url: String, dupes: Seq[DisplayedDuplicate])
