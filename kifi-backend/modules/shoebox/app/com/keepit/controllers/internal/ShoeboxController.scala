package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.commanders.{ KeepsCommander, RawKeepImporterPlugin, UserCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ ElectronicMail, LocalPostOffice }
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.heimdal.SanitizedKifiHit
import com.keepit.model._
import com.keepit.normalizer._
import com.keepit.scraper._
import com.keepit.search.{ SearchConfigExperiment, SearchConfigExperimentRepo }
import com.keepit.social.{ BasicUser, SocialGraphPlugin, SocialId, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Action

import scala.util.{ Failure, Success }

class ShoeboxController @Inject() (
  db: Database,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  normUriRepo: NormalizedURIRepo,
  normalizedURIInterner: NormalizedURIInterner,
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
  friendRequestRepo: FriendRequestRepo,
  userValueRepo: UserValueRepo,
  userCommander: UserCommander,
  kifiInstallationRepo: KifiInstallationRepo,
  socialGraphPlugin: SocialGraphPlugin,
  rawKeepImporterPlugin: RawKeepImporterPlugin,
  scrapeScheduler: ScrapeScheduler,
  verifiedEmailUserIdCache: VerifiedEmailUserIdCache)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends ShoeboxServiceController with Logging {

  val MaxContentLength = 6000

  def getUserOpt(id: ExternalId[User]) = Action { request =>
    val userOpt = db.readOnlyReplica { implicit s => userRepo.getOpt(id) } //using cache
    userOpt match {
      case Some(user) => Ok(Json.toJson(user))
      case None => Ok(JsNull)
    }
  }

  def getSocialUserInfoByNetworkAndSocialId(id: String, networkType: String) = Action {
    val socialId = SocialId(id)
    val network = SocialNetworkType(networkType)
    val sui = db.readOnlyReplica { implicit session =>
      socialUserInfoRepo.get(socialId, network) //using cache
    }
    Ok(Json.toJson(sui))
  }

  def getSocialUserInfosByUserId(userId: Id[User]) = Action {
    val sui = db.readOnlyReplica { implicit session =>
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

    val addrs = db.readOnlyReplica(2) { implicit session => emailAddressRepo.getAllByUser(userId) }
    for (addr <- addrs.find(_.verifiedAt.isDefined).orElse(addrs.headOption)) {
      db.readWrite(attempts = 3) { implicit session => postOffice.sendMail(email.copy(to = List(addr.address))) }
    }
    Ok("true")
  }

  def getNormalizedURI(id: Id[NormalizedURI]) = SafeAsyncAction {
    val uri = db.readOnlyMaster { implicit s =>
      normUriRepo.get(id) //using cache
    }
    Ok(Json.toJson(uri))
  }

  def getNormalizedURIs(ids: String) = SafeAsyncAction { request =>
    val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
    val uris = db.readOnlyMaster { implicit s => uriIds map normUriRepo.get } //using cache
    Ok(Json.toJson(uris))
  }

  def getNormalizedURIExternalIDs(ids: String) = SafeAsyncAction { request =>
    val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
    val uris = db.readOnlyMaster { implicit s => uriIds.map(id => normUriRepo.get(id).externalId) } //using cache
    Ok(Json.toJson(uris))
  }

  def getNormalizedURIByURL() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val url: String = Json.fromJson[String](request.body).get
    val uriOpt = db.readOnlyMaster { implicit s =>
      normalizedURIInterner.getByUri(url) //using cache
    }
    uriOpt match {
      case Some(uri) => Ok(Json.toJson(uri))
      case None => Ok(JsNull)
    }
  }

  def getNormalizedUriByUrlOrPrenormalize() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val url = Json.fromJson[String](request.body).get
    val normalizedUriOrPrenormStr = db.readOnlyMaster { implicit s => //using cache
      normalizedURIInterner.getByUriOrPrenormalize(url) match {
        case Success(Right(prenormalizedUrl)) => Json.obj("url" -> prenormalizedUrl)
        case Success(Left(nuri)) => Json.obj("normalizedURI" -> nuri)
        case Failure(ex) =>
          log.error("Could not get normalized uri or prenormalized url", ex)
          Json.obj("url" -> url)
      }
    }
    Ok(normalizedUriOrPrenormStr)
  }

  def internNormalizedURI() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val o = request.body.as[JsObject]
    val url = (o \ "url").as[String]
    val uri = db.readWrite(attempts = 1) { implicit s => //using cache
      normalizedURIInterner.internByUri(url, NormalizationCandidate(o): _*)
    }
    val scrapeWanted = (o \ "scrapeWanted").asOpt[Boolean] getOrElse false
    if (scrapeWanted) SafeFuture { db.readWrite { implicit session => scrapeScheduler.scheduleScrape(uri) } }
    Ok(Json.toJson(uri))
  }

  def getBookmarks(userId: Id[User]) = Action { request =>
    val bookmarks = db.readOnlyReplica(2) { implicit session => //no cache used
      keepRepo.getByUser(userId)
    }
    Ok(Json.toJson(bookmarks))
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = Action { request =>
    val bookmark = db.readOnlyMaster { implicit session => //using cache
      keepRepo.getByUriAndUser(uriId, userId)
    }.map(Json.toJson(_)).getOrElse(JsNull)
    Ok(bookmark)
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
    val users = db.readOnlyMaster { implicit s => userIds map userRepo.get } //using cache
    Ok(Json.toJson(users))
  }

  def getUserIdsByExternalIds(ids: String) = Action { request =>
    val extUserIds = ids.split(',').map(_.trim).filterNot(_.isEmpty).map(ExternalId[User](_))
    val users = db.readOnlyMaster { implicit s => //using cache
      extUserIds.map { userRepo.getOpt(_).map(_.id.get.id) }.flatten
    }
    Ok(Json.toJson(users))
  }

  def getBasicUsers() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map { x => Id[User](x.as[Long]) }
    val users = db.readOnlyMaster { implicit s => //using cache
      userIds.map { userId => userId.id.toString -> Json.toJson(basicUserRepo.load(userId)) }.toMap
    }
    Ok(Json.toJson(users))
  }

  def getBasicUsersNoCache() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map { x => Id[User](x.as[Long]) }
    val users = db.readOnlyMaster { implicit s => //using cache
      userIds.map { userId =>
        val user = userRepo.getNoCache(userId)
        userId.id.toString -> Json.toJson(BasicUser.fromUser(user))
      }.toMap
    }
    Ok(Json.toJson(users))
  }

  def getEmailAddressesForUsers() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map { x => Id[User](x.as[Long]) }
    val emails = db.readOnlyReplica(2) { implicit s =>
      userIds.map { userId => userId.id.toString -> emailAddressRepo.getAllByUser(userId).map { _.address } }.toMap
    }
    val json = Json.toJson(emails)
    log.debug(s"json emails for users [$userIds] are $json")
    Ok(json)
  }

  def getCollectionIdsByExternalIds(ids: String) = Action { request =>
    val extCollIds = ids.split(',').map(_.trim).filterNot(_.isEmpty).map(ExternalId[Collection](_))
    val collectionIds = db.readOnlyReplica(2) { implicit s => //no cache used
      extCollIds.map { collectionRepo.getOpt(_).map(_.id.get.id) }.flatten
    }
    Ok(Json.toJson(collectionIds))
  }

  // on kifi
  def getConnectedUsers(id: Id[User]) = Action { request =>
    val ids = db.readOnlyMaster { implicit s => //using cache
      userConnectionRepo.getConnectedUsers(id).toSeq
        .map { friendId => JsNumber(friendId.id) }
    }
    Ok(JsArray(ids))
  }

  def getActiveExperiments = Action { request =>
    val exp = db.readOnlyMaster { implicit s => searchConfigExperimentRepo.getActive() } //using cache
    Ok(Json.toJson(exp))
  }

  def getExperiments = Action { request =>
    val exp = db.readOnlyReplica(2) { implicit s => searchConfigExperimentRepo.getNotInactive() }
    Ok(Json.toJson(exp))
  }

  def getExperiment(id: Id[SearchConfigExperiment]) = Action { request =>
    val exp = db.readOnlyReplica(2) { implicit s => searchConfigExperimentRepo.get(id) } //no cache used
    Ok(Json.toJson(exp))
  }

  def saveExperiment = Action(parse.tolerantJson) { request =>
    val exp = Json.fromJson[SearchConfigExperiment](request.body).get
    val saved = db.readWrite(attempts = 3) { implicit s => searchConfigExperimentRepo.save(exp) }
    Ok(Json.toJson(saved))
  }

  def getUserExperiments(userId: Id[User]) = Action { request =>
    val experiments = db.readOnlyMaster { implicit s => //using cache
      userExperimentRepo.getUserExperiments(userId).map(_.value)
    }
    Ok(Json.toJson(experiments))
  }

  def getExperimentsByUserIds() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map { x => Id[User](x.as[Long]) }
    val exps = db.readOnlyMaster { implicit s => //using cache
      userIds.map { uid =>
        uid.id.toString -> userExperimentRepo.getUserExperiments(uid)
      }.toMap
    }
    Ok(Json.toJson(exps))
  }

  def getExperimentGenerators() = Action { request =>
    val result = db.readOnlyReplica { implicit session => probabilisticExperimentGeneratorRepo.allActive() }
    Ok(Json.toJson(result))
  }

  def getCollectionsByUser(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(db.readOnlyMaster { implicit s => collectionRepo.getUnfortunatelyIncompleteTagsByUser(userId) })) //using cache
  }

  def getUriIdsInCollection(collectionId: Id[Collection]) = Action { request =>
    val uris = db.readOnlyReplica(2) { implicit s =>
      keepToCollectionRepo.getUriIdsInCollection(collectionId)
    }
    Ok(Json.toJson(uris))
  }

  def getSessionByExternalId(sessionId: ExternalId[UserSession]) = Action { request =>
    val res = db.readOnlyMaster { implicit session => //using cache
      sessionRepo.getOpt(sessionId)
    }
    Ok(Json.toJson(res))
  }

  def searchFriends(userId: Id[User]) = Action { request =>
    db.readOnlyMaster { implicit s => //using cache
      Ok(Json.toJson(searchFriendRepo.getSearchFriends(userId).map(_.id)))
    }
  }

  def getUnfriends(userId: Id[User]) = Action { request =>
    db.readOnlyReplica { implicit s =>
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

  def getHelpRankInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    val uriIds = Json.fromJson[Seq[Id[NormalizedURI]]](request.body).get
    val infos = keepsCommander.getHelpRankInfo(uriIds.toSet)
    log.info(s"[getHelpRankInfo] infos=${infos.mkString(",")}")
    Ok(Json.toJson(infos))
  }

  def getFriendRequestsBySender(senderId: Id[User]) = Action { request =>
    val requests = db.readOnlyReplica(2) { implicit s =>
      friendRequestRepo.getBySender(senderId)
    }
    Ok(JsArray(requests.map { x => Json.toJson(x) }))
  }

  def setUserValue(userId: Id[User], key: UserValueName) = SafeAsyncAction(parse.tolerantJson) { request =>
    val value = request.body.as[String]
    db.readWrite(attempts = 3) { implicit session => userValueRepo.setValue(userId, key, value) }
    Ok
  }

  def getUserValue(userId: Id[User], key: UserValueName) = SafeAsyncAction { request =>
    val value = db.readOnlyMaster { implicit session => userValueRepo.getValueStringOpt(userId, key) } //using cache
    Ok(Json.toJson(value))
  }

  def getUserSegment(userId: Id[User]) = SafeAsyncAction { request =>
    val segment = userCommander.getUserSegment(userId)
    Ok(Json.toJson(segment))
  }

  def getExtensionVersion(installationId: ExternalId[KifiInstallation]) = SafeAsyncAction { request =>
    val version = db.readOnlyReplica(2) { implicit session => kifiInstallationRepo.get(installationId).version.toString }
    Ok(JsString(version))
  }

  def triggerRawKeepImport() = Action { request =>
    rawKeepImporterPlugin.processKeeps(broadcastToOthers = false)
    Status(202)("0")
  }

  def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]) = Action.async { request =>
    val socialUserInfo = db.readOnlyReplica { implicit session =>
      socialUserInfoRepo.get(socialUserInfoId)
    }
    socialGraphPlugin.asyncFetch(socialUserInfo, broadcastToOthers = false).map { _ =>
      Ok("0")
    }
  }

  def updateURIRestriction() = SafeAsyncAction(parse.tolerantJson) { request =>
    val uriId = Json.fromJson[Id[NormalizedURI]](request.body \ "uriId").get
    val r = request.body \ "restriction" match {
      case JsNull => None
      case x => Some(Json.fromJson[Restriction](x).get)
    }
    db.readWrite { implicit s =>
      normUriRepo.updateURIRestriction(uriId, r)
    }
    Ok
  }

  def getUserImageUrl(id: Long, width: Int) = Action.async { request =>
    userCommander.getUserImageUrl(Id[User](id), width).map { url =>
      Ok(Json.toJson(url))
    }
  }

  def getLapsedUsersForDelighted(maxCount: Int, skipCount: Int, after: DateTime, before: Option[DateTime]) = Action { request =>
    val userInfos = db.readOnlyReplica { implicit session =>
      userRepo.getUsers(userValueRepo.getLastActive(after, before, maxCount, skipCount)) map {
        case (userId, user) =>
          DelightedUserRegistrationInfo(userId, user.externalId, user.primaryEmail, user.fullName)
      }
    }
    Ok(Json.toJson(userInfos))
  }
}
