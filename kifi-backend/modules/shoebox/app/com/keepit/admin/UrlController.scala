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
import com.keepit.integrity.MergedUri
import com.keepit.integrity.SplittedUri
import org.joda.time.DateTime
import com.keepit.common.time.zones.PT
import com.keepit.normalizer.{TrustedCandidate, NormalizationService, Prenormalizer}
import scala.concurrent.Await


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
  deepLinkRepo: DeepLinkRepo,
  followRepo: FollowRepo,
  changedUriRepo: ChangedURIRepo,
  duplicateDocumentRepo: DuplicateDocumentRepo,
  ktcRepo: KeepToCollectionRepo,
  orphanCleaner: OrphanCleaner,
  dupeDetect: DuplicateDocumentDetection,
  duplicatesProcessor: DuplicateDocumentsProcessor,
  uriIntegrityPlugin: UriIntegrityPlugin,
  normalizationService: NormalizationService)
    extends AdminController(actionAuthenticator) {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminHtmlAction { implicit request =>
    Ok(html.admin.adminDashboard())
  }

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
              val tmp = NormalizedURI.withHash(Prenormalizer(url.url))
              val nuri = if (!readOnly) uriRepo.save(tmp) else tmp
              (nuri, URLHistoryCause.SPLIT)
            }
          }

          // in readOnly mode, id maybe empty
          if (normalizedUri.id.isEmpty || url.normalizedUriId.id != normalizedUri.id.get.id) {
            changes("url") += 1
            if (!readOnly) {
              reason match {
                case URLHistoryCause.MERGE => uriIntegrityPlugin.handleChangedUri(MergedUri(oldUri = url.normalizedUriId, newUri = normalizedUri.id.get))
                case URLHistoryCause.SPLIT => uriIntegrityPlugin.handleChangedUri(SplittedUri(url = url, newUri = normalizedUri.id.get))
              }

            }
          }
        }
      }
      (urlsSize, changes)
    }

    "%s urls processed, changes:<br>\n<br>\n%s".format(urlsSize, changes)
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
  
  def normalizationView(page: Int = 0) = AdminHtmlAction{ request =>
    implicit val playRequest = request.request
    val PAGE_SIZE = 50
    val (pendingCount, appliedCount, applied) = db.readOnly{ implicit s =>
      val totalCount = changedUriRepo.count
      val appliedCount = changedUriRepo.allAppliedCount()
      val applied = changedUriRepo.page(page, PAGE_SIZE).map{ change =>
        (uriRepo.get(change.oldUriId), uriRepo.get(change.newUriId), change.updatedAt.date.toString())
      }
      (totalCount - appliedCount, appliedCount, applied)
    }
    val pageCount = (appliedCount*1.0 / PAGE_SIZE).ceil.toInt
    Ok(html.admin.normalization(applied, page, appliedCount, pendingCount, pageCount, PAGE_SIZE))
  }
  
  def batchMerge = AdminHtmlAction{ request =>
    implicit val playRequest = request.request
    Await.result(uriIntegrityPlugin.batchUpdateMerge(), 5 seconds)
    Redirect(com.keepit.controllers.admin.routes.UrlController.normalizationView(0))
  }

  def redirect(oldUrl: String, newUrl: String, canonical: Boolean = false) = AdminHtmlAction { request =>
    db.readOnly { implicit session =>
      (uriRepo.getByUri(oldUrl), uriRepo.getByUri(newUrl)) match {
        case (None, _) => Redirect(com.keepit.controllers.admin.routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUrl} could not be found.")
        case (_, None) => Redirect(com.keepit.controllers.admin.routes.UrlController.normalizationView(0)).flashing("result" -> s"${newUrl} could not be found.")
        case (_, Some(newUri)) if newUri.normalization.isEmpty && !canonical =>
          Redirect(com.keepit.controllers.admin.routes.UrlController.normalizationView(0)).flashing("result" -> s"${newUri.id.get}: ${newUri.url} isn't normalized.")
        case (Some(oldUri), Some(newUri)) => {
          val normalization = if (canonical) Normalization.CANONICAL else newUri.normalization.get
          val result = Await.result(normalizationService.update(oldUri, TrustedCandidate(newUri.url, normalization)), 5 seconds)
          if (result.isDefined)
            Redirect(com.keepit.controllers.admin.routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} will be redirected to ${newUri.id.get}: ${newUri.url}")
          else
            Redirect(com.keepit.controllers.admin.routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} cannot be redirected to ${newUri.id.get}: ${newUri.url}")
        }
      }
    }
  }

  def fixSite(readOnly: Boolean = true) = AdminHtmlAction { request =>
    val toBeRedirected = db.readOnly { implicit session => uriRepo.getOldSite() }
    val r = """(https://www\.kifi\.com)/site(.*)""".r
    val filtered = toBeRedirected.filter { u => r.findAllIn(u.url).size == 1 }
    assert(toBeRedirected.size == filtered.size)
    val newUrls = toBeRedirected.map(_.url).map { case r(prefix, suffix) => prefix + suffix }
    if (!readOnly) {
      val newUris = db.readWrite { implicit session => newUrls.map(uriRepo.internByUri(_)) }
      toBeRedirected zip newUris foreach { case (oldUri, newUri) =>
        val id = newUri.id.get
        var newUriWithNormalization = db.readOnly { implicit session => uriRepo.get(id) }
        while (newUriWithNormalization.normalization.isEmpty) {
          Thread.sleep(5000)
          newUriWithNormalization = db.readOnly { implicit session => uriRepo.get(id) }
        }
        normalizationService.update(oldUri, TrustedCandidate(newUri.url, newUriWithNormalization.normalization.get))
      }
    }
    Ok(s"[READONLY = ${readOnly}] [VALID = ${filtered.size == toBeRedirected.size}] TO BE INVALIDATED: ${toBeRedirected.length} uris. \n" + (toBeRedirected.map(_.url) zip newUrls).mkString("\n"))
  }
}


case class DisplayedDuplicate(id: Id[DuplicateDocument], normUriId: Id[NormalizedURI], url: String, percentMatch: Double)
case class DisplayedDuplicates(normUriId: Id[NormalizedURI], url: String, dupes: Seq[DisplayedDuplicate])
