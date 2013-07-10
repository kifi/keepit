package com.keepit.controllers.shoebox

import com.google.inject.Inject
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.EventPersister
import com.keepit.common.analytics.Events
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.State
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.LocalPostOffice
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.ArticleSearchResultRef
import com.keepit.search.ArticleSearchResultRefRepo
import com.keepit.search.SearchConfigExperiment
import com.keepit.search.SearchConfigExperimentRepo
import com.keepit.shoebox.BrowsingHistoryTracker
import com.keepit.shoebox.ClickHistoryTracker

import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.social.{SocialNetworkType, SocialId}

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
  browsingHistoryRepo: BrowsingHistoryRepo,
  browsingHistoryTracker: BrowsingHistoryTracker,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  clickingHistoryRepo: ClickHistoryRepo,
  clickHistoryTracker: ClickHistoryTracker,
  normUriRepo: NormalizedURIRepo,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  userExperimentRepo: UserExperimentRepo,
  EventPersister: EventPersister,
  postOffice: LocalPostOffice,
  healthcheckPlugin: HealthcheckPlugin,
  phraseRepo: PhraseRepo,
  collectionRepo: CollectionRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  basicUserRepo: BasicUserRepo,
  articleSearchResultRefRepo: ArticleSearchResultRefRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  sessionRepo: UserSessionRepo)
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
        healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some("Unable to parse: " + request.body.toString)))
        Ok("false")
    }
  }

  def getNormalizedURI(id: Long) = Action {
    val uri = db.readOnly { implicit s =>
      normUriRepo.get(Id[NormalizedURI](id))
    }
    Ok(Json.toJson(uri))
  }

  def getNormalizedURIs(ids: String) = Action { request =>
     val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
     val uris = db.readOnly { implicit s => uriIds map normUriRepo.get }
     Ok(Json.toJson(uris))
  }

  def getBrowsingHistoryFilter(userId: Id[User]) = Action {
    Ok(browsingHistoryTracker.getMultiHashFilter(userId).getFilter)
  }

  def addBrowsingHistory(userId: Id[User], uriId: Id[NormalizedURI]) = Action { request =>
    browsingHistoryTracker.add(userId, uriId)
    Ok
  }

  def getClickHistoryFilter(userId: Id[User]) = Action {
     Ok(clickHistoryTracker.getMultiHashFilter(userId).getFilter)
  }

  def addClickHistory(userId: Id[User], uriId: Id[NormalizedURI]) = Action { request =>
    clickHistoryTracker.add(userId, uriId)
    Ok
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

  def getCommentsChanged(seqNum: Long, fetchSize: Int) = Action { request =>
    val comments = db.readOnly { implicit session =>
      commentRepo.getCommentsChanged(SequenceNumber(seqNum), fetchSize)
    }
    Ok(Json.toJson(comments))
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
    val userIds = ids.split(',').map(id => Id[User](id.toLong))
    val users = db.readOnly { implicit s =>
      userIds.map{ userId => userId.id.toString -> Json.toJson(basicUserRepo.load(userId)) }.toMap
    }
    Ok(Json.toJson(users))
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

  def reportArticleSearchResult = Action(parse.json) { request =>
    val ref = Json.fromJson[ArticleSearchResultRef](request.body).get
    db.readWrite { implicit s =>
      articleSearchResultRefRepo.save(ref)
    }
    Ok
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

  def hasExperiment(userId: Id[User], state: State[ExperimentType]) = Action { request =>
    val has = db.readOnly { implicit s =>
      userExperimentRepo.hasExperiment(userId, state)
    }
    Ok(JsBoolean(has))
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

}
