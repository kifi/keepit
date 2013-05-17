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
import play.api.libs.json.JsValue
import play.api.libs.json.JsArray
import com.keepit.model.BrowsingHistoryRepo
import com.keepit.model.User
import com.keepit.search.MultiHashFilter
import play.api.libs.json.JsNull
import com.keepit.model.BrowsingHistory

class ShoeboxController @Inject() (
  db: Database,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  browsingHistoryRepo: BrowsingHistoryRepo,
  normUriRepo: NormalizedURIRepo)
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

}
