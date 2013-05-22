package com.keepit.controllers.shoebox

import com.keepit.common.controller.ShoeboxServiceController
import com.google.inject.Inject
import com.keepit.model.UserRepo
import com.keepit.model.BookmarkRepo
import com.keepit.model.NormalizedURIRepo
import com.keepit.common.db.slick.Database
import com.keepit.model.UserConnectionRepo
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.serializer._
import play.api.mvc.Action
import play.api.libs.json.{JsNumber, JsValue, JsArray, JsNull}
import com.keepit.model.BrowsingHistoryRepo
import com.keepit.model.User
import com.keepit.search.MultiHashFilter
import com.keepit.model.BrowsingHistory
import com.keepit.model.ClickHistoryRepo
import com.keepit.model.ClickHistory
import com.keepit.common.db.SequenceNumber
import play.api.libs.json._
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.analytics.Events
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.shoebox.ClickHistoryTracker
import com.keepit.shoebox.BrowsingHistoryTracker
import com.keepit.model.NormalizedURI

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
  persistEventPlugin: PersistEventPlugin)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices
)
  extends ShoeboxServiceController with Logging {

  def getNormalizedURI(id: Long) = Action {
    val uri = db.readOnly { implicit s =>
      normUriRepo.get(Id[NormalizedURI](id))
    }
    Ok(NormalizedURISerializer.normalizedURISerializer.writes(uri))
  }

  def getNormalizedURIs = Action(parse.json) { request =>
     val json = request.body
     val ids = json.as[JsArray].value.map( id => id.as[Long] )
     val uris = db.readOnly { implicit s =>
       ids.map{ id =>
         val uri = normUriRepo.get(Id[NormalizedURI](id))
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
  def getUsers = Action(parse.json) { request =>
        val json = request.body
        val userIds = json.as[JsArray].value.map(id => Id[User](id.as[Long]))
        val users = db.readOnly { implicit s =>
          userIds.map{userId =>
            val user = userRepo.get(userId)
            UserSerializer.userSerializer.writes(user)
          }
        }
        Ok(JsArray(users))
  }

  def getConnectedUsers(id : Long) = Action { request =>
    val ids = db.readOnly { implicit s =>
      userConnectionRepo.getConnectedUsers(Id[User](id)).toSeq
        .map { friendId => JsNumber(friendId.id) }
    }
    Ok(JsArray(ids))
  }

}
