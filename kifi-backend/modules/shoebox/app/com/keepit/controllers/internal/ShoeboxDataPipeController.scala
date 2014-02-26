package com.keepit.controllers.internal

import com.keepit.common.db.slick.Database.Slave
import com.keepit.common.db.{Id, SequenceNumber}
import play.api.libs.json.{JsNumber, JsObject, JsArray, Json}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.mvc.Action
import com.keepit.model._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import scala.concurrent.Future
import com.keepit.common.routes.Shoebox

class ShoeboxDataPipeController @Inject() (
    db: Database,
    userRepo: UserRepo,
    normUriRepo: NormalizedURIRepo,
    collectionRepo: CollectionRepo,
    bookmarkRepo: BookmarkRepo,
    changedUriRepo: ChangedURIRepo,
    phraseRepo: PhraseRepo,
    userConnRepo: UserConnectionRepo,
    searchFriendRepo: SearchFriendRepo
  ) extends ShoeboxServiceController with Logging {

  def getIndexable(seqNum: Long, fetchSize: Int) = Action { request =>
    val uris = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getIndexable(SequenceNumber(seqNum), fetchSize)
    }
    Ok(Json.toJson(uris))
  }

  def getIndexableUris(seqNum: Long, fetchSize: Int) = Action { request =>
    val uris = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getIndexable(SequenceNumber(seqNum), fetchSize)
    }
    val indexables = uris map { u => IndexableUri(u) }
    Ok(Json.toJson(indexables))
  }

  def getScrapedUris(seqNum: Long, fetchSize: Int) = Action { request =>
    val scrapedStates = Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_WANTED, NormalizedURIStates.SCRAPE_FAILED, NormalizedURIStates.UNSCRAPABLE)
    val uris = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getChanged(SequenceNumber(seqNum), includeStates = scrapedStates,  limit = fetchSize)
    }
    val indexables = uris map { u => IndexableUri(u) }
    Ok(Json.toJson(indexables))
  }

  def getHighestUriSeq() = Action { request =>
    val seq = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getCurrentSeqNum()
    }
    Ok(Json.toJson(seq.value))
  }

  def getCollectionsChanged(seqNum: Long, fetchSize: Int) = Action { request =>
    Ok(Json.toJson(db.readOnly(2, Slave) { implicit s =>
      collectionRepo.getCollectionsChanged(SequenceNumber(seqNum), fetchSize)
    }))
  }

  def getPhrasesChanged(seqNum: Long, fetchSize: Int) = Action { request =>
    val phrases = db.readOnly(2, Slave) { implicit s =>
      phraseRepo.getPhrasesChanged(SequenceNumber(seqNum), fetchSize)
    }
    Ok(Json.toJson(phrases))
  }

  def getBookmarksChanged(seqNum: Long, fetchSize: Int) = Action { request =>
    val bookmarks = db.readOnly(2, Slave) { implicit session =>
      bookmarkRepo.getBookmarksChanged(SequenceNumber(seqNum), fetchSize)
    }
    Ok(Json.toJson(bookmarks))
  }

  def getUserIndexable(seqNum: Long, fetchSize: Int) = Action { request =>
    val users = db.readOnly(2, Slave) { implicit s =>
      userRepo.getUsersSince(SequenceNumber(seqNum), fetchSize)
    }
    Ok(JsArray(users.map{ u => Json.toJson(u)}))
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber, highSeq: SequenceNumber) = Action { request =>
    val changes = db.readOnly(2, Slave) { implicit s =>
      changedUriRepo.getChangesBetween(lowSeq, highSeq).map{ change =>
        (change.oldUriId, normUriRepo.get(change.newUriId))
      }
    }
    val jsChanges = changes.map{ case (id, uri) =>
      JsObject(List("id" -> JsNumber(id.id), "uri" -> Json.toJson(uri)))
    }
    Ok(JsArray(jsChanges))
  }

  def getUserConnectionsChanged(seqNum: Long, fetchSize: Int) = Action { request =>
    val changes = db.readOnly(2, Slave) { implicit s =>
      userConnRepo.getUserConnectionChanged(SequenceNumber(seqNum), fetchSize)
    }
    Ok(Json.toJson(changes))
  }

  def getSearchFriendsChanged(seqNum: Long, fetchSize: Int) = Action { request =>
    val changes = db.readOnly(2, Slave){ implicit s =>
      searchFriendRepo.getSearchFriendsChanged(SequenceNumber(seqNum), fetchSize)
    }
    Ok(Json.toJson(changes))
  }
}
