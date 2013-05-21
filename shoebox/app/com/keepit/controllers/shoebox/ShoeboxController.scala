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

class ShoeboxController @Inject() (
  db: Database,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  browsingHistoryRepo: BrowsingHistoryRepo,
  clickingHistoryRepo: ClickHistoryRepo,
  normUriRepo: NormalizedURIRepo)
  extends ShoeboxServiceController with Logging {
  
  def sendMail = Action(parse.json) { request =>
    Ok("true")
  }

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

  private def getMultiHashFilter(userId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int) = {
    db.readOnly { implicit session =>
      browsingHistoryRepo.getByUserId(Id[User](userId)) match {
        case Some(browsingHistory) =>
          new MultiHashFilter(browsingHistory.tableSize, browsingHistory.filter, browsingHistory.numHashFuncs, browsingHistory.minHits)
        case None =>
          val filter = MultiHashFilter(tableSize, numHashFuncs, minHits)
          filter
      }
    }
  }

  def addBrowsingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int) = Action { request =>
    val filter = getMultiHashFilter(userId, tableSize, numHashFuncs, minHits)
    filter.put(uriId)

    db.readWrite { implicit session =>
      browsingHistoryRepo.save(browsingHistoryRepo.getByUserId(Id[User](userId)) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          BrowsingHistory(userId = Id[User](userId), tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }

    Ok("browsing history added")
  }

  private def getMultiHashFilterForClickingHistory(userId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int) = {
    db.readOnly { implicit session =>
      clickingHistoryRepo.getByUserId(Id[User](userId)) match {
        case Some(clickingHistory) =>
          new MultiHashFilter(clickingHistory.tableSize, clickingHistory.filter, clickingHistory.numHashFuncs, clickingHistory.minHits)
        case None =>
          val filter = MultiHashFilter(tableSize, numHashFuncs, minHits)
          filter
      }
    }
  }

  def addClickingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int) = Action { request =>
    val filter = getMultiHashFilterForClickingHistory(userId, tableSize, numHashFuncs, minHits)
    filter.put(uriId)

    db.readWrite { implicit session =>
      clickingHistoryRepo.save(clickingHistoryRepo.getByUserId(Id[User](userId)) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          ClickHistory(userId = Id[User](userId), tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }
    Ok("clicking history added")
  }

  // this is not quite right yet (need new bookmark serializer).
  // This function is not used yet, just a placeholder.
  def getBookmarks(userId: Long) = Action { request =>
    val bookmarks = db.readOnly { implicit session =>
      bookmarkRepo.getByUser(Id[User](userId))
    }.map{BookmarkSerializer.bookmarkSerializer.writes}

    Ok(JsArray(bookmarks))
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
