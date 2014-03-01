package com.keepit.controllers.admin


import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.mail._
import scala.concurrent.duration._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.integrity.OrphanCleaner
import com.keepit.integrity.DuplicateDocumentDetection
import com.keepit.integrity.DuplicateDocumentsProcessor
import com.keepit.integrity.UriIntegrityPlugin
import com.keepit.normalizer._
import com.keepit.model.DuplicateDocument
import com.keepit.common.healthcheck.{SystemAdminMailSender, BabysitterTimeout, AirbrakeNotifier}
import com.keepit.integrity.HandleDuplicatesAction
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.integrity.RenormalizationCheckKey
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import scala.collection.mutable

class UrlController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  systemAdminMailSender: SystemAdminMailSender,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  changedUriRepo: ChangedURIRepo,
  duplicateDocumentRepo: DuplicateDocumentRepo,
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
        doRenormalize(readOnly, clearSeq, domainRegex)
      } catch {
        case ex: Throwable => airbrake.notify(ex)
      }
    }
    Ok("Started!")
  }

  def doRenormalize(readOnly: Boolean = true, clearSeq: Boolean = false, domainRegex: Option[String] = None) = {

    def getUrlList() = {
      val urls = db.readOnly { implicit s =>
        domainRegex match {
          case Some(regex) => urlRepo.getByDomainRegex(regex)
          case None => urlRepo.all
        }
      }.sortBy(_.id.get.id)

      val lastId = if (domainRegex.isDefined) 0L else { centralConfig(RenormalizationCheckKey) getOrElse 0L }
      urls.filter(_.id.get.id > lastId).filter(_.state == URLStates.ACTIVE)
    }

    def needRenormalization(url: URL)(implicit session: RWSession): (Boolean, Option[NormalizedURI]) = {
      uriRepo.getByUri(url.url) match {
        case None => if (!readOnly) (true, Some(uriRepo.internByUri(url.url))) else (true, None)
        case Some(uri) if (url.normalizedUriId != uri.id.get) => (true, Some(uri))
        case _ => (false, None)
      }
    }

    def batch[T](obs: Seq[T], batchSize: Int): Seq[Seq[T]] = {
      val total = obs.size
      val index = ((0 to total/batchSize).map(_*batchSize) :+ total).distinct
      (0 to (index.size - 2)).map{ i => obs.slice(index(i), index(i+1)) }
    }

    def sendStartEmail(urls: Seq[URL]) = {
      val title = "Renormalization Begins"
      val msg = s"domainRegex = ${domainRegex}, scanning ${urls.size} urls. readOnly = ${readOnly}"
      systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
        subject = title, htmlBody = msg, category = NotificationCategory.System.ADMIN))
    }

    def sendEmail(changes: Vector[(URL, Option[NormalizedURI])], readOnly: Boolean)(implicit session: RWSession) = {
      val batchChanges = batch[(URL, Option[NormalizedURI])](changes, batchSize = 1000)
      batchChanges.zipWithIndex.map{ case (batch, i) =>
        val title = "Renormalization Report: " + s"part ${i+1} of ${batchChanges.size}. ReadOnly Mode = $readOnly. Num of affected URL: ${changes.size}"
        val msg = batch.map( x => x._1.url + s"\ncurrent uri: ${ uriRepo.get(x._1.normalizedUriId).url }" + "\n--->\n" + x._2.map{_.url}).mkString("\n\n")
        systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
          subject = title, htmlBody = msg.replaceAll("\n","\n<br>"), category = NotificationCategory.System.ADMIN))
      }
    }

    case class SplittedURIs(value: Map[Id[NormalizedURI], Set[Id[URL]]])

    def sendSplitEmail(splits: Map[Id[NormalizedURI], SplittedURIs], readOnly: Boolean)(implicit session: RWSession) = {
      if (splits.size == 0){
        systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
          subject = "Renormalization Split Cases Report: no split cases found", htmlBody = "", category = NotificationCategory.System.ADMIN))
      }

      val batches = splits.toSeq.grouped(500)
      batches.zipWithIndex.map{ case (batch, i) =>
        val title = "Renormalization Split Cases Report: " + s"part ${i+1} of ${batches.size}. ReadOnly Mode = $readOnly. Num of splits: ${splits.size}"
        val msg = batch.map{ case (oldUri, splitted) =>
          val line1 = s"oldUriId: ${oldUri} uri:\n${uriRepo.get(oldUri).url}\n"
          val lines = splitted.value.map{ case (newUri, urls) =>
            val urlsInfo = urls.map{id => urlRepo.get(id)}.map{url => s"urlId: ${url.id.get}, url: ${url.url}"}.mkString("\n\n")
            s"uriId: ${newUri}, ${uriRepo.get(newUri).url} \nis referenced by following urls:\n\n" + urlsInfo
          }
          line1 + lines
        }.mkString("\n\n===========================\n\n")
        systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
          subject = title, htmlBody = msg.replaceAll("\n","\n<br>"), category = NotificationCategory.System.ADMIN))
      }
    }


    // main code
    if (clearSeq) centralConfig.update(RenormalizationCheckKey, 0L)
    var changes = Vector.empty[(URL, Option[NormalizedURI])]
    val urls = getUrlList()
    sendStartEmail(urls)
    val batchUrls = batch[URL](urls, batchSize = 50)     // avoid long DB write lock.

    val originalRef = mutable.Map.empty[Id[NormalizedURI], Set[Id[URL]]]      // all active urls pointing to a uri initially
    db.readOnly{ implicit s =>
      urls.map{_.normalizedUriId}.toSet.foreach{ uriId: Id[NormalizedURI] =>
        val urls = urlRepo.getByNormUri(uriId).filter(_.state == URLStates.ACTIVE)
        originalRef += uriId -> urls.map{_.id.get}.toSet
      }
    }
    val migratedRef = mutable.Map.empty[Id[NormalizedURI], Set[Id[URL]]]      // urls pointing to new uri due to migration
    val potentialUriMigrations = mutable.Map.empty[Id[NormalizedURI], Set[Id[NormalizedURI]]]       // oldUri -> set of new uris. set size > 1 is a sufficient (but not necessary) condition for a "uri split"

    batchUrls.map { urls =>
      log.info(s"begin a new batch of renormalization. lastId from zookeeper: ${centralConfig(RenormalizationCheckKey)}")
      db.readWrite { implicit s =>
        urls.foreach { url =>
          needRenormalization(url) match {
            case (true, newUriOpt) => {
              changes = changes :+ (url, newUriOpt)
              newUriOpt.foreach{ uri =>
                potentialUriMigrations += url.normalizedUriId -> (potentialUriMigrations.getOrElse(url.normalizedUriId, Set()) + uri.id.get)
                migratedRef += uri.id.get -> (migratedRef.getOrElse(uri.id.get, Set()) + url.id.get)
              }
              if (!readOnly) newUriOpt.map { uri => renormRepo.save(RenormalizedURL(urlId = url.id.get, oldUriId = url.normalizedUriId, newUriId = uri.id.get))}
            }
            case _ =>
          }
        }
      }
      if (domainRegex.isEmpty && !readOnly) urls.lastOption.map{ url => centralConfig.update(RenormalizationCheckKey, url.id.get.id)}     // We assume id's are already sorted ( in getUrlList() )
    }

    db.readWrite{ implicit s =>
      val splitCases = potentialUriMigrations.map{ case (oldUri, newUris) =>
        val allInitUrls = originalRef(oldUri)
        val untouched = newUris.foldLeft(allInitUrls){case (all, newUri) => all -- migratedRef(newUri)}
        if (newUris.size == 1 && untouched.size == 0) {
          // we essentially have a uri migration here. Report a *APPLIED* uri migration event, so that other services like Eliza can sync.
          if (!readOnly) changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUris.head, state = ChangedURIStates.APPLIED))
          None
        } else {
          val splitted = newUris.foldLeft(Map(oldUri -> untouched)){case (m, newUri) => m + (newUri -> migratedRef(newUri))}
          Some(oldUri -> SplittedURIs(splitted))
        }
      }.flatten.toMap
      sendSplitEmail(splitCases, readOnly)
    }

    changes = changes.sortBy(_._1.url)
    db.readWrite{ implicit s =>
      sendEmail(changes, readOnly)
    }
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
    val candidateOpt = body.get("candidateNormalization").collect {
      case normalizationStr: String if normalizationStr.nonEmpty => (Normalization(normalizationStr))
    } orElse SchemeNormalizer.findSchemeNormalization(candidateUrl) map {
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
          trustedDomain = Some(body("trusted_domain_" + key)).filter(!_.isEmpty)
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
          trustedDomain = Some(body("new_trusted_domain")).filter(!_.isEmpty)
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
}

case class DisplayedDuplicate(id: Id[DuplicateDocument], normUriId: Id[NormalizedURI], url: String, percentMatch: Double)
case class DisplayedDuplicates(normUriId: Id[NormalizedURI], url: String, dupes: Seq[DisplayedDuplicate])
