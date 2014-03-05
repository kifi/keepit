package com.keepit.controllers.internal

import com.google.inject.{Provider, Inject}
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.LocalPostOffice
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer._
import com.keepit.search.SearchConfigExperiment
import com.keepit.search.SearchConfigExperimentRepo

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.scraper._
import com.keepit.social.{SocialGraphPlugin, BasicUser, SocialNetworkType}

import com.keepit.commanders.{RawKeepImporterPlugin, UserCommander}
import com.keepit.common.db.slick.Database.Slave
import com.keepit.normalizer.VerifiedCandidate
import com.keepit.model.KifiInstallation
import com.keepit.social.SocialId
import play.api.libs.json.JsObject
import scala.util.{Try, Failure, Success}
import com.keepit.common.akka.SafeFuture


class ShoeboxController @Inject() (
  db: Database,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  normUriRepo: NormalizedURIRepo,
  normalizationServiceProvider:Provider[NormalizationService],
  urlPatternRuleRepo: UrlPatternRuleRepo,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  probabilisticExperimentGeneratorRepo: ProbabilisticExperimentGeneratorRepo,
  userExperimentRepo: UserExperimentRepo,
  postOffice: LocalPostOffice,
  airbrake: AirbrakeNotifier,
  phraseRepo: PhraseRepo,
  collectionRepo: CollectionRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  basicUserRepo: BasicUserRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialConnectionRepo: SocialConnectionRepo,
  sessionRepo: UserSessionRepo,
  searchFriendRepo: SearchFriendRepo,
  emailAddressRepo: EmailAddressRepo,
  userBookmarkClicksRepo: UserBookmarkClicksRepo,
  scrapeInfoRepo:ScrapeInfoRepo,
  friendRequestRepo: FriendRequestRepo,
  userValueRepo: UserValueRepo,
  userCommander: UserCommander,
  kifiInstallationRepo: KifiInstallationRepo,
  socialGraphPlugin: SocialGraphPlugin,
  rawKeepImporterPlugin: RawKeepImporterPlugin,
  scraperHelper: ScraperCallbackHelper,
  scrapeScheduler: ScrapeSchedulerPlugin
)
  (implicit private val clock: Clock,
   implicit private val scraperConfig: ScraperConfig,
   private val fortyTwoServices: FortyTwoServices)
  extends ShoeboxServiceController with Logging {

  val MaxContentLength = 6000

  def getUserOpt(id: ExternalId[User]) = Action { request =>
    val userOpt =  db.readOnly { implicit s => userRepo.getOpt(id) } //using cache
    userOpt match {
      case Some(user) => Ok(Json.toJson(user))
      case None => Ok(JsNull)
    }
  }

  def getSocialUserInfoByNetworkAndSocialId(id: String, networkType: String) = Action {
    val socialId = SocialId(id)
    val network = SocialNetworkType(networkType)
    val sui = db.readOnly { implicit session =>
      socialUserInfoRepo.get(socialId, network) //using cache
    }
    Ok(Json.toJson(sui))
  }

  def getSocialUserInfosByUserId(userId: Id[User]) = Action {
    val sui = db.readOnly { implicit session =>
      socialUserInfoRepo.getByUser(userId) //using cache
    }
    Ok(Json.toJson(sui))
  }

  def sendMail = Action(parse.json) { request =>
    Json.fromJson[ElectronicMail](request.body).asOpt match {
      case Some(mail) =>
        db.readWrite(attempts = 3) { implicit session =>
          postOffice.sendMail(mail)
        }
        Ok("true")
      case None =>
        val e = new Exception("Unable to parse email")
        airbrake.notify(s"Unable to parse: ${request.body.toString}", e)
        Ok("false")
    }
  }

  def sendMailToUser = Action(parse.json) { request =>
    val userId = Id[User]((request.body \ "user").as[Long])
    val email = (request.body \ "email").as[ElectronicMail]

    val addrs = db.readOnly(2, Slave) { implicit session => emailAddressRepo.getAllByUser(userId) }
    for (addr <- addrs.find(_.verifiedAt.isDefined).orElse(addrs.headOption)) {
      db.readWrite(attempts = 3){ implicit session => postOffice.sendMail(email.copy(to=List(addr))) }
    }
    Ok("true")
  }

  def getNormalizedURI(id: Id[NormalizedURI]) = SafeAsyncAction {
    val uri = db.readOnly { implicit s =>
      normUriRepo.get(id)//using cache
    }
    Ok(Json.toJson(uri))
  }

  def saveNormalizedURI() = SafeAsyncAction(parse.json(maxLength = MaxContentLength)) { request =>
    val ts = System.currentTimeMillis
    val normalizedUri = request.body.as[NormalizedURI]
    val saved = db.readWrite(attempts = 3) { implicit s =>
      normUriRepo.save(normalizedUri)
    }
    log.info(s"[saveNormalizedURI] time-lapsed:${System.currentTimeMillis - ts} url=(${normalizedUri.url}) result=$saved")
    Ok(Json.toJson(saved))
  }

  def updateNormalizedURI(uriId: Id[NormalizedURI]) = SafeAsyncAction(parse.json) { request =>
     val saveResult = Try(db.readWrite(attempts = 3) { implicit s =>
       // Handle serialization in session to be transactional.
       val originalNormalizedUri = normUriRepo.get(uriId)
       val originalJson = Json.toJson(originalNormalizedUri).as[JsObject]
       val newNormalizedUriResult = Json.fromJson[NormalizedURI](originalJson ++ request.body.as[JsObject])

       newNormalizedUriResult.fold({ invalid =>
         log.error(s"Could not deserialize NormalizedURI ($uriId) update: $invalid\nOriginal: $originalNormalizedUri\nbody: ${request.body}")
         airbrake.notify(s"Could not deserialize NormalizedURI ($uriId) update: $invalid. See logs for more.")
         None
       }, { normalizedUri =>
         Some(normUriRepo.save(normalizedUri))
       }).nonEmpty
    })
    saveResult match {
      case Success(res) =>
        Ok(Json.toJson(res))
      case Failure(ex) =>
        log.error(s"Could not deserialize NormalizedURI ($uriId) update: $ex\nbody: ${request.body}")
        airbrake.notify(s"Could not deserialize NormalizedURI ($uriId) update", ex)
        Ok(Json.toJson(false))
    }

  }

  def scraped() = SafeAsyncAction(parse.json) { request =>
    val ts = System.currentTimeMillis
    val json = request.body
    val uriOpt  = (json \ "uri").asOpt[NormalizedURI]
    val infoOpt = (json \ "info").asOpt[ScrapeInfo]
    val updateBookmark = (json \ "updateBookmark").asOpt[JsBoolean].getOrElse(JsBoolean(false)).value
    log.info(s"[scraped] uri=$uriOpt info=$infoOpt updateBookmark=$updateBookmark")
    if (!(uriOpt.isDefined && infoOpt.isDefined)) BadRequest(s"Illegal arguments: arguments($uriOpt, $infoOpt) cannot be null")
    else {
      val uri = uriOpt.get
      val info = infoOpt.get
      val savedUri = db.readWrite(attempts = 2) { implicit request =>
        val savedUri  = normUriRepo.save(uri)
        val savedInfo = scrapeInfoRepo.save(info)
        if (updateBookmark) {
          bookmarkRepo.getByUriWithoutTitle(savedUri.id.get).foreach { bookmark =>
            bookmarkRepo.save(bookmark.copy(title = savedUri.title))
          }
        }
        log.info(s"[scraped($savedUri,$savedInfo)] time-lapsed=${System.currentTimeMillis - ts}")
        savedUri
      }
      Ok(Json.toJson(savedUri))
    }
  }

  def scrapeFailed() = SafeAsyncAction(parse.json) { request =>
    val ts = System.currentTimeMillis
    val json = request.body
    val uriOpt  = (json \ "uri").asOpt[NormalizedURI]
    val infoOpt = (json \ "info").asOpt[ScrapeInfo]
    log.info(s"[scrapeFailed] uri=$uriOpt info=$infoOpt")
    if (!(uriOpt.isDefined && infoOpt.isDefined)) BadRequest(s"Illegal arguments: arguments($uriOpt, $infoOpt) cannot be null")
    else {
      val (savedUri, savedInfo) = {
        val uri = uriOpt.get
        val info = infoOpt.get
        db.readWrite(attempts = 2) { implicit request =>
          val uri2 = uri.id match {
            case Some(id) => Some(normUriRepo.get(id))
            case None => normUriRepo.getByUri(uri.url)
          }
          val savedUri = uri2 match {
            case None => uri
            case Some(uri2) => {
              if (uri2.state == NormalizedURIStates.INACTIVE) uri2
              else normUriRepo.save(uri2.withState(NormalizedURIStates.SCRAPE_FAILED))
            }
          }
          val savedInfo = scrapeInfoRepo.save(info.withFailure)
          log.info(s"[scrapeFailed(uri(${uri.id}).url=${uri.url},info(${info.id}).state=${info.state})] time-lapsed:${System.currentTimeMillis - ts} updated: savedUri(${savedUri.id}).state=${savedUri.state}; savedInfo(${savedInfo.id}).state=${savedInfo.state}")
          (savedUri, savedInfo)
        }
      }
      Ok(Json.obj("uri" -> savedUri, "info" -> savedInfo))
    }
  }

  // todo: revisit
  def recordPermanentRedirect() = Action.async(parse.json) { request =>
    val ts = System.currentTimeMillis
    log.info(s"[recordPermanentRedirect] body=${request.body}")
    val args = request.body.as[JsArray].value
    require((!args.isEmpty && args.length == 2), "Both uri and redirect need to be supplied")
    val uri = args(0).as[NormalizedURI]
    val redirect = args(1).as[HttpRedirect]
    require(redirect.isPermanent, "HTTP redirect is not permanent.")
    require(redirect.isLocatedAt(uri.url), "Current Location of HTTP redirect does not match normalized Uri.")
    val candidateUri = db.readWrite { implicit session => normUriRepo.internByUri(redirect.newDestination) }
    val resFutureOption = candidateUri.normalization.map { normalization =>
      val toBeRedirected = NormalizationReference(uri, correctedNormalization = Some(Normalization.MOVED))
      val verifiedCandidate = VerifiedCandidate(candidateUri.url, normalization)
      val updateFuture = normalizationServiceProvider.get.update(toBeRedirected, verifiedCandidate)
      // Scraper reports entire NormalizedUri objects with a major chance of stale data / race conditions
      // The following is meant for synchronisation and should be revisited when scraper apis are rewritten to report modified fields only

      updateFuture.map {
        case Some(update) => {
          val redirectedUri = db.readOnly() { implicit session => normUriRepo.get(uri.id.get) }
          log.info(s"[recordedPermanentRedirect($uri, $redirect)] time-lapsed: ${System.currentTimeMillis - ts} result=$redirectedUri")
          redirectedUri
        }
        case None => {
          log.info(s"[failedToRecordPermanentRedirect($uri, $redirect)] Normalization update failed - time-lapsed: ${System.currentTimeMillis - ts} result=$uri")
          uri
        }
      }
    }

    val resFuture = resFutureOption getOrElse {
        log.info(s"[failedToRecordPermanentRedirect($uri, $redirect)] Redirection normalization empty - time-lapsed: ${System.currentTimeMillis - ts} result=$uri")
        Future.successful(uri)
      }

    resFuture.map { res => Ok(Json.toJson(res)) }
  }

  def recordScrapedNormalization() = Action(parse.json) { request =>

    val candidateUrl = (request.body \ "url").as[String]
    val candidateNormalization = (request.body \ "normalization").as[Normalization]
    val scrapedCandidate = ScrapedCandidate(candidateUrl, candidateNormalization)

    val uriId = (request.body \ "id").as[Id[NormalizedURI]](Id.format)
    val signature = Signature((request.body \ "signature").as[String])
    val scrapedUri = db.readOnly { implicit session => normUriRepo.get(uriId) }

    normalizationServiceProvider.get.update(NormalizationReference(scrapedUri, signature = Some(signature)), scrapedCandidate)
    // todo(Léo): What follows is dangerous. Someone could mess up with our data by reporting wrong alternate Urls on its website. We need to do a specific content check.
    scrapedUri.normalization.map(ScrapedCandidate(scrapedUri.url, _)).foreach { scrapedUriCandidate =>
      val alternateUrls = (request.body \ "alternateUrls").asOpt[Set[String]].getOrElse(Set.empty)
      val alternateUris = db.readOnly { implicit session => alternateUrls.flatMap(normUriRepo.getByUri(_)) }
      alternateUris.foreach(uri => normalizationServiceProvider.get.update(NormalizationReference(uri), scrapedUriCandidate))
    }
    Ok
  }

  def getProxy(url:String) = SafeAsyncAction { request =>
    val httpProxyOpt = db.readOnly(2, Slave) { implicit session =>
      urlPatternRuleRepo.getProxy(url)
    }
    log.info(s"[getProxy($url): result=$httpProxyOpt")
    Ok(Json.toJson(httpProxyOpt))
  }

  def getProxyP = SafeAsyncAction(parse.json) { request =>
    val ts = System.currentTimeMillis
    val url = request.body.as[String]
    val httpProxyOpt = db.readOnly(2, Slave) { implicit session =>
      urlPatternRuleRepo.getProxy(url)
    }
    log.info(s"[getProxyP] time-lapsed:${System.currentTimeMillis - ts} url=$url result=$httpProxyOpt")
    Ok(Json.toJson(httpProxyOpt))
  }

  def isUnscrapable(url: String, destinationUrl: Option[String]) = SafeAsyncAction { request =>
    val res = db.readOnly { implicit s => //using cache
      (urlPatternRuleRepo.isUnscrapable(url) || (destinationUrl.isDefined && urlPatternRuleRepo.isUnscrapable(destinationUrl.get)))
    }
    log.info(s"[isUnscrapable($url, $destinationUrl)] result=$res")
    Ok(JsBoolean(res))
  }

  def isUnscrapableP() = SafeAsyncAction(parse.json(maxLength = MaxContentLength)) { request =>
    val ts = System.currentTimeMillis
    val args = request.body.as[JsArray].value
    require(args != null && args.length >= 1, "Expect args to be url && opt[dstUrl] ")
    val url = args(0).as[String]
    val destinationUrl = if (args.length > 1) args(1).asOpt[String] else None
    val res = db.readOnly { implicit s => //using cache
      (urlPatternRuleRepo.isUnscrapable(url) || (destinationUrl.isDefined && urlPatternRuleRepo.isUnscrapable(destinationUrl.get)))
    }
    log.info(s"[isUnscrapableP] time-lapsed:${System.currentTimeMillis - ts} url=$url dstUrl=${destinationUrl.getOrElse("")} result=$res")
    Ok(JsBoolean(res))
  }

  def getNormalizedURIs(ids: String) = SafeAsyncAction { request =>
    val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
    val uris = db.readOnly { implicit s => uriIds map normUriRepo.get }  //using cache
    Ok(Json.toJson(uris))
  }

  def getNormalizedURIByURL() = SafeAsyncAction(parse.json(maxLength = MaxContentLength)) { request =>
    val url : String = Json.fromJson[String](request.body).get
    val uriOpt = db.readOnly { implicit s =>
      normUriRepo.getByUri(url) //using cache
    }
    uriOpt match {
      case Some(uri) => Ok(Json.toJson(uri))
      case None => Ok(JsNull)
    }
  }

  def getNormalizedUriByUrlOrPrenormalize() = SafeAsyncAction(parse.json(maxLength = MaxContentLength)) { request =>
    val url = Json.fromJson[String](request.body).get
    val normalizedUriOrPrenormStr = db.readOnly { implicit s => //using cache
      normUriRepo.getByUriOrPrenormalize(url) match {
        case Right(url) => Json.obj("url" -> url)
        case Left(nuri) => Json.obj("normalizedURI" -> nuri)
      }
    }
    Ok(normalizedUriOrPrenormStr)
  }

  def internNormalizedURI() = SafeAsyncAction(parse.json(maxLength = MaxContentLength)) { request =>
    val o = request.body.as[JsObject]
    val url = (o \ "url").as[String]
    val uri = db.readWrite(attempts = 2) { implicit s =>  //using cache
      normUriRepo.internByUri(url, NormalizationCandidate(o): _*)
    }
    val scrapeWanted = (o \ "scrapeWanted").asOpt[Boolean] getOrElse false
    if (scrapeWanted) SafeFuture { db.readWrite { implicit session => scrapeScheduler.scheduleScrape(uri) }}
    Ok(Json.toJson(uri))
  }

  def assignScrapeTasks(zkId:Id[ScraperWorker], max:Int) = SafeAsyncAction { request =>
    val res = scraperHelper.assignTasks(zkId, max)
    Ok(Json.toJson(res))
  }

  def getScrapeInfo() = SafeAsyncAction(parse.json) { request =>
    val ts = System.currentTimeMillis
    val json = request.body
    val uri = json.as[NormalizedURI]
    //Openning two sessions may be slower, the assumption is that >99% of the cases only one session is needed
    val infoOpt = db.readOnly(2, Slave) { implicit s =>  //no cache used
      scrapeInfoRepo.getByUriId(uri.id.get)
    }
    val info = infoOpt.getOrElse {
      db.readWrite(attempts = 3) { implicit s =>
        scrapeInfoRepo.save(ScrapeInfo(uriId = uri.id.get))
      }
    }
    log.info(s"[getScrapeInfo] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=$info")
    Ok(Json.toJson(info))
  }

  def saveScrapeInfo() = SafeAsyncAction(parse.json) { request =>
    val ts = System.currentTimeMillis
    val json = request.body
    val info = json.as[ScrapeInfo]
    val saved = db.readWrite(attempts = 3) { implicit s =>
      scrapeInfoRepo.save(info)
    }
    log.info(s"[saveScrapeInfo] time-lapsed:${System.currentTimeMillis - ts} result=$saved")
    Ok(Json.toJson(saved))
  }

  def getBookmarks(userId: Id[User]) = Action { request =>
    val bookmarks = db.readOnly(2, Slave) { implicit session => //no cache used
      bookmarkRepo.getByUser(userId)
    }
    Ok(Json.toJson(bookmarks))
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = Action { request =>
    val bookmark = db.readOnly { implicit session => //using cache
      bookmarkRepo.getByUriAndUser(uriId, userId)
    }.map(Json.toJson(_)).getOrElse(JsNull)
    Ok(bookmark)
  }

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]) = Action { request =>
    val ts = System.currentTimeMillis
    val bookmarks = db.readOnly(2, Slave) { implicit session =>
      bookmarkRepo.getByUriWithoutTitle(uriId)
    }
    log.info(s"[getBookmarksByUriWithoutTitle($uriId)] time-lapsed:${System.currentTimeMillis - ts} bookmarks(len=${bookmarks.length}):${bookmarks.mkString}")
    Ok(Json.toJson(bookmarks))
  }

  def getLatestBookmark(uriId: Id[NormalizedURI]) = Action { request =>
    val bookmarkOpt = db.readOnly(2) { implicit session => //using cache
      bookmarkRepo.latestBookmark(uriId)
    }
    log.info(s"[getLatestBookmark($uriId)] $bookmarkOpt")
    Ok(Json.toJson(bookmarkOpt))
  }

  def saveBookmark() = Action(parse.json) { request =>
    val bookmark = request.body.as[Bookmark]
    val saved = db.readWrite(attempts = 3) { implicit session =>
      bookmarkRepo.save(bookmark)
    }
    log.info(s"[saveBookmark] saved=$saved")
    Ok(Json.toJson(saved))
  }

  def getUsers(ids: String) = Action { request =>
    val userIds = ids.split(',').map(id => Id[User](id.toLong))
    val users = db.readOnly { implicit s => userIds map userRepo.get } //using cache
    Ok(Json.toJson(users))
  }

  def getUserIdsByExternalIds(ids: String) = Action { request =>
    val extUserIds = ids.split(',').map(_.trim).filterNot(_.isEmpty).map(ExternalId[User](_))
    val users = db.readOnly { implicit s => //using cache
      extUserIds.map { userRepo.getOpt(_).map(_.id.get.id) }.flatten
    }
    Ok(Json.toJson(users))
  }

  def getBasicUsers() = Action(parse.json) { request =>
    val userIds = request.body.as[JsArray].value.map{x => Id[User](x.as[Long])}
    val users = db.readOnly { implicit s => //using cache
      userIds.map{ userId => userId.id.toString -> Json.toJson(basicUserRepo.load(userId)) }.toMap
    }
    Ok(Json.toJson(users))
  }

  def getBasicUsersNoCache() = Action(parse.json) { request =>
    val userIds = request.body.as[JsArray].value.map{x => Id[User](x.as[Long])}
    val users = db.readOnly { implicit s => //using cache
      userIds.map{ userId =>
        val user = userRepo.getNoCache(userId)
        userId.id.toString -> Json.toJson(BasicUser.fromUser(user))
      }.toMap
    }
    Ok(Json.toJson(users))
  }

  def getEmailAddressesForUsers() = Action(parse.json) { request =>
    val userIds = request.body.as[JsArray].value.map{x => Id[User](x.as[Long])}
    val emails = db.readOnly(2, Slave){ implicit s =>
      userIds.map{userId => userId.id.toString -> emailAddressRepo.getAllByUser(userId).map{_.address}}.toMap
    }
    val json = Json.toJson(emails)
    log.info(s"json emails for users [$userIds] are $json")
    Ok(json)
  }

  def getCollectionIdsByExternalIds(ids: String) = Action { request =>
    val extCollIds = ids.split(',').map(_.trim).filterNot(_.isEmpty).map(ExternalId[Collection](_))
    val collectionIds = db.readOnly(2, Slave) { implicit s => //no cache used
      extCollIds.map { collectionRepo.getOpt(_).map(_.id.get.id) }.flatten
    }
    Ok(Json.toJson(collectionIds))
  }

  // on kifi
  def getConnectedUsers(id : Id[User]) = Action { request =>
    val ids = db.readOnly { implicit s => //using cache
      userConnectionRepo.getConnectedUsers(id).toSeq
        .map { friendId => JsNumber(friendId.id) }
    }
    Ok(JsArray(ids))
  }

  def getActiveExperiments = Action { request =>
    val exp = db.readOnly { implicit s => searchConfigExperimentRepo.getActive() } //using cache
    Ok(Json.toJson(exp))
  }

  def getExperiments = Action { request =>
    val exp = db.readOnly(2, Slave) { implicit s => searchConfigExperimentRepo.getNotInactive() }
    Ok(Json.toJson(exp))
  }

  def getExperiment(id: Id[SearchConfigExperiment]) = Action{ request =>
    val exp = db.readOnly(2, Slave) { implicit s => searchConfigExperimentRepo.get(id) } //no cache used
    Ok(Json.toJson(exp))
  }

  def saveExperiment = Action(parse.json) { request =>
    val exp = Json.fromJson[SearchConfigExperiment](request.body).get
    val saved = db.readWrite(attempts = 3) { implicit s => searchConfigExperimentRepo.save(exp) }
    Ok(Json.toJson(saved))
  }

  def getUserExperiments(userId: Id[User]) = Action { request =>
    val experiments = db.readOnly { implicit s => //using cache
      userExperimentRepo.getUserExperiments(userId).map(_.value)
    }
    Ok(Json.toJson(experiments))
  }

  def getExperimentsByUserIds() = Action(parse.json) { request =>
    val userIds = request.body.as[JsArray].value.map{x => Id[User](x.as[Long])}
    val exps = db.readOnly { implicit s => //using cache
      userIds.map{ uid =>
        uid.id.toString -> userExperimentRepo.getUserExperiments(uid)
      }.toMap
    }
    Ok(Json.toJson(exps))
  }

  def getExperimentGenerators() = Action { request =>
    val result = db.readOnly { implicit session => probabilisticExperimentGeneratorRepo.allActive() }
    Ok(Json.toJson(result))
  }

  def getCollectionsByUser(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(db.readOnly { implicit s => collectionRepo.getByUser(userId) })) //using cache
  }

  def getBookmarksInCollection(collectionId: Id[Collection]) = Action { request =>
    Ok(Json.toJson(db.readOnly { implicit s => //using cache
      keepToCollectionRepo.getBookmarksInCollection(collectionId) map bookmarkRepo.get
    }))
  }

  def getUriIdsInCollection(collectionId: Id[Collection]) = Action { request =>
    val uris = db.readOnly(2, Slave) { implicit s =>
      keepToCollectionRepo.getUriIdsInCollection(collectionId)
    }
    Ok(Json.toJson(uris))
  }

  def getSessionByExternalId(sessionId: ExternalId[UserSession]) = Action { request =>
    val res = db.readOnly { implicit session => //using cache
      sessionRepo.getOpt(sessionId)
    }
    Ok(Json.toJson(res))
  }

  def searchFriends(userId: Id[User]) = Action { request =>
    db.readOnly { implicit s => //using cache
      Ok(Json.toJson(searchFriendRepo.getSearchFriends(userId).map(_.id)))
    }
  }

  def getUnfriends(userId: Id[User]) = Action { request =>
    db.readOnly{ implicit s =>
      Ok(Json.toJson(searchFriendRepo.getUnfriends(userId).map(_.id)))
    }
  }

  def clickAttribution() = SafeAsyncAction(parse.json) { request =>
    val json = request.body
    val clicker = Id.format[User].reads(json \ "clicker").get
    val uriId = Id.format[NormalizedURI].reads(json \ "uriId").get
    val keepers = (json \ "keepers").as[JsArray].value.map(ExternalId.format[User].reads(_).get)
    db.readWrite { implicit session =>
      if (keepers.isEmpty) userBookmarkClicksRepo.increaseCounts(clicker, uriId, true)
      else keepers.foreach { extId => userBookmarkClicksRepo.increaseCounts(userRepo.get(extId).id.get, uriId, false) }
    }
    Ok
  }

  def getFriendRequestsBySender(senderId: Id[User]) = Action { request =>
    val requests = db.readOnly(2, Slave) { implicit s =>
      friendRequestRepo.getBySender(senderId)
    }
    Ok(JsArray(requests.map{ x => Json.toJson(x) }))
  }

  def setUserValue(userId: Id[User], key: String) = SafeAsyncAction(parse.json) { request =>
    val value = request.body.as[String]
    db.readWrite(attempts = 3) { implicit session => userValueRepo.setValue(userId, key, value) }
    Ok
  }

  def getUserValue(userId: Id[User], key: String) = SafeAsyncAction { request =>
    val value = db.readOnly { implicit session => userValueRepo.getValueStringOpt(userId, key) } //using cache
    Ok(Json.toJson(value))
  }

  def getUserSegment(userId: Id[User]) = SafeAsyncAction { request =>
    val segment = userCommander.getUserSegment(userId)
    Ok(Json.toJson(segment))
  }

  def getExtensionVersion(installationId: ExternalId[KifiInstallation]) = SafeAsyncAction { request =>
    val version = db.readOnly(2, Slave) { implicit session => kifiInstallationRepo.get(installationId).version.toString }
    Ok(JsString(version))
  }

  def triggerRawKeepImport() = Action { request =>
    rawKeepImporterPlugin.processKeeps(broadcastToOthers = false)
    Status(202)("0")
  }

  def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]) = Action.async { request =>
    val socialUserInfo = db.readOnly { implicit session =>
      socialUserInfoRepo.get(socialUserInfoId)
    }
    socialGraphPlugin.asyncFetch(socialUserInfo, broadcastToOthers = false).map { _ =>
      Ok("0")
    }
  }

  def updateURIRestriction() = SafeAsyncAction(parse.json){ request =>
    val uriId = Json.fromJson[Id[NormalizedURI]](request.body \ "uriId").get
    val r = request.body \ "restriction" match {
      case JsNull => None
      case x => Some(Json.fromJson[Restriction](x).get)
    }
    db.readWrite{ implicit s =>
      normUriRepo.updateURIRestriction(uriId, r)
    }
    Ok
  }

  def getVerifiedAddressOwner() = SafeAsyncAction(parse.json) { request =>
    val address = (request.body \ "address").as[String]
    val ownerIdOpt = db.readOnly { implicit session =>
      emailAddressRepo.getVerifiedOwner(address)
    }
    implicit val userIdFormat = Id.format[User]
    Ok(Json.toJson(ownerIdOpt))
  }
}
