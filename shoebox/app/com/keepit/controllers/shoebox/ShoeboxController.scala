package com.keepit.controllers.shoebox

import com.google.inject.Inject
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.LocalPostOffice
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.serializer._
import com.keepit.shoebox.BrowsingHistoryTracker
import com.keepit.model.NormalizedURI
import com.keepit.common.mail.LocalPostOffice
import play.api.libs.json.Json
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.model.NormalizedURIRepo
import com.keepit.model.PhraseRepo
import com.keepit.shoebox.ClickHistoryTracker
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.model.BrowsingHistoryRepo
import com.keepit.model.User
import com.keepit.search.MultiHashFilter
import com.keepit.model.BrowsingHistory
import com.keepit.model.ClickHistoryRepo
import com.keepit.model.ClickHistory
import com.keepit.common.mail.LocalPostOffice
import play.api.libs.json.Json
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.search.SearchConfigExperimentRepo
import com.keepit.model.UserExperimentRepo
import com.keepit.serializer.SearchConfigExperimentSerializer
import com.keepit.search.SearchConfigExperiment
import com.keepit.common.db.State
import com.keepit.model.ExperimentType
import com.keepit.common.db.SequenceNumber
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.ext.PersonalSearchHit
import com.keepit.common.social.BasicUser
import com.keepit.common.db.ExternalId

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
  clickingHistoryRepo: ClickHistoryRepo,
  clickHistoryTracker: ClickHistoryTracker,
  normUriRepo: NormalizedURIRepo,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  userExperimentRepo: UserExperimentRepo,
  persistEventPlugin: PersistEventPlugin,
  postOffice: LocalPostOffice,
  healthcheckPlugin: HealthcheckPlugin,
  phraseRepo: PhraseRepo,
  collectionRepo: CollectionRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  basicUserRepo: BasicUserRepo)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices
)
  extends ShoeboxServiceController with Logging {

  def getUserOpt(id: ExternalId[User]) = Action { request =>
    val userOpt =  db.readOnly { implicit s => userRepo.getOpt(id) }
    userOpt match {
      case Some(user) => Ok(UserSerializer.userSerializer.writes(user))
      case None => Ok(JsNull)
    }
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
    Ok(NormalizedURISerializer.normalizedURISerializer.writes(uri))
  }

  def getNormalizedURIs(ids: String) = Action { request =>
     val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
     val uris = db.readOnly { implicit s =>
       uriIds.map{ id =>
         val uri = normUriRepo.get(id)
         NormalizedURISerializer.normalizedURISerializer.writes(uri)
       }
     }
     Ok(JsArray(uris))
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
  
  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = Action { request =>
    val bookmark = db.readOnly { implicit session =>
      bookmarkRepo.getByUriAndUser(uriId, userId)
    }.map(Json.toJson(_)).getOrElse(JsNull)
    Ok(bookmark)
  }
  
  def getPersonalSearchInfo(userId: Id[User], allUsers: String, formattedHits: String) = Action { request =>
    val (users, personalSearchHits) = db.readOnly { implicit session =>
      val neededUsers = (allUsers.split(",").filterNot(_.isEmpty).map { u =>
        val user = Id[User](u.toLong)
        user.toString -> Json.toJson(basicUserRepo.load(user))
      }).toSeq
      val personalSearchHits = formattedHits.split(",").filterNot(_.isEmpty).map { hit =>
        val param = hit.split(":").toSeq
        val isMyBookmark = param.head == "1"
        val uriId = Id[NormalizedURI](param.tail.head.toLong)
        val uri = normUriRepo.get(uriId)
        
        (if(isMyBookmark) bookmarkRepo.getByUriAndUser(uriId, userId) else None) match {
          case Some(bmk) =>
            PersonalSearchHit(uri.id.get, uri.externalId, bmk.title, bmk.url, bmk.isPrivate)
          case None =>
            val uri = normUriRepo.get(uriId)
            PersonalSearchHit(uri.id.get, uri.externalId, uri.title, uri.url, false)
        }
      }
      (neededUsers, personalSearchHits)
    }

    Ok(Json.obj("users" -> JsObject(users), "personalSearchHits" -> personalSearchHits))
  }

  def getUsersChanged(seqNum: Long) = Action { request =>
    val changed = db.readOnly { implicit s =>
      bookmarkRepo.getUsersChanged(SequenceNumber(seqNum))
    } map{ case(userId, seqNum) =>
      Json.obj( "id" -> userId.id, "seqNum" -> seqNum.value)
    }
    Ok(JsArray(changed))
  }

  def persistServerSearchEvent() = Action(parse.json) { request =>
    val metaData = request.body
    persistEventPlugin.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData.as[JsObject]))
    Ok("server search event persisted")
  }

  def getUsers(ids: String) = Action { request =>
    val userIds = ids.split(',').map(id => Id[User](id.toLong))
    val users = db.readOnly { implicit s =>
      userIds.map{userId =>
        val user = userRepo.get(userId)
        UserSerializer.userSerializer.writes(user)
      }
    }
    Ok(JsArray(users))
  }
  
  def getUserIdsByExternalIds(ids: String) = Action { request =>
    val extUserIds = ids.split(',').map(id => ExternalId[User](id))
    val users = (db.readOnly { implicit s =>
      extUserIds.map { userId =>
        userRepo.getOpt(userId).map(_.id.get.id)
      } flatten
    })
    Ok(Json.toJson(users))
  }

  def getConnectedUsers(id : Id[User]) = Action { request =>
    val ids = db.readOnly { implicit s =>
      userConnectionRepo.getConnectedUsers(id).toSeq
        .map { friendId => JsNumber(friendId.id) }
    }
    Ok(JsArray(ids))
  }

  def getActiveExperiments = Action { request =>
    val exp = db.readOnly { implicit s => searchConfigExperimentRepo.getActive() }.map {
      SearchConfigExperimentSerializer.serializer.writes(_)
    }
    Ok(JsArray(exp))
  }

  def getExperiments = Action { request =>
    val exp = db.readOnly { implicit s => searchConfigExperimentRepo.getNotInactive() }.map {
      SearchConfigExperimentSerializer.serializer.writes(_)
    }
    Ok(JsArray(exp))
  }

  def getExperiment(id: Id[SearchConfigExperiment]) = Action{ request =>
    val exp = db.readOnly { implicit s => searchConfigExperimentRepo.get(id) }
    Ok( SearchConfigExperimentSerializer.serializer.writes(exp))
  }

  def saveExperiment = Action(parse.json) { request =>
    val exp = SearchConfigExperimentSerializer.serializer.reads(request.body).get
    val saved = db.readWrite { implicit s => searchConfigExperimentRepo.save(exp) }
    Ok(SearchConfigExperimentSerializer.serializer.writes(saved))
  }

  def hasExperiment(userId: Id[User], state: State[ExperimentType]) = Action { request =>
     val has = db.readOnly { implicit s =>
          userExperimentRepo.hasExperiment(userId, state)
    }
    Ok(JsBoolean(has))
  }

  def getPhrasesByPage(page: Int, size: Int) = Action { request =>
    val phrases = db.readOnly { implicit s =>
      phraseRepo.page(page,size).map(PhraseSerializer.phraseSerializer.writes(_))
    }

    Ok(JsArray(phrases))
  }

  def getCollectionsByUser(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(db.readOnly { implicit s => collectionRepo.getByUser(userId).map(_.id.get.id) }))
  }

  def getCollectionsChanged(seqNum: Long) = Action { request =>
    import ShoeboxController.collectionTupleFormat
    Ok(Json.toJson(db.readOnly { implicit s => collectionRepo.getCollectionsChanged(SequenceNumber(seqNum)) }))
  }

  def getBookmarksInCollection(collectionId: Id[Collection]) = Action { request =>
    Ok(Json.toJson(db.readOnly { implicit s =>
      keepToCollectionRepo.getBookmarksInCollection(collectionId) map bookmarkRepo.get
    }))
  }


  def getIndexable(seqNum: Long, fetchSize: Int) = Action { request =>
    val uris = db.readOnly { implicit s =>
        normUriRepo.getIndexable(SequenceNumber(seqNum), fetchSize)
      }.map{uri => NormalizedURISerializer.normalizedURISerializer.writes(uri)}
    Ok(JsArray(uris))
  }

}
