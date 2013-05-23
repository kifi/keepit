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
import com.keepit.common.db.SequenceNumber

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
  persistEventPlugin: PersistEventPlugin,
  postOffice: LocalPostOffice,
  collectionRepo: CollectionRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  healthcheckPlugin: HealthcheckPlugin)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices
)
  extends ShoeboxServiceController with Logging {

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

  // this is not quite right yet (need new bookmark serializer).
  // This function is not used yet, just a placeholder.
  def getBookmarks(userId: Id[User]) = Action { request =>
    val bookmarks = db.readOnly { implicit session =>
      bookmarkRepo.getByUser(userId)
    }.map{BookmarkSerializer.bookmarkSerializer.writes}

    Ok(JsArray(bookmarks))
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

  def getConnectedUsers(id : Id[User]) = Action { request =>
    val ids = db.readOnly { implicit s =>
      userConnectionRepo.getConnectedUsers(id).toSeq
        .map { friendId => JsNumber(friendId.id) }
    }
    Ok(JsArray(ids))
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
