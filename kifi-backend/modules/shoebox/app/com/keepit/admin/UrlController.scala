package com.keepit.controllers.admin


import play.api.Play.current
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.mail._
import play.api.libs.concurrent.Akka
import scala.concurrent.duration._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.integrity.OrphanCleaner
import com.keepit.integrity.DuplicateDocumentDetection
import com.keepit.integrity.DuplicateDocumentsProcessor
import com.keepit.integrity.UriIntegrityPlugin
import com.keepit.normalizer.NormalizationService
import scala.concurrent.Await
import play.api.data.Form
import play.api.data.Forms._
import com.keepit.model.DuplicateDocument
import com.keepit.integrity.URLMigration
import com.keepit.common.healthcheck.BabysitterTimeout
import com.keepit.normalizer.TrustedCandidate
import com.keepit.integrity.MergedUri
import com.keepit.integrity.HandleDuplicatesAction
import play.api.mvc.Action
import play.api.data.format.Formats._
import play.api.libs.json.Json
import com.keepit.eliza.ElizaServiceClient
import com.keepit.common.db.slick.DBSession.RWSession


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
  normalizationService: NormalizationService,
  urlPatternRuleRepo: UrlPatternRuleRepo,
  renormRepo: RenormalizedURLRepo,
  eliza: ElizaServiceClient) extends AdminController(actionAuthenticator) {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminHtmlAction { implicit request =>
    Ok(html.admin.adminDashboard())
  }

  def renormalize(readOnly: Boolean = true, domain: Option[String] = None) = AdminHtmlAction { implicit request =>
    Akka.future {
      try {
        doRenormalize(readOnly, domain)
      } catch {
        case ex: Throwable => log.error(ex.getMessage, ex)
      }
    }
    Ok("Started!")
  }

  def doRenormalize(readOnly: Boolean = true, domain: Option[String] = None) = {
    def needRenormalization(url: URL)(implicit session: RWSession): (Boolean, Option[NormalizedURI]) = {
      uriRepo.getByUri(url.url) match {
        case None => if (!readOnly) (true, Some(uriRepo.internByUri(url.url))) else (true, None)
        case Some(uri) if (url.normalizedUriId != uri.id.get) => (true, Some(uri))
        case _ => (false, None)
      }
    }
    var changes = Vector.empty[URL]

    db.readWrite { implicit session =>
      val urls = domain match {
        case Some(domainStr) => urlRepo.getByDomain(domainStr)
        case None => urlRepo.all
      }

      urls.foreach { url =>
        needRenormalization(url) match {
          case (true, newUriOpt) => {
            changes = changes :+ url
            if (!readOnly) newUriOpt.map { uri => renormRepo.save(RenormalizedURL(urlId = url.id.get, newUriId = uri.id.get)) }
          }
          case _ =>
        }
      }

      val msg = s"ReadOnly Mode = ${readOnly}. ${changes.size} url need to be renormalized.\n" + changes.map(_.url).mkString("\n")
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
        subject = "Renormalization Report", htmlBody = msg.replaceAll("\n","\n<br>"), category = PostOffice.Categories.ADMIN))
    }
  }

  def orphanCleanup() = AdminHtmlAction { implicit request =>
    Akka.future {
      db.readWrite { implicit session =>
        orphanCleaner.cleanNormalizedURIs(false)
        orphanCleaner.cleanScrapeInfo(false)
      }
    }
    Redirect(routes.UrlController.documentIntegrity())
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
    Redirect(routes.UrlController.documentIntegrity())
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
    Redirect(routes.UrlController.normalizationView(0))
  }

  def redirect(oldUrl: String, newUrl: String, canonical: Boolean = false) = AdminHtmlAction { request =>
    db.readOnly { implicit session =>
      (uriRepo.getByUri(oldUrl), uriRepo.getByUri(newUrl)) match {
        case (None, _) => Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUrl} could not be found.")
        case (_, None) => Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${newUrl} could not be found.")
        case (_, Some(newUri)) if newUri.normalization.isEmpty && !canonical =>
          Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${newUri.id.get}: ${newUri.url} isn't normalized.")
        case (Some(oldUri), Some(newUri)) => {
          val normalization = if (canonical) Normalization.CANONICAL else newUri.normalization.get
          val result = Await.result(normalizationService.update(oldUri, TrustedCandidate(newUri.url, normalization)), 5 seconds)
          if (result.isDefined)
            Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} will be redirected to ${newUri.id.get}: ${newUri.url}")
          else
            Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} cannot be redirected to ${newUri.id.get}: ${newUri.url}")
        }
      }
    }
  }

  def normSchemePageView(page: Int = 0) = AdminHtmlAction { implicit request =>
    val PAGE_SIZE = 50
    val (rules, totalCount) = db.readOnly{ implicit s =>
      urlPatternRuleRepo.normSchemePage(page, PAGE_SIZE)
    }
    val pageCount = (totalCount * 1.0 / PAGE_SIZE).ceil.toInt
    Ok(html.admin.domainNormalization(rules, page, totalCount, pageCount, PAGE_SIZE, None))
  }

  def searchDomainNormalization() = AdminHtmlAction{ implicit request =>
    val form = request.request.body.asFormUrlEncoded.map{ req => req.map(r => (r._1 -> r._2.head)) }
    val searchTerm = form.flatMap{ _.get("searchTerm") }
    searchTerm match {
      case None => Redirect(routes.UrlController.normSchemePageView(0))
      case Some(term) =>
        val rules = db.readOnly{ implicit s => urlPatternRuleRepo.findAll(term) }
        Ok(html.admin.domainNormalization(rules, 0, rules.size, 1, rules.size, searchTerm))
    }
  }

  val normSchemeForm = Form(
    mapping("scheme"-> of[String])(Normalization.apply)(Normalization.unapply)
  )

  val normSchemeOptions = Normalization.schemes.map{x => (x.scheme, x.scheme)}.toSeq

  def editDomainNormScheme(id: Id[UrlPatternRule]) = Action { implicit request =>
    val rule = db.readOnly{ implicit s => urlPatternRuleRepo.get(id) }
    Ok(html.admin.editDomainNormScheme(rule, normSchemeForm.fill(rule.normalization.getOrElse(Normalization(""))), normSchemeOptions))
  }

  def saveDomainNormScheme(id: Id[UrlPatternRule]) = Action { implicit request =>
    val rule = db.readOnly{ implicit s => urlPatternRuleRepo.get(id) }
    normSchemeForm.bindFromRequest.fold(
      formWithErrors => BadRequest,
      normalization => {
        val modifiedRule = if (normalization.scheme == "") rule.copy(normalization = None)
        else rule.copy(normalization = Some(normalization))
        db.readWrite{implicit s => urlPatternRuleRepo.save(modifiedRule)}
        Redirect(routes.UrlController.normSchemePageView(0))
      }
    )
  }

  def getPatterns = AdminHtmlAction { implicit request =>
    val patterns = db.readOnly { implicit session =>
      urlPatternRuleRepo.all.sortBy(_.id.get.id)
    }
    Ok(html.admin.urlPatternRules(patterns))
  }

  def savePatterns = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    val toBeBroadcasted = db.readWrite { implicit session =>
      for (key <- body.keys.filter(_.startsWith("pattern_")).map(_.substring(8))) {
        val id = Id[UrlPatternRule](key.toLong)
        val oldPat = urlPatternRuleRepo.get(id)
        val newPat = oldPat.copy(
          pattern = body("pattern_" + key),
          example = Some(body("example_" + key)).filter(!_.isEmpty),
          state = if (body.contains("active_" + key)) UrlPatternRuleStates.ACTIVE else UrlPatternRuleStates.INACTIVE,
          isUnscrapable = body.contains("unscrapable_"+ key),
          doNotSlide = body.contains("no-slide_" + key),
          normalization = body("normalization_" + key) match {
            case "None" => None
            case scheme => Some(Normalization(scheme))
          }
        )

        if (newPat != oldPat) {
          urlPatternRuleRepo.save(newPat)
        }
      }
      val newPat = body("new_pattern")
      if (newPat.nonEmpty) {
        urlPatternRuleRepo.save(UrlPatternRule(
          pattern = newPat,
          example = Some(body("new_example")).filter(!_.isEmpty),
          state = if (body.contains("new_active")) UrlPatternRuleStates.ACTIVE else UrlPatternRuleStates.INACTIVE,
          isUnscrapable = body.contains("new_unscrapable"),
          doNotSlide = body.contains("no-slide-slider"),
          normalization = body("new_normalization") match {
            case "None" => None
            case scheme => Some(Normalization(scheme))
          }
        ))
      }
      urlPatternRuleRepo.getSliderNotShown()
    }
    eliza.sendToAllUsers(Json.arr("url_patterns", toBeBroadcasted))
    Redirect(routes.UrlController.getPatterns)
  }
}

case class DisplayedDuplicate(id: Id[DuplicateDocument], normUriId: Id[NormalizedURI], url: String, percentMatch: Double)
case class DisplayedDuplicates(normUriId: Id[NormalizedURI], url: String, dupes: Seq[DisplayedDuplicate])
