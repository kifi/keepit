package com.keepit.controllers.admin

import com.keepit.classify.{ NormalizedHostname, SensitivityUpdater, DomainToTagRepo, DomainTagRepo, DomainTagStates, DomainTag, DomainRepo, Domain }
import com.keepit.commanders.OrganizationDomainOwnershipCommander
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.article.content.ArticleContentExtractor
import com.keepit.rover.model.{ ArticleVersion }
import org.joda.time.DateTime
import scala.concurrent.duration._
import views.html
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.google.inject.Inject
import com.keepit.integrity._
import com.keepit.normalizer._
import com.keepit.common.healthcheck.{ SystemAdminMailSender, BabysitterTimeout, AirbrakeNotifier }
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsBoolean, JsObject, Json }

import scala.util.Try

class UrlController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    uriRepo: NormalizedURIRepo,
    changedUriRepo: ChangedURIRepo,
    domainRepo: DomainRepo,
    domainTagRepo: DomainTagRepo,
    domainToTagRepo: DomainToTagRepo,
    sensitivityUpdater: SensitivityUpdater,
    orphanCleaner: OrphanCleaner,
    uriIntegrityPlugin: UriIntegrityPlugin,
    normalizationService: NormalizationService,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    monitoredAwait: MonitoredAwait,
    normalizedURIInterner: NormalizedURIInterner,
    airbrake: AirbrakeNotifier,
    uriIntegrityHelpers: UriIntegrityHelpers,
    roverServiceClient: RoverServiceClient,
    orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo) extends AdminUserActions {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminUserPage { implicit request =>
    Ok(html.admin.adminDashboard())
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

  def setFixDuplicateKeepsSeq(seq: Long) = AdminUserPage { request =>
    uriIntegrityPlugin.setFixDuplicateKeepsSeq(seq)
    Ok(s"Ok. The sequence number is set to $seq")
  }

  def clearRedirects(toUriId: Id[NormalizedURI]) = AdminUserPage { request =>
    uriIntegrityPlugin.clearRedirects(toUriId)
    Ok(s"Ok. Redirections of all NormalizedURIs that were redirected to $toUriId is cleared. You should initiate renormalization.")
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
            normalizationService.update(reference, Set(candidate)).map {
              case Some(newUriId) => Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} will be redirected to $newUriId")
              case None => Redirect(routes.UrlController.normalizationView(0)).flashing("result" -> s"${oldUri.id.get}: ${oldUri.url} could not be redirected to $candidateUrl")
            }
        }
    }
  }

  def getPatterns = AdminUserPage { implicit request =>
    val patterns = db.readOnlyReplica { implicit session =>
      urlPatternRuleRepo.all.sortBy(_.id.get.id)
    }
    Ok(html.admin.urlPatternRules(patterns))
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
    Redirect(routes.UrlController.getURIInfo(uriId))
  }

  def flagAsAdult(uriId: Id[NormalizedURI]) = AdminUserPage { implicit request =>
    db.readWrite { implicit session => uriRepo.updateURIRestriction(uriId, Some(Restriction.ADULT)) }
    Redirect(routes.UrlController.getURIInfo(uriId))
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
      Ok(Json.toJson(targetArticle))
    }
  }

  def getBestArticle(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article]) = AdminUserPage.async { implicit request =>

    val fBestArticleWithId = roverServiceClient.getBestArticlesByUris((Set(uriId)))
    fBestArticleWithId.map { bestArticleWithId: Map[Id[NormalizedURI], Set[Article]] =>
      val bestArticles = bestArticleWithId.getOrElse(uriId, Set.empty)
      val targetArticle = bestArticles.filter(article => article.kind == kind).head
      Ok(Json.toJson(targetArticle))
    }
  }

  def fetchAsap(uriId: Id[NormalizedURI]) = AdminUserPage.async { implicit request =>
    val uri = db.readOnlyMaster { implicit session => uriRepo.get(uriId) }
    roverServiceClient.fetchAsap(uriId, uri.url, refresh = true).map { _ =>
      Ok("We got you =)")
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
              val updatedUri = uri.withContentRequest(true).copy(restriction = None)
              val savedUri = if (updatedUri != uri) uriRepo.save(updatedUri) else uri
              log.info(s"[CleaningKeeps] Removed restriction | requested content for shortened url: $savedUri")
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

  def searchDomain = AdminUserPage { implicit request =>
    Ok(html.admin.domainFind())
  }

  def getDomain(domainName: String) = AdminUserPage { implicit request =>
    NormalizedHostname.fromHostname(domainName) match {
      case None => BadRequest("invalid_domain_name")
      case Some(hostname) => {
        val (domain, owningOrgs) = db.readOnlyReplica { implicit s =>
          (domainRepo.get(hostname), orgDomainOwnershipRepo.getOwnershipsForDomain(hostname).map(_.organizationId))
        }
        domain match {
          case None => NotFound("domain_not_found")
          case Some(foundDomain) => Ok(html.admin.domain(foundDomain, owningOrgs))
        }
      }
    }
  }

  def domainToggleEmailProvider(id: Id[Domain]) = AdminUserPage { implicit request =>
    val domain = db.readWrite { implicit s =>
      val domain = domainRepo.get(id)
      val domainToggled = domain.copy(isEmailProvider = !domain.isEmailProvider) //domain
      domainRepo.save(domainToggled)
    }
    Redirect(routes.UrlController.getDomain(domain.hostname.value))
  }

  def getDomainTags = AdminUserPage { implicit request =>
    val tags = db.readOnlyReplica { implicit session =>
      domainTagRepo.all
    }
    Ok(html.admin.domainTags(tags))
  }

  def saveDomainTags = AdminUserPage { implicit request =>
    val tagIdValue = """sensitive_([0-9]+)""".r
    val sensitiveTags = request.body.asFormUrlEncoded.get.keys
      .collect { case tagIdValue(v) => Id[DomainTag](v.toInt) }.toSet
    val tagsToSave = db.readOnlyReplica { implicit s =>
      domainTagRepo.all.map(tag => (tag, sensitiveTags contains tag.id.get)).collect {
        case (tag, sensitive) if tag.state == DomainTagStates.ACTIVE && tag.sensitive != Some(sensitive) =>
          tag.withSensitive(Some(sensitive))
      }
    }
    tagsToSave.foreach { tag =>
      db.readWrite { implicit s =>
        domainTagRepo.save(tag)
      }
      Future {
        val domainIds = db.readOnlyMaster { implicit s =>
          domainToTagRepo.getByTag(tag.id.get).map(_.domainId)
        }
        db.readWrite { implicit s =>
          sensitivityUpdater.clearDomainSensitivity(domainIds)
        }
      }
    }
    Redirect(routes.UrlController.getDomainTags)
  }

  def getDomainOverrides = AdminUserPage { implicit request =>
    val domains = db.readOnlyReplica { implicit session =>
      domainRepo.getOverrides()
    }
    Ok(html.admin.domainOverrides(domains))
  }

  def saveDomainOverrides = AdminUserAction { implicit request =>
    val domainSensitiveMap = request.body.asFormUrlEncoded.get.map {
      case (k, vs) => NormalizedHostname.fromHostname(k) -> (vs.head.toLowerCase == "true")
    }.collect { case (Some(hostname), value) => hostname -> value }
    val domainsToRemove = db.readOnlyReplica { implicit session =>
      domainRepo.getOverrides()
    }.filterNot(d => domainSensitiveMap.contains(d.hostname))

    db.readWrite { implicit s =>
      domainSensitiveMap.foreach {
        case (domainName, sensitive) =>
          val domain = domainRepo.get(domainName)
            .getOrElse(Domain(hostname = domainName))
            .withManualSensitive(Some(sensitive))
          domainRepo.save(domain)
      }
      domainsToRemove.foreach { domain =>
        domainRepo.save(domain.withManualSensitive(None))
      }
    }
    Ok(JsObject(domainSensitiveMap map { case (s, b) => s.value -> JsBoolean(b) } toSeq))
  }
}
