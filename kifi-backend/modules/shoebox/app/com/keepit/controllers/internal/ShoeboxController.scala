package com.keepit.controllers.internal

import com.google.inject.{Provider, Inject}
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{EmailAddress, ElectronicMail, LocalPostOffice}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer._
import com.keepit.search.{SearchConfigExperiment, SearchConfigExperimentRepo}

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.scraper._
import com.keepit.social.{SocialGraphPlugin, BasicUser, SocialNetworkType}

import com.keepit.commanders.{KeepsCommander, RawKeepImporterPlugin, UserCommander}
import com.keepit.common.db.slick.Database.Slave
import com.keepit.normalizer.VerifiedCandidate
import com.keepit.model.KifiInstallation
import com.keepit.social.SocialId
import play.api.libs.json.JsObject
import scala.util.{Try, Failure, Success}
import com.keepit.common.akka.SafeFuture
import com.keepit.heimdal.SanitizedKifiHit


class ShoeboxController @Inject() (
  db: Database,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  normUriRepo: NormalizedURIRepo,
  normalizedURIInterner: NormalizedURIInterner,
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
  sessionRepo: UserSessionRepo,
  searchFriendRepo: SearchFriendRepo,
  emailAddressRepo: UserEmailAddressRepo,
  keepsCommander: KeepsCommander,
  scrapeInfoRepo:ScrapeInfoRepo,
  imageInfoRepo:ImageInfoRepo,
  pageInfoRepo:PageInfoRepo,
  friendRequestRepo: FriendRequestRepo,
  userValueRepo: UserValueRepo,
  userCommander: UserCommander,
  kifiInstallationRepo: KifiInstallationRepo,
  socialGraphPlugin: SocialGraphPlugin,
  rawKeepImporterPlugin: RawKeepImporterPlugin,
  scraperHelper: ScraperCallbackHelper,
  scrapeScheduler: ScrapeSchedulerPlugin,
  verifiedEmailUserIdCache: VerifiedEmailUserIdCache,
  latestKeepUrlCache: LatestKeepUrlCache
)
  (implicit private val clock: Clock,
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

  def sendMail = Action(parse.tolerantJson) { request =>
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

  def sendMailToUser = Action(parse.tolerantJson) { request =>
    val userId = Id[User]((request.body \ "user").as[Long])
    val email = (request.body \ "email").as[ElectronicMail]

    val addrs = db.readOnly(2, Slave) { implicit session => emailAddressRepo.getAllByUser(userId) }
    for (addr <- addrs.find(_.verifiedAt.isDefined).orElse(addrs.headOption)) {
      db.readWrite(attempts = 3){ implicit session => postOffice.sendMail(email.copy(to=List(addr.address))) }
    }
    Ok("true")
  }

  def getNormalizedURI(id: Id[NormalizedURI]) = SafeAsyncAction {
    val uri = db.readOnly { implicit s =>
      normUriRepo.get(id)//using cache
    }
    Ok(Json.toJson(uri))
  }

  def saveNormalizedURI() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val ts = System.currentTimeMillis
    val normalizedUri = request.body.as[NormalizedURI]
    val saved = scraperHelper.saveNormalizedURI(normalizedUri)
    log.debug(s"[saveNormalizedURI] time-lapsed:${System.currentTimeMillis - ts} url=(${normalizedUri.url}) result=$saved")
    Ok(Json.toJson(saved))
  }

  def updateNormalizedURI(uriId: Id[NormalizedURI]) = SafeAsyncAction(parse.tolerantJson) { request =>
    val saveResult = Try {
      // Handle serialization in session to be transactional.
      val originalNormalizedUri = db.readOnly{ implicit s => normUriRepo.get(uriId) }
      val originalJson = Json.toJson(originalNormalizedUri).as[JsObject]
      val newNormalizedUriResult = Json.fromJson[NormalizedURI](originalJson ++ request.body.as[JsObject])

      newNormalizedUriResult.fold({ invalid =>
        val error = "Could not deserialize NormalizedURI ($uriId) update: $invalid\nOriginal: $originalNormalizedUri\nbody: ${request.body}"
        airbrake.notify(error)
        throw new Exception(error)
      }, { normalizedUri =>
        scraperHelper.saveNormalizedURI(normalizedUri)
      })
    }
    saveResult match {
      case Success(res) =>
        Ok
      case Failure(ex) =>
        log.error(s"Could not deserialize NormalizedURI ($uriId) update: $ex\nbody: ${request.body}")
        airbrake.notify(s"Could not deserialize NormalizedURI ($uriId) update", ex)
        throw ex
    }
  }

  // todo: revisit
  def recordPermanentRedirect() = Action.async(parse.tolerantJson) { request =>
    val ts = System.currentTimeMillis
    log.debug(s"[recordPermanentRedirect] body=${request.body}")
    val args = request.body.as[JsArray].value
    require(!args.isEmpty && args.length == 2, "Both uri and redirect need to be supplied")
    val uri = args(0).as[NormalizedURI]
    val redirect = args(1).as[HttpRedirect]
    require(redirect.isPermanent, "HTTP redirect is not permanent.")
    require(redirect.isLocatedAt(uri.url), "Current Location of HTTP redirect does not match normalized Uri.")
    val verifiedCandidateOption = normalizationServiceProvider.get.prenormalize(redirect.newDestination).toOption.flatMap { prenormalizedDestination =>
      db.readWrite { implicit session =>
        val (candidateUrl, candidateNormalizationOption) = normUriRepo.getByNormalizedUrl(prenormalizedDestination) match {
          case None => (prenormalizedDestination, SchemeNormalizer.findSchemeNormalization(prenormalizedDestination))
          case Some(referenceUri) if referenceUri.state != NormalizedURIStates.REDIRECTED => (referenceUri.url, referenceUri.normalization)
          case Some(reverseRedirectUri) if reverseRedirectUri.redirect == Some(uri.id.get) =>
            (reverseRedirectUri.url, SchemeNormalizer.findSchemeNormalization(reverseRedirectUri.url))
          case Some(redirectedUri) =>
            val referenceUri = normUriRepo.get(redirectedUri.redirect.get)
            (referenceUri.url, referenceUri.normalization)
        }
        candidateNormalizationOption.map(VerifiedCandidate(candidateUrl, _))
      }
    }

    val resFutureOption = verifiedCandidateOption.map { verifiedCandidate =>
      val toBeRedirected = NormalizationReference(uri, correctedNormalization = Some(Normalization.MOVED))
      val updateFuture = normalizationServiceProvider.get.update(toBeRedirected, verifiedCandidate)
      // Scraper reports entire NormalizedUri objects with a major chance of stale data / race conditions
      // The following is meant for synchronisation and should be revisited when scraper apis are rewritten to report modified fields only

      updateFuture.map {
        case Some(update) => {
          val redirectedUri = db.readOnly() { implicit session => normUriRepo.get(uri.id.get) }
          log.debug(s"[recordedPermanentRedirect($uri, $redirect)] time-lapsed: ${System.currentTimeMillis - ts} result=$redirectedUri")
          redirectedUri
        }
        case None => {
          log.warn(s"[failedToRecordPermanentRedirect($uri, $redirect)] Normalization update failed - time-lapsed: ${System.currentTimeMillis - ts} result=$uri")
          uri
        }
      }
    }

    val resFuture = resFutureOption getOrElse {
        log.warn(s"[failedToRecordPermanentRedirect($uri, $redirect)] Redirection normalization empty - time-lapsed: ${System.currentTimeMillis - ts} result=$uri")
        Future.successful(uri)
      }

    resFuture.map { res => Ok(Json.toJson(res)) }
  }

  def recordScrapedNormalization() = Action.async(parse.tolerantJson) { request =>

    val candidateUrl = (request.body \ "url").as[String]
    val candidateNormalization = (request.body \ "normalization").as[Normalization]
    val scrapedCandidate = ScrapedCandidate(candidateUrl, candidateNormalization)

    val uriId = (request.body \ "id").as[Id[NormalizedURI]](Id.format)
    val signature = Signature((request.body \ "signature").as[String])
    val scrapedUri = db.readOnly { implicit session => normUriRepo.get(uriId) }

    normalizationServiceProvider.get.update(NormalizationReference(scrapedUri, signature = Some(signature)), scrapedCandidate).map { newReferenceOption =>

      (request.body \ "alternateUrls").asOpt[Set[String]].foreach { alternateUrls =>
        val bestReference = newReferenceOption.map { newReferenceId =>
          db.readOnly { implicit session =>
            normUriRepo.get(newReferenceId)
          }
        } getOrElse scrapedUri
        // todo(Léo): What follows is dangerous. Someone could mess up with our data by reporting wrong alternate Urls on its website. We need to do a specific content check.
        bestReference.normalization.map(ScrapedCandidate(scrapedUri.url, _)).foreach { bestCandidate =>
          alternateUrls.foreach { alternateUrl =>
            val uri = db.readOnly { implicit session =>
              normalizedURIInterner.getByUri(alternateUrl)
            }
            uri match {
              case Some(existingUri) if existingUri.id.get == bestReference.id.get => // ignore
              case _ => db.readWrite { implicit session =>
                normalizedURIInterner.internByUri(alternateUrl, bestCandidate)
              }
            }
          }
        }
      }
      Ok
    }
  }

  def getProxy(url:String) = SafeAsyncAction { request =>
    val httpProxyOpt = db.readOnly(2, Slave) { implicit session =>
      urlPatternRuleRepo.getProxy(url)
    }
    log.debug(s"[getProxy($url): result=$httpProxyOpt")
    Ok(Json.toJson(httpProxyOpt))
  }

  def getProxyP = SafeAsyncAction(parse.tolerantJson) { request =>
    val url = request.body.as[String]
    val httpProxyOpt = db.readOnly(2, Slave) { implicit session =>
      urlPatternRuleRepo.getProxy(url)
    }
    Ok(Json.toJson(httpProxyOpt))
  }

  def isUnscrapable(url: String, destinationUrl: Option[String]) = SafeAsyncAction { request =>
    val res = urlPatternRuleRepo.rules().isUnscrapable(url) || (destinationUrl.isDefined && urlPatternRuleRepo.rules().isUnscrapable(destinationUrl.get))
    log.debug(s"[isUnscrapable($url, $destinationUrl)] result=$res")
    Ok(JsBoolean(res))
  }

  def isUnscrapableP() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val ts = System.currentTimeMillis
    val args = request.body.as[JsArray].value
    require(args != null && args.length >= 1, "Expect args to be url && opt[dstUrl] ")
    val url = args(0).as[String]
    val destinationUrl = if (args.length > 1) args(1).asOpt[String] else None
    val res = urlPatternRuleRepo.rules().isUnscrapable(url) || (destinationUrl.isDefined && urlPatternRuleRepo.rules().isUnscrapable(destinationUrl.get))
    log.debug(s"[isUnscrapableP] time-lapsed:${System.currentTimeMillis - ts} url=$url dstUrl=${destinationUrl.getOrElse("")} result=$res")
    Ok(JsBoolean(res))
  }

  def getNormalizedURIs(ids: String) = SafeAsyncAction { request =>
    val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
    val uris = db.readOnly { implicit s => uriIds map normUriRepo.get }  //using cache
    Ok(Json.toJson(uris))
  }

  def getNormalizedURIExternalIDs(ids: String) = SafeAsyncAction { request =>
    val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
    val uris = db.readOnly { implicit s => uriIds.map(id => normUriRepo.get(id).externalId) }  //using cache
    Ok(Json.toJson(uris))
  }

  def getNormalizedURIByURL() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val url : String = Json.fromJson[String](request.body).get
    val uriOpt = db.readOnly { implicit s =>
      normalizedURIInterner.getByUri(url) //using cache
    }
    uriOpt match {
      case Some(uri) => Ok(Json.toJson(uri))
      case None => Ok(JsNull)
    }
  }

  def getNormalizedUriByUrlOrPrenormalize() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val url = Json.fromJson[String](request.body).get
    val normalizedUriOrPrenormStr = db.readOnly { implicit s => //using cache
      normalizedURIInterner.getByUriOrPrenormalize(url) match {
        case Success(Right(prenormalizedUrl)) => Json.obj("url" -> prenormalizedUrl)
        case Success(Left(nuri)) => Json.obj("normalizedURI" -> nuri)
        case Failure(ex) => {
          log.error("Could not get normalized uri or prenormalized url", ex)
          Json.obj("url" -> url)
        }
      }
    }
    Ok(normalizedUriOrPrenormStr)
  }

  def internNormalizedURI() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val o = request.body.as[JsObject]
    val url = (o \ "url").as[String]
    val uri = db.readWrite(attempts = 1) { implicit s =>  //using cache
      normalizedURIInterner.internByUri(url, NormalizationCandidate(o): _*)
    }
    val scrapeWanted = (o \ "scrapeWanted").asOpt[Boolean] getOrElse false
    if (scrapeWanted) SafeFuture { db.readWrite { implicit session => scrapeScheduler.scheduleScrape(uri) }}
    Ok(Json.toJson(uri))
  }

  def assignScrapeTasks(zkId:Id[ScraperWorker], max:Int) = SafeAsyncAction { request =>
    val res = scraperHelper.assignTasks(zkId, max)
    Ok(Json.toJson(res))
  }

  def getScrapeInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
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
    log.debug(s"[getScrapeInfo] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=$info")
    Ok(Json.toJson(info))
  }

  def getImageInfo(id:Id[ImageInfo]) = SafeAsyncAction { request =>
    val imageInfo = db.readOnly { implicit ro =>
      imageInfoRepo.get(id)
    }
    log.debug(s"[getImageInfo($id)] result=$imageInfo")
    Ok(Json.toJson(imageInfo))
  }

  def saveImageInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    val json = request.body
    val info = json.as[ImageInfo]
    val saved = scraperHelper.saveImageInfo(info)
    log.debug(s"[saveImageInfo] result=$saved")
    Ok(Json.toJson(saved))
  }

  def savePageInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    val json = request.body
    val info = json.as[PageInfo]
    val toSave = db.readOnly { implicit ro => pageInfoRepo.getByUri(info.uriId) } map { p => info.withId(p.id.get) } getOrElse info
    val saved = scraperHelper.savePageInfo(toSave)
    log.debug(s"[savePageInfo] result=$saved")
    Ok(Json.toJson(saved))
  }

  def saveScrapeInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    val ts = System.currentTimeMillis
    val json = request.body
    val info = json.as[ScrapeInfo]
    val saved = db.readWrite(attempts = 3) { implicit s =>
      scrapeInfoRepo.save(info)
    }
    log.debug(s"[saveScrapeInfo] time-lapsed:${System.currentTimeMillis - ts} result=$saved")
    Ok(Json.toJson(saved))
  }

  def getBookmarks(userId: Id[User]) = Action { request =>
    val bookmarks = db.readOnly(2, Slave) { implicit session => //no cache used
      keepRepo.getByUser(userId)
    }
    Ok(Json.toJson(bookmarks))
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = Action { request =>
    val bookmark = db.readOnly { implicit session => //using cache
      keepRepo.getByUriAndUser(uriId, userId)
    }.map(Json.toJson(_)).getOrElse(JsNull)
    Ok(bookmark)
  }

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]) = Action { request =>
    val ts = System.currentTimeMillis
    val bookmarks = db.readOnly(2, Slave) { implicit session =>
      keepRepo.getByUriWithoutTitle(uriId)
    }
    log.debug(s"[getBookmarksByUriWithoutTitle($uriId)] time-lapsed:${System.currentTimeMillis - ts} bookmarks(len=${bookmarks.length}):${bookmarks.mkString}")
    Ok(Json.toJson(bookmarks))
  }

  def getLatestKeep() = Action(parse.json) { request =>
    val url = request.body.as[String]
    val bookmarkOpt = db.readOnly(2, Slave) { implicit session =>
      latestKeepUrlCache.getOrElseOpt(LatestKeepUrlKey(url)) {
        normUriRepo.getByNormalizedUrl(url).flatMap{ uri =>
          keepRepo.latestKeep(uri.id.get)
        }
      }
    }
    log.debug(s"[getLatestKeep($url)] $bookmarkOpt")
    Ok(Json.toJson(bookmarkOpt))
  }

  def saveBookmark() = Action(parse.tolerantJson) { request =>
    val bookmark = request.body.as[Keep]
    val saved = db.readWrite(attempts = 3) { implicit session =>
      keepRepo.save(bookmark)
    }
    log.debug(s"[saveBookmark] saved=$saved")
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

  def getBasicUsers() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map{x => Id[User](x.as[Long])}
    val users = db.readOnly { implicit s => //using cache
      userIds.map{ userId => userId.id.toString -> Json.toJson(basicUserRepo.load(userId)) }.toMap
    }
    Ok(Json.toJson(users))
  }

  def getBasicUsersNoCache() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map{x => Id[User](x.as[Long])}
    val users = db.readOnly { implicit s => //using cache
      userIds.map{ userId =>
        val user = userRepo.getNoCache(userId)
        userId.id.toString -> Json.toJson(BasicUser.fromUser(user))
      }.toMap
    }
    Ok(Json.toJson(users))
  }

  def getEmailAddressesForUsers() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map{x => Id[User](x.as[Long])}
    val emails = db.readOnly(2, Slave){ implicit s =>
      userIds.map{userId => userId.id.toString -> emailAddressRepo.getAllByUser(userId).map{_.address}}.toMap
    }
    val json = Json.toJson(emails)
    log.debug(s"json emails for users [$userIds] are $json")
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

  def saveExperiment = Action(parse.tolerantJson) { request =>
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

  def getExperimentsByUserIds() = Action(parse.tolerantJson) { request =>
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
    Ok(Json.toJson(db.readOnly { implicit s => collectionRepo.getUnfortunatelyIncompleteTagsByUser(userId) })) //using cache
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

  def kifiHit() = SafeAsyncAction(parse.tolerantJson) { request =>
    val json = request.body
    val clicker = (json \ "clickerId").as(Id.format[User])
    val kifiHit = (json \ "kifiHit").as[SanitizedKifiHit]
    keepsCommander.processKifiHit(clicker, kifiHit)
    Ok
  }

  def getFriendRequestsBySender(senderId: Id[User]) = Action { request =>
    val requests = db.readOnly(2, Slave) { implicit s =>
      friendRequestRepo.getBySender(senderId)
    }
    Ok(JsArray(requests.map{ x => Json.toJson(x) }))
  }

  def setUserValue(userId: Id[User], key: String) = SafeAsyncAction(parse.tolerantJson) { request =>
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

  def updateURIRestriction() = SafeAsyncAction(parse.tolerantJson){ request =>
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

  def getVerifiedAddressOwners() = SafeAsyncAction(parse.tolerantJson) { request =>
    val addresses = (request.body \ "addresses").as[Seq[EmailAddress]]
    val owners = db.readOnly { implicit session =>
      for {
        address <- addresses
        userId <- verifiedEmailUserIdCache.getOrElseOpt(VerifiedEmailUserIdKey(address)) {
          emailAddressRepo.getVerifiedOwner(address)
        }
      } yield (address -> userId)
    }
    val json = Json.toJson(owners.map { case (address, userId) => address.address -> userId }.toMap)
    Ok(json)
  }

  def getAllURLPatternRules() = Action { request =>
    val patterns = urlPatternRuleRepo.rules.rules
    Ok(Json.toJson(patterns))
  }

  def getUserImageUrl(id: Long, width: Int) = Action.async { request =>
    userCommander.getUserImageUrl(Id[User](id), width).map{ url =>
      Ok(Json.toJson(url))
    }
  }
}
