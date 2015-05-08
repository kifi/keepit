package com.keepit.controllers.admin

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.article.content.ArticleContentExtractor
import com.keepit.rover.model.{ ArticleKey, ArticleVersion }
import com.keepit.scraper.ScrapeScheduler
import scala.concurrent.duration._
import views.html
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.google.inject.Inject
import com.keepit.integrity._
import com.keepit.normalizer._
import com.keepit.model.DuplicateDocument
import com.keepit.common.healthcheck.{ SystemAdminMailSender, BabysitterTimeout, AirbrakeNotifier }
import com.keepit.integrity.HandleDuplicatesAction
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.util.{ Failure, Success, Random }

class UrlController @Inject() (
    val userActionsHelper: UserActionsHelper,
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
    normalizedURIInterner: NormalizedURIInterner,
    airbrake: AirbrakeNotifier,
    uriIntegrityHelpers: UriIntegrityHelpers,
    scrapeScheduler: ScrapeScheduler,
    roverServiceClient: RoverServiceClient) extends AdminUserActions {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminUserPage { implicit request =>
    Ok(html.admin.adminDashboard())
  }

  private def asyncSafelyRenormalize(readOnly: Boolean, clearSeq: Boolean, regex: DomainOrURLRegex) = {
    Future {
      try {
        urlRenormalizeCommander.doRenormalize(readOnly, clearSeq, regex = regex)
      } catch {
        case ex: Throwable => airbrake.notify(ex)
      }
    }
  }

  // old secret admin endpoint
  def renormalize(readOnly: Boolean = true, clearSeq: Boolean = false, domainRegex: Option[String] = None) = AdminUserPage { implicit request =>
    asyncSafelyRenormalize(readOnly, clearSeq, regex = DomainOrURLRegex(domainRegex = domainRegex))
    Ok("Started!")
  }

  def orphanCleanup(readOnly: Boolean = true) = AdminUserPage { implicit request =>
    Future {
      db.readWrite { implicit session =>
        orphanCleaner.clean(readOnly)
      }
    }
    Ok
  }
  def orphanCleanupFull(readOnly: Boolean = true) = AdminUserPage { implicit request =>
    Future {
      db.readWrite { implicit session =>
        orphanCleaner.fullClean(readOnly)
      }
    }
    Ok
  }

  def documentIntegrity(page: Int = 0, size: Int = 50) = AdminUserPage { implicit request =>
    val dupes = db.readOnlyReplica { implicit conn =>
      duplicateDocumentRepo.getActive(page, size)
    }

    val groupedDupes = dupes.groupBy { case d => d.uri1Id }.toSeq.sortWith((a, b) => a._1.id < b._1.id)

    val loadedDupes = db.readOnlyReplica { implicit session =>
      groupedDupes map { d =>
        val dupeRecords = d._2.map { sd =>
          DisplayedDuplicate(sd.id.get, sd.uri2Id, uriRepo.get(sd.uri2Id).url, sd.percentMatch)
        }
        DisplayedDuplicates(d._1, uriRepo.get(d._1).url, dupeRecords)
      }
    }

    Ok(html.admin.documentIntegrity(loadedDupes))
  }

  def handleDuplicate = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = body("action").head
    val id = Id[DuplicateDocument](body("id").head.toLong)
    duplicatesProcessor.handleDuplicates(Left[Id[DuplicateDocument], Id[NormalizedURI]](id), HandleDuplicatesAction(action))
    Ok
  }

  def handleDuplicates = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = body("action").head
    val id = Id[NormalizedURI](body("id").head.toLong)
    duplicatesProcessor.handleDuplicates(Right[Id[DuplicateDocument], Id[NormalizedURI]](id), HandleDuplicatesAction(action))
    Ok
  }

  def duplicateDocumentDetection = AdminUserPage { implicit request =>
    dupeDetect.asyncProcessDocuments()
    Redirect(routes.UrlController.documentIntegrity())
  }

  def normalizationView(page: Int = 0) = AdminUserPage { implicit request =>
    implicit val playRequest = request.request
    val PAGE_SIZE = 50
    val (pendingCount, appliedCount, applied) = db.readOnlyReplica { implicit s =>
      val activeCount = changedUriRepo.countState(ChangedURIStates.ACTIVE)
      val appliedCount = changedUriRepo.countState(ChangedURIStates.APPLIED)
      val applied = changedUriRepo.page(page, PAGE_SIZE).map { change =>
        (uriRepo.get(change.oldUriId), uriRepo.get(change.newUriId), change.updatedAt.date.toString())
      }
      (activeCount, appliedCount, applied)
    }
    val pageCount = (appliedCount * 1.0 / PAGE_SIZE).ceil.toInt
    Ok(html.admin.normalization(applied, page, appliedCount, pendingCount, pageCount, PAGE_SIZE))
  }

  def batchURIMigration = AdminUserPage { request =>
    implicit val playRequest = request.request
    monitoredAwait.result(uriIntegrityPlugin.batchURIMigration(), 1 minute, "Manual merge failed.")
    Redirect(com.keepit.controllers.admin.routes.UrlController.normalizationView(0))
  }

  def batchURLMigration = AdminUserPage { request =>
    uriIntegrityPlugin.batchURLMigration(500)
    Ok("Ok. Start migration of upto 500 urls")
  }

  def setFixDuplicateKeepsSeq(seq: Long) = AdminUserPage { request =>
    uriIntegrityPlugin.setFixDuplicateKeepsSeq(seq)
    Ok(s"Ok. The sequence number is set to $seq")
  }

  def clearRedirects(toUriId: Id[NormalizedURI]) = AdminUserPage { request =>
    uriIntegrityPlugin.clearRedirects(toUriId)
    Ok(s"Ok. Redirections of all NormalizedURIs that were redirected to $toUriId is cleared. You should initiate renormalization.")
  }

  def urlRenormalizeConsole() = AdminUserPage { implicit request =>
    Ok(html.admin.urlRenormalization())
  }

  def urlRenormalizeConsoleSubmit() = AdminUserPage { request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val regexStr = body.get("regex").get
    val urlSelection = body.get("urlSelection").get
    val mode = body.get("mode").get

    val regex = if (urlSelection == "domain") DomainOrURLRegex(domainRegex = Some(regexStr)) else DomainOrURLRegex(urlRegex = Some(regexStr))

    if (mode == "preview") {

      val urls = db.readOnlyReplica { implicit s =>
        if (regex.isDomainRegex) urlRepo.getByDomainRegex(regex.domainRegex.get)
        else if (regex.isUrlRegex) urlRepo.getByURLRegex(regex.urlRegex.get)
        else Seq()
      }

      val msg = urls.map { _.url }.mkString("\n")
      Ok(msg.replaceAll("\n", "\n<br>"))

    } else {

      val readOnly = if (mode == "readWrite") false else true
      asyncSafelyRenormalize(readOnly, clearSeq = false, regex = regex)

      Ok(s"Started! readOnlyMode = $readOnly")
    }

  }

  def renormalizationView(page: Int = 0) = AdminUserPage { implicit request =>
    val PAGE_SIZE = 200
    val (renorms, totalCount) = db.readOnlyReplica { implicit s => (renormRepo.pageView(page, PAGE_SIZE), renormRepo.activeCount()) }
    val pageCount = (totalCount * 1.0 / PAGE_SIZE).ceil.toInt
    val info = db.readOnlyMaster { implicit s =>
      renorms.map { renorm =>
        (
          renorm.state.toString,
          urlRepo.get(renorm.urlId).url,
          uriRepo.get(renorm.oldUriId).url,
          uriRepo.get(renorm.newUriId).url
        )
      }
    }
    Ok(html.admin.renormalizationView(info, page, totalCount, pageCount, PAGE_SIZE))
  }

  def submitNormalization = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    val candidateUrl = body("candidateUrl")
    val verified = body.contains("verified")
    val candidateOpt = body.get("candidateNormalization").collect {
      case normalizationStr: String if normalizationStr.nonEmpty => Normalization(normalizationStr)
    } orElse SchemeNormalizer.findSchemeNormalization(candidateUrl) map {
      case normalization if verified => VerifiedCandidate(candidateUrl, normalization)
      case normalization => ScrapedCandidate(candidateUrl, normalization)
    }

    candidateOpt match {
      case None => Future.successful(Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"A normalization candidate could not be constructed for $candidateUrl."))
      case Some(candidate) =>
        val referenceUrl = body("referenceUrl")
        db.readOnlyMaster { implicit session => normalizedURIInterner.getByUri(referenceUrl) } match {
          case None => Future.successful(Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"$referenceUrl could not be found."))
          case Some(oldUri) =>
            val correctedNormalization = body.get("correctNormalization").flatMap {
              case "reset" => SchemeNormalizer.findSchemeNormalization(referenceUrl)
              case normalization if normalization.nonEmpty => Some(Normalization(normalization))
              case _ => None
            }

            val reference = NormalizationReference(oldUri, isNew = false, correctedNormalization = correctedNormalization)
            normalizationService.update(reference, candidate).map {
              case Some(newUriId) => Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} will be redirected to $newUriId")
              case None => Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} could not be redirected to $candidateUrl")
            }
        }
    }
  }

  def getPatterns = AdminUserPage { implicit request =>
    val (patterns, proxies) = db.readOnlyReplica { implicit session =>
      (urlPatternRuleRepo.all.sortBy(_.id.get.id), httpProxyRepo.all())
    }
    Ok(html.admin.urlPatternRules(patterns, proxies))
  }

  def savePatterns = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    db.readWrite { implicit session =>
      for (key <- body.keys.filter(_.startsWith("pattern_")).map(_.substring(8))) {
        val id = Id[UrlPatternRule](key.toLong)
        val oldPat = urlPatternRuleRepo.get(id)
        val newPat = oldPat.copy(
          pattern = body("pattern_" + key),
          example = Some(body("example_" + key)).filter(!_.isEmpty),
          state = if (body.contains("active_" + key)) UrlPatternRuleStates.ACTIVE else UrlPatternRuleStates.INACTIVE,
          isUnscrapable = body.contains("unscrapable_" + key),
          useProxy = body("proxy_" + key) match {
            case "None" => None
            case proxy_id => Some(Id[HttpProxy](proxy_id.toLong))
          },
          normalization = body("normalization_" + key) match {
            case "None" => None
            case scheme => Some(Normalization(scheme))
          },
          trustedDomain = Some(body("trusted_domain_" + key)).filter(!_.isEmpty),
          nonSensitive = body("non_sensitive_" + key) match {
            case "None" => None
            case "true" => Some(true)
            case "false" => Some(false)
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
          useProxy = body("new_proxy") match {
            case "None" => None
            case proxy_id => Some(Id[HttpProxy](proxy_id.toLong))
          },
          normalization = body("new_normalization") match {
            case "None" => None
            case scheme => Some(Normalization(scheme))
          },
          trustedDomain = Some(body("new_trusted_domain")).filter(!_.isEmpty),
          nonSensitive = body("new_non_sensitive") match {
            case "None" => None
            case "true" => Some(true)
            case "false" => Some(false)
          }
        ))
      }
    }
    Redirect(routes.UrlController.getPatterns)
  }

  def pornDomainFlag() = AdminUserPage { request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val regex = body.get("regex").get
    val mode = body.get("mode").get
    val uris = db.readOnlyReplica { implicit s => uriRepo.getByRegex("%" + regex.trim + "%") }

    if (regex.trim.length <= 3) {
      Ok("Please check input domain")
    } else {
      if (mode == "preview") {
        val msg = "preview of matched uris: \n" + uris.map { _.url }.mkString("\n")
        Ok(msg.replaceAll("\n", "\n<br>"))
      } else {
        uris.grouped(100).foreach { gp =>
          db.readWrite { implicit s =>
            gp.foreach {
              uri => if (uri.restriction.isEmpty) uriRepo.save(uri.copy(restriction = Some(Restriction.ADULT)))
            }
          }
        }
        val msg = s"${uris.size} uris processed"
        Ok(msg)
      }
    }
  }

  def fixRedirectedUriStates(doIt: Boolean = false) = AdminUserPage { implicit request =>
    val problematicUris = db.readOnlyMaster { implicit session => uriRepo.toBeRemigrated() }
    if (doIt) db.readWrite { implicit session =>
      problematicUris.foreach { uri =>
        changedUriRepo.save(ChangedURI(oldUriId = uri.id.get, newUriId = uri.redirect.get))
      }
    }
    Ok(Json.toJson(problematicUris))
  }

  def clearRestriction(uriId: Id[NormalizedURI]) = AdminUserPage { implicit request =>
    db.readWrite { implicit session => uriRepo.updateURIRestriction(uriId, None) }
    Redirect(routes.ScraperAdminController.getScraped(uriId))
  }

  def flagAsAdult(uriId: Id[NormalizedURI]) = AdminUserPage { implicit request =>
    db.readWrite { implicit session => uriRepo.updateURIRestriction(uriId, Some(Restriction.ADULT)) }
    Redirect(routes.ScraperAdminController.getScraped(uriId))
  }

  def getURIInfo(id: Id[NormalizedURI]) = AdminUserPage.async { implicit request =>
    val fArticleInfoWithUri = roverServiceClient.getArticleInfosByUris(Set(id))
    val fBestArticlesWithUri = roverServiceClient.getBestArticlesByUris(Set(id))
    val uri: NormalizedURI = db.readOnlyReplica { implicit s => uriRepo.get(id) }

    fArticleInfoWithUri.flatMap { articleInfoWithUri =>
      fBestArticlesWithUri.map { bestArticlesWithUri =>
        val bestArticles = bestArticlesWithUri.getOrElse(id, Set.empty)
        val aggregateContent: ArticleContentExtractor = ArticleContentExtractor(bestArticles)
        Ok(html.admin.uri(uri, articleInfoWithUri.getOrElse(id, Set.empty), aggregateContent))
      }
    }
  }

  def getArticle(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article], version: ArticleVersion) = AdminUserPage.async { implicit request =>
    // TODO: Cam: expand functionality for any version, currently returns just the best

    val fBestArticleWithId = roverServiceClient.getBestArticlesByUris((Set(uriId)))
    fBestArticleWithId.map { bestArticleWithId: Map[Id[NormalizedURI], Set[Article]] =>
      val bestArticles = bestArticleWithId.getOrElse(uriId, Set.empty)
      val targetArticle = bestArticles.filter(article => article.kind == kind).head
      Ok(Json.obj(("content" -> targetArticle.content.content.getOrElse("").toString)))
    }
  }

  def getArticleByKind(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article]) = AdminUserPage.async { implicit request =>
    val fBestArticleWithId = roverServiceClient.getBestArticlesByUris((Set(uriId)))
    fBestArticleWithId.map { bestArticleWithId: Map[Id[NormalizedURI], Set[Article]] =>
      val bestArticles = bestArticleWithId.getOrElse(uriId, Set.empty)
      val targetArticle = bestArticles.filter(article => article.kind == kind).head
      Ok(Json.obj("content" -> targetArticle.content.content.getOrElse("").toString))
    }
  }

  def cleanKeepsByUri(firstPage: Int, pageSize: Int) = AdminUserAction { implicit request =>
    SafeFuture {
      var page = firstPage
      var done = false
      val excludeStates = Set(NormalizedURIStates.REDIRECTED, NormalizedURIStates.INACTIVE)
      while (!done) {
        db.readWrite { implicit session =>
          val uris = uriRepo.page(page, pageSize, excludeStates)
          uris.foreach { uri =>
            if (HttpRedirect.isShortenedUrl(uri.url)) {
              uriRepo.updateURIRestriction(uri.id.get, None)
              val updatedUri = uriRepo.get(uri.id.get)
              scrapeScheduler.scheduleScrape(updatedUri, currentDateTime.plusMinutes((Random.nextInt(10))))
              log.info(s"[CleaningKeeps] Removed restriction and scheduled scrape for shortened url: $uri")
            } else {
              uriIntegrityHelpers.improveKeepsSafely(uri)
            }
          }
          done = uris.isEmpty
          page = page + 1
        }
        log.info(s"[CleaningKeeps] Successfully processed page $page")
      }
      log.info("[CleaningKeeps] All Done!")
    }
    Ok(s"Starting at page $firstPage of size $pageSize, it's on!")
  }
}

case class DisplayedDuplicate(id: Id[DuplicateDocument], normUriId: Id[NormalizedURI], url: String, percentMatch: Double)
case class DisplayedDuplicates(normUriId: Id[NormalizedURI], url: String, dupes: Seq[DisplayedDuplicate])
