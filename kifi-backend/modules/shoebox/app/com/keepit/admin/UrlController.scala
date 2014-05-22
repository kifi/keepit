package com.keepit.controllers.admin


import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.time._
import scala.concurrent.duration._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.integrity._
import com.keepit.normalizer._
import com.keepit.model.DuplicateDocument
import com.keepit.common.healthcheck.{SystemAdminMailSender, BabysitterTimeout, AirbrakeNotifier}
import com.keepit.integrity.HandleDuplicatesAction
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

class UrlController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  systemAdminMailSender: SystemAdminMailSender,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  changedUriRepo: ChangedURIRepo,
  duplicateDocumentRepo: DuplicateDocumentRepo,
  urlRenormalizeCommander: URLRenormalizeCommander,
  orphanCleaner: OrphanCleaner,
  dupeDetect: DuplicateDocumentDetection,
  duplicatesProcessor: DuplicateDocumentsProcessor,
  uriIntegrityPlugin: UriIntegrityPlugin,
  normalizationService: NormalizationService,
  urlPatternRuleRepo: UrlPatternRuleRepo,
  renormRepo: RenormalizedURLRepo,
  centralConfig: CentralConfig,
  httpProxyRepo: HttpProxyRepo,
  monitoredAwait: MonitoredAwait,
  airbrake: AirbrakeNotifier) extends AdminController(actionAuthenticator) {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.adminDashboard())
  }

  def renormalize(readOnly: Boolean = true, clearSeq: Boolean = false, domainRegex: Option[String] = None) = AdminHtmlAction.authenticated { implicit request =>
    Future {
      try {
        urlRenormalizeCommander.doRenormalize(readOnly, clearSeq, domainRegex)
      } catch {
        case ex: Throwable => airbrake.notify(ex)
      }
    }
    Ok("Started!")
  }

  def orphanCleanup(readOnly: Boolean = true) = AdminHtmlAction.authenticated { implicit request =>
    Future {
      db.readWrite { implicit session =>
        orphanCleaner.clean(readOnly)
      }
    }
    Ok
  }
  def orphanCleanupFull(readOnly: Boolean = true) = AdminHtmlAction.authenticated { implicit request =>
    Future {
      db.readWrite { implicit session =>
        orphanCleaner.fullClean(readOnly)
      }
    }
    Ok
  }

  def documentIntegrity(page: Int = 0, size: Int = 50) = AdminHtmlAction.authenticated { implicit request =>
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

  def handleDuplicate = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = body("action").head
    val id = Id[DuplicateDocument](body("id").head.toLong)
    duplicatesProcessor.handleDuplicates(Left[Id[DuplicateDocument], Id[NormalizedURI]](id), HandleDuplicatesAction(action))
    Ok
  }

  def handleDuplicates = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = body("action").head
    val id = Id[NormalizedURI](body("id").head.toLong)
    duplicatesProcessor.handleDuplicates(Right[Id[DuplicateDocument], Id[NormalizedURI]](id), HandleDuplicatesAction(action))
    Ok
  }

  def duplicateDocumentDetection = AdminHtmlAction.authenticated { implicit request =>
    dupeDetect.asyncProcessDocuments()
    Redirect(routes.UrlController.documentIntegrity())
  }

  def normalizationView(page: Int = 0) = AdminHtmlAction.authenticated { request =>
    implicit val playRequest = request.request
    val PAGE_SIZE = 50
    val (pendingCount, appliedCount, applied) = db.readOnly{ implicit s =>
      val activeCount = changedUriRepo.countState(ChangedURIStates.ACTIVE)
      val appliedCount = changedUriRepo.countState(ChangedURIStates.APPLIED)
      val applied = changedUriRepo.page(page, PAGE_SIZE).map{ change =>
        (uriRepo.get(change.oldUriId), uriRepo.get(change.newUriId), change.updatedAt.date.toString())
      }
      (activeCount, appliedCount, applied)
    }
    val pageCount = (appliedCount*1.0 / PAGE_SIZE).ceil.toInt
    Ok(html.admin.normalization(applied, page, appliedCount, pendingCount, pageCount, PAGE_SIZE))
  }

  def batchURIMigration = AdminHtmlAction.authenticated { request =>
    implicit val playRequest = request.request
    monitoredAwait.result(uriIntegrityPlugin.batchURIMigration(), 1 minute, "Manual merge failed.")
    Redirect(com.keepit.controllers.admin.routes.UrlController.normalizationView(0))
  }

  def batchURLMigration = AdminHtmlAction.authenticated { request =>
    uriIntegrityPlugin.batchURLMigration(500)
    Ok("Ok. Start migration of upto 500 urls")
  }

  def setFixDuplicateKeepsSeq(seq: Long) = AdminHtmlAction.authenticated { request =>
    uriIntegrityPlugin.setFixDuplicateKeepsSeq(seq)
    Ok(s"Ok. The sequence number is set to $seq")
  }

  def clearRedirects(toUriId: Id[NormalizedURI]) = AdminHtmlAction.authenticated { request =>
    uriIntegrityPlugin.clearRedirects(toUriId)
    Ok(s"Ok. Redirections of all NormalizedURIs that were redirected to $toUriId is cleared. You should initiate renormalization.")
  }

  def renormalizationView(page: Int = 0) = AdminHtmlAction.authenticated { request =>
    val PAGE_SIZE = 200
    val (renorms, totalCount) = db.readOnly{ implicit s => (renormRepo.pageView(page, PAGE_SIZE), renormRepo.activeCount())}
    val pageCount = (totalCount*1.0 / PAGE_SIZE).ceil.toInt
    val info = db.readOnly{ implicit s =>
      renorms.map{ renorm => (
        renorm.state.toString,
        urlRepo.get(renorm.urlId).url,
        uriRepo.get(renorm.oldUriId).url,
        uriRepo.get(renorm.newUriId).url
      )}
    }
    Ok(html.admin.renormalizationView(info, page, totalCount, pageCount, PAGE_SIZE))
  }

  def submitNormalization = AdminHtmlAction.authenticatedAsync { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    val candidateUrl = body("candidateUrl")
    val verified = body.contains("verified")
    val candidateOpt = body.get("candidateNormalization").collect {
      case normalizationStr: String if normalizationStr.nonEmpty => (Normalization(normalizationStr))
    } orElse SchemeNormalizer.findSchemeNormalization(candidateUrl) map {
      case normalization if verified => VerifiedCandidate(candidateUrl, normalization)
      case normalization => ScrapedCandidate(candidateUrl, normalization)
    }

    candidateOpt match {
      case None => Future.successful(Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"A normalization candidate could not be constructed for $candidateUrl."))
      case Some(candidate) => {
        val referenceUrl = body("referenceUrl")
        db.readOnly { implicit session => uriRepo.getByUri(referenceUrl) } match {
          case None => Future.successful(Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${referenceUrl} could not be found."))
          case Some(oldUri) => {
            val correctedNormalization = body.get("correctNormalization").flatMap {
              case "reset" => SchemeNormalizer.findSchemeNormalization(referenceUrl)
              case normalization if normalization.nonEmpty => Some(Normalization(normalization))
              case _ => None
            }

            val reference = NormalizationReference(oldUri, isNew = false, correctedNormalization = correctedNormalization)
            normalizationService.update(reference, candidate).map {
              case Some(newUriId) => Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} will be redirected to ${newUriId}")
              case None => Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} could not be redirected to $candidateUrl")
            }
          }
        }
      }
    }
  }

  def getPatterns = AdminHtmlAction.authenticated { implicit request =>
    val (patterns, proxies) = db.readOnly { implicit session =>
      (urlPatternRuleRepo.all.sortBy(_.id.get.id), httpProxyRepo.all())
    }
    Ok(html.admin.urlPatternRules(patterns, proxies))
  }

  def savePatterns = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    db.readWrite { implicit session =>
      for (key <- body.keys.filter(_.startsWith("pattern_")).map(_.substring(8))) {
        val id = Id[UrlPatternRule](key.toLong)
        val oldPat = urlPatternRuleRepo.get(id)
        val newPat = oldPat.copy(
          pattern = body("pattern_" + key),
          example = Some(body("example_" + key)).filter(!_.isEmpty),
          state = if (body.contains("active_" + key)) UrlPatternRuleStates.ACTIVE else UrlPatternRuleStates.INACTIVE,
          isUnscrapable = body.contains("unscrapable_"+ key),
          useProxy = body("proxy_" + key) match {
            case "None" => None
            case proxy_id => Some(Id[HttpProxy](proxy_id.toLong))
          },
          normalization = body("normalization_" + key) match {
            case "None" => None
            case scheme => Some(Normalization(scheme))
          },
          trustedDomain = Some(body("trusted_domain_" + key)).filter(!_.isEmpty),
          nonSensitive = body.contains("non_sensitive_" + key)
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
          useProxy = body("new_proxy") match {
            case "None" => None
            case proxy_id => Some(Id[HttpProxy](proxy_id.toLong))
          },
          normalization = body("new_normalization") match {
            case "None" => None
            case scheme => Some(Normalization(scheme))
          },
          trustedDomain = Some(body("new_trusted_domain")).filter(!_.isEmpty),
          nonSensitive = body.contains("new_nonsensitive")
        ))
      }
    }
    Redirect(routes.UrlController.getPatterns)
  }

  def fixRedirectedUriStates(doIt: Boolean = false) = AdminHtmlAction.authenticated { implicit request =>
    val problematicUris = db.readOnly { implicit session => uriRepo.toBeRemigrated() }
    if (doIt) db.readWrite { implicit session =>
      problematicUris.foreach { uri =>
        changedUriRepo.save(ChangedURI(oldUriId = uri.id.get, newUriId = uri.redirect.get))
      }
    }
    Ok(Json.toJson(problematicUris))
  }

  def clearRestriction(uriId: Id[NormalizedURI]) = AdminHtmlAction.authenticated { implicit request =>
    db.readWrite { implicit session => uriRepo.updateURIRestriction(uriId, None) }
    Redirect(routes.ScraperAdminController.getScraped(uriId))
  }
}

case class DisplayedDuplicate(id: Id[DuplicateDocument], normUriId: Id[NormalizedURI], url: String, percentMatch: Double)
case class DisplayedDuplicates(normUriId: Id[NormalizedURI], url: String, dupes: Seq[DisplayedDuplicate])
