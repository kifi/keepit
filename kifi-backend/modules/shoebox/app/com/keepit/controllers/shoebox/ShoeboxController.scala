package com.keepit.controllers.shoebox

import com.google.inject.{Provider, Inject}
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.EventPersister
import com.keepit.common.analytics.Events
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.LocalPostOffice
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer.{TrustedCandidate, NormalizationService, NormalizationCandidate}
import com.keepit.search.SearchConfigExperiment
import com.keepit.search.SearchConfigExperimentRepo
import com.keepit.common.akka.SafeFuture

import scala.concurrent.future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.social.{SocialNetworkType, SocialId}
import com.keepit.scraper.HttpRedirect

object ShoeboxController {
  implicit val collectionTupleFormat = (
    (__ \ 'collId).format(Id.format[Collection]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'seq).format(SequenceNumber.sequenceNumberFormat)
  ).tupled
}

class ShoeboxController @Inject() (
  db: Database,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  commentRecipientRepo: CommentRecipientRepo,
  normUriRepo: NormalizedURIRepo,
  normalizationServiceProvider:Provider[NormalizationService],
  urlPatternRuleRepo: UrlPatternRuleRepo,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  userExperimentRepo: UserExperimentRepo,
  EventPersister: EventPersister,
  postOffice: LocalPostOffice,
  airbrake: AirbrakeNotifier,
  phraseRepo: PhraseRepo,
  collectionRepo: CollectionRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  basicUserRepo: BasicUserRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  sessionRepo: UserSessionRepo,
  searchFriendRepo: SearchFriendRepo,
  emailAddressRepo: EmailAddressRepo,
  changedUriRepo: ChangedURIRepo,
  userBookmarkClicksRepo: UserBookmarkClicksRepo,
  scrapeInfoRepo:ScrapeInfoRepo
)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices
)
  extends ShoeboxServiceController with Logging {

  def getUserOpt(id: ExternalId[User]) = Action { request =>
    val userOpt =  db.readOnly { implicit s => userRepo.getOpt(id) }
    userOpt match {
      case Some(user) => Ok(Json.toJson(user))
      case None => Ok(JsNull)
    }
  }

  def getSocialUserInfoByNetworkAndSocialId(id: String, networkType: String) = Action {
    val socialId = SocialId(id)
    val network = SocialNetworkType(networkType)
    val sui = db.readOnly { implicit session =>
      socialUserInfoRepo.get(socialId, network)
    }
    Ok(Json.toJson(sui))
  }

  def getSocialUserInfosByUserId(userId: Id[User]) = Action {
    val sui = db.readOnly { implicit session =>
      socialUserInfoRepo.getByUser(userId)
    }
    Ok(Json.toJson(sui))
  }

  def sendMail = Action(parse.json) { request =>
    Json.fromJson[ElectronicMail](request.body).asOpt match {
      case Some(mail) =>
        db.readWrite { implicit session =>
          postOffice.sendMail(mail)
        }
        Ok("true")
      case None =>
        val e = new Exception("Unable to parse email")
        airbrake.notify(AirbrakeError(exception = e, message = Some(s"Unable to parse: ${request.body.toString}")))
        Ok("false")
    }
  }

  def sendMailToUser = Action(parse.json) { request =>
    val userId = Id[User]((request.body \ "user").as[Long])
    val email = (request.body \ "email").as[ElectronicMail]

    val addrs = db.readOnly{ implicit session => emailAddressRepo.getByUser(userId) }
    for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
      db.readWrite{ implicit session => postOffice.sendMail(email.copy(to=List(addr))) }
    }
    Ok("true")
  }

  def getNormalizedURI(id: Long) = SafeAsyncAction {
    val uri = db.readOnly { implicit s =>
      normUriRepo.get(Id[NormalizedURI](id))
    }
    Ok(Json.toJson(uri))
  }

  def saveNormalizedURI() = SafeAsyncAction(parse.json) { request =>
    log.info(s"[saveNormalizedURI] body=${Json.prettyPrint(request.body)}")
    val normalizedUri = request.body.as[NormalizedURI]
    val saved = db.readWrite { implicit s =>
      normUriRepo.save(normalizedUri)
    }
    log.info(s"[saveNormalizedURI(${normalizedUri.url})] result=$saved")
    Ok(Json.toJson(saved))
  }

  def recordPermanentRedirect() = SafeAsyncAction(parse.json) { request =>
    log.info(s"[recordPermanentRedirect] body=${Json.prettyPrint(request.body)}")
    val args = request.body.as[JsArray].value
    require((!args.isEmpty && args.length == 2), "Both uri and redirect need to be supplied")
    val uri = args(0).as[NormalizedURI]
    val redirect = args(1).as[HttpRedirect]
    require(redirect.isPermanent, "HTTP redirect is not permanent.")
    require(redirect.isLocatedAt(uri.url), "Current Location of HTTP redirect does not match normalized Uri.")
    val toBeRedirected = db.readWrite { implicit session =>
      for {
        candidateUri <- normUriRepo.getByUri(redirect.newDestination)
        normalization <- candidateUri.normalization
      } yield {
        val toBeRedirected = uri.withNormalization(Normalization.MOVED)
        // session.onTransactionSuccess(normalizationServiceProvider.get.update(toBeRedirected, TrustedCandidate(candidateUri.url, normalization)))
        normalizationServiceProvider.get.update(toBeRedirected, TrustedCandidate(candidateUri.url, normalization)) // TODO:revisit
        toBeRedirected
      }
    }
    val res = toBeRedirected getOrElse uri
    log.info(s"[recordPermanentRedirect($uri, $redirect)] result=$res")
    Ok(Json.toJson(res))
  }

  def getProxy(url:String) = SafeAsyncAction { request =>
    val httpProxyOpt = db.readOnly { implicit session =>
      urlPatternRuleRepo.getProxy(url)
    }
    log.info(s"[getProxy($url): result=$httpProxyOpt")
    Ok(Json.toJson(httpProxyOpt))
  }

  def isUnscrapable(url: String, destinationUrl: Option[String]) = SafeAsyncAction { request =>
    val res = db.readOnly { implicit s =>
      (urlPatternRuleRepo.isUnscrapable(url) || (destinationUrl.isDefined && urlPatternRuleRepo.isUnscrapable(destinationUrl.get)))
    }
    log.info(s"[isUnscrapable($url, $destinationUrl)] result=$res")
    Ok(JsBoolean(res))
  }

  def getNormalizedURIs(ids: String) = SafeAsyncAction { request =>
    val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
    val uris = db.readOnly { implicit s => uriIds map normUriRepo.get }
    Ok(Json.toJson(uris))
  }

  def getNormalizedURIByURL() = SafeAsyncAction(parse.json) { request =>
    val url : String = Json.fromJson[String](request.body).get
    val uriOpt = db.readOnly { implicit s =>
      normUriRepo.getByUri(url)
    }
    uriOpt match {
      case Some(uri) => Ok(Json.toJson(uri))
      case None => Ok(JsNull)
    }
  }

  def getNormalizedUriByUrlOrPrenormalize() = SafeAsyncAction(parse.json) { request =>
    val url = Json.fromJson[String](request.body).get
    val normalizedUriOrPrenormStr = db.readOnly { implicit s =>
      normUriRepo.getByUriOrPrenormalize(url) match {
        case Right(url) => Json.obj("url" -> url)
        case Left(nuri) => Json.obj("normalizedURI" -> nuri)
      }
    }
    Ok(normalizedUriOrPrenormStr)
  }

  def internNormalizedURI() = SafeAsyncAction(parse.json) { request =>
    val o = request.body.as[JsObject]
    val url = (o \ "url").as[String]
    val uriId = db.readWrite(attempts=2) { implicit s =>
      normUriRepo.internByUri(url, NormalizationCandidate(o): _*)
    }
    Ok(Json.toJson(uriId))
  }

  def getScrapeInfo() = SafeAsyncAction(parse.json) { request =>
    log.info(s"[getScrapeInfo] body=${Json.prettyPrint(request.body)}")
    val json = request.body
    val uri = json.as[NormalizedURI]
    val info = db.readWrite { implicit s =>
      scrapeInfoRepo.getByUri(uri.id.get).getOrElse(scrapeInfoRepo.save(ScrapeInfo(uriId = uri.id.get)))
    }
    log.info(s"[getScrapeInfo(${uri.url})] result=$info")
    Ok(Json.toJson(info))
  }

  def saveScrapeInfo() = SafeAsyncAction(parse.json) { request =>
    log.info(s"[saveScrapeInfo] body=${Json.prettyPrint(request.body)}")
    val json = request.body
    val info = json.as[ScrapeInfo]
    val saved = db.readWrite( { implicit s =>
      scrapeInfoRepo.save(info)
    })
    log.info(s"[saveScrapeInfo] result=$saved")
    Ok(Json.toJson(saved))
  }

  def getBookmarks(userId: Id[User]) = Action { request =>
    val bookmarks = db.readOnly { implicit session =>
      bookmarkRepo.getByUser(userId)
    }
    Ok(Json.toJson(bookmarks))
  }

  def getBookmarksChanged(seqNum: Long, fetchSize: Int) = Action { request =>
    val bookmarks = db.readOnly { implicit session =>
      bookmarkRepo.getBookmarksChanged(SequenceNumber(seqNum), fetchSize)
    }
    Ok(Json.toJson(bookmarks))
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = Action { request =>
    val bookmark = db.readOnly { implicit session =>
      bookmarkRepo.getByUriAndUser(uriId, userId)
    }.map(Json.toJson(_)).getOrElse(JsNull)
    Ok(bookmark)
  }

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]) = Action { request =>
    val bookmarks = db.readOnly { implicit session =>
      bookmarkRepo.getByUriWithoutTitle(uriId)
    }
    val res = Json.toJson(bookmarks)
    log.info(s"[getBookmarksByUriWithoutTitle($uriId)] ${bookmarks} json=$res")
    Ok(res)
  }

  def getLatestBookmark(uriId: Id[NormalizedURI]) = Action { request =>
    val bookmarkOpt = db.readOnly { implicit session =>
      bookmarkRepo.latestBookmark(uriId)
    }
    log.info(s"[getLatestBookmark($uriId)] $bookmarkOpt")
    Ok(Json.toJson(bookmarkOpt))
  }

  def saveBookmark() = Action(parse.json) { request =>
    val bookmark = request.body.as[Bookmark]
    val saved = db.readWrite { implicit session =>
      bookmarkRepo.save(bookmark)
    }
    log.info(s"[saveBookmark] saved=$saved")
    Ok(Json.toJson(saved))
  }

  def getCommentRecipientIds(commentId: Id[Comment]) = Action { request =>
    val commentRecipientIds = db.readOnly { implicit session =>
      commentRecipientRepo.getByComment(commentId).filter(_.state == CommentRecipientStates.ACTIVE).flatMap(_.userId.map(_.id))
    }
    Ok(Json.toJson(commentRecipientIds))
  }

  def persistServerSearchEvent() = Action(parse.json) { request =>
    val metaData = request.body
    EventPersister.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData.as[JsObject]))
    Ok("server search event persisted")
  }

  def getUsers(ids: String) = Action { request =>
    val userIds = ids.split(',').map(id => Id[User](id.toLong))
    val users = db.readOnly { implicit s => userIds map userRepo.get }
    Ok(Json.toJson(users))
  }

  def getUserIdsByExternalIds(ids: String) = Action { request =>
    val extUserIds = ids.split(',').map(_.trim).filterNot(_.isEmpty).map(ExternalId[User](_))
    val users = db.readOnly { implicit s =>
      extUserIds.map { userRepo.getOpt(_).map(_.id.get.id) }.flatten
    }
    Ok(Json.toJson(users))
  }

  def getBasicUsers(ids: String) = Action { request =>
    val userIds = ids.split(',').map(_.trim).filterNot(_.isEmpty).map(id => Id[User](id.toLong))
    val users = db.readOnly { implicit s =>
      userIds.map{ userId => userId.id.toString -> Json.toJson(basicUserRepo.load(userId)) }.toMap
    }
    Ok(Json.toJson(users))
  }
  
  def getUserIndexable(seqNum: Long, fetchSize: Int) = Action { request =>
    val users = db.readOnly { implicit s => userRepo.getUsersSince(SequenceNumber(seqNum), fetchSize) }
    Ok(JsArray(users.map{ u => Json.toJson(u)}))
  }
  
  def getEmailsForUsers(ids: String) = Action { request =>
    val userIds = ids.split(',').map(_.trim).filterNot(_.isEmpty).map(id => Id[User](id.toLong))
    val emails = db.readOnly{ implicit s =>
      userIds.map{userId => userId.id.toString -> emailAddressRepo.getByUser(userId).map{_.address}}.toMap
    }
    Ok(Json.toJson(emails))
  }

  def getCollectionIdsByExternalIds(ids: String) = Action { request =>
    val extCollIds = ids.split(',').map(_.trim).filterNot(_.isEmpty).map(ExternalId[Collection](_))
    val collectionIds = db.readOnly { implicit s =>
      extCollIds.map { collectionRepo.getOpt(_).map(_.id.get.id) }.flatten
    }
    Ok(Json.toJson(collectionIds))
  }

  def getConnectedUsers(id : Id[User]) = Action { request =>
    val ids = db.readOnly { implicit s =>
      userConnectionRepo.getConnectedUsers(id).toSeq
        .map { friendId => JsNumber(friendId.id) }
    }
    Ok(JsArray(ids))
  }

  def getActiveExperiments = Action { request =>
    val exp = db.readOnly { implicit s => searchConfigExperimentRepo.getActive() }
    Ok(Json.toJson(exp))
  }

  def getExperiments = Action { request =>
    val exp = db.readOnly { implicit s => searchConfigExperimentRepo.getNotInactive() }
    Ok(Json.toJson(exp))
  }

  def getExperiment(id: Id[SearchConfigExperiment]) = Action{ request =>
    val exp = db.readOnly { implicit s => searchConfigExperimentRepo.get(id) }
    Ok(Json.toJson(exp))
  }

  def saveExperiment = Action(parse.json) { request =>
    val exp = Json.fromJson[SearchConfigExperiment](request.body).get
    val saved = db.readWrite { implicit s => searchConfigExperimentRepo.save(exp) }
    Ok(Json.toJson(saved))
  }

  def getUserExperiments(userId: Id[User]) = Action { request =>
    val experiments = db.readOnly { implicit s =>
      userExperimentRepo.getUserExperiments(userId).map(_.value)
    }
    Ok(Json.toJson(experiments))
  }

  def getPhrasesByPage(page: Int, size: Int) = Action { request =>
    val phrases = db.readOnly { implicit s => phraseRepo.page(page,size) }
    Ok(Json.toJson(phrases))
  }

  def getCollectionsByUser(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(db.readOnly { implicit s => collectionRepo.getByUser(userId) }))
  }

  def getCollectionsChangedDeprecated(seqNum: Long, fetchSize: Int) = Action { request =>
    import ShoeboxController.collectionTupleFormat
    Ok(Json.toJson(db.readOnly { implicit s =>
      collectionRepo.getCollectionsChanged(SequenceNumber(seqNum), fetchSize).map{ c => (c.id.get, c.userId, c.seq) }
    }))
  }

  def getCollectionsChanged(seqNum: Long, fetchSize: Int) = Action { request =>
    import ShoeboxController.collectionTupleFormat
    Ok(Json.toJson(db.readOnly { implicit s => collectionRepo.getCollectionsChanged(SequenceNumber(seqNum), fetchSize) }))
  }

  def getBookmarksInCollection(collectionId: Id[Collection]) = Action { request =>
    Ok(Json.toJson(db.readOnly { implicit s =>
      keepToCollectionRepo.getBookmarksInCollection(collectionId) map bookmarkRepo.get
    }))
  }


  def getIndexable(seqNum: Long, fetchSize: Int) = Action { request =>
    val uris = db.readOnly { implicit s => normUriRepo.getIndexable(SequenceNumber(seqNum), fetchSize) }
    Ok(Json.toJson(uris))
  }

  def getSessionByExternalId(sessionId: ExternalId[UserSession]) = Action { request =>
    val res = db.readOnly { implicit session =>
      sessionRepo.getOpt(sessionId)
    }
    Ok(Json.toJson(res))
  }

  def searchFriends(userId: Id[User]) = Action { request =>
    db.readOnly { implicit s =>
      Ok(Json.toJson(searchFriendRepo.getSearchFriends(userId).map(_.id)))
    }
  }

  def getNormalizedUriUpdates(lowSeq: Long, highSeq: Long) = Action { request =>
    val changes = db.readOnly { implicit s =>
      changedUriRepo.getChangesBetween(SequenceNumber(lowSeq), SequenceNumber(highSeq)).map{ change =>
        (change.oldUriId, normUriRepo.get(change.newUriId))
      }
    }
    val jsChanges = changes.map{ case (id, uri) =>
      JsObject(List("id" -> JsNumber(id.id), "uri" -> Json.toJson(uri)))
    }
    Ok(JsArray(jsChanges))
  }

  def clickAttribution() = SafeAsyncAction(parse.json) { request =>
    val json = request.body
    val clicker = Id.format[User].reads(json \ "clicker").get
    val uriId = Id.format[NormalizedURI].reads(json \ "uriId").get
    val keepers = (json \ "keepers").as[JsArray].value.map(jsString => ExternalId[User](jsString.as[String]))
    db.readWrite { implicit session =>
      if (keepers.isEmpty) userBookmarkClicksRepo.increaseCounts(clicker, uriId, true)
      else keepers.foreach { extId => userBookmarkClicksRepo.increaseCounts(userRepo.get(extId).id.get, uriId, false) }
    }
    Ok
  }
}
