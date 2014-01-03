package com.keepit.controllers.internal

import com.keepit.common.db.slick.Database.Slave
import com.keepit.common.db.SequenceNumber
import play.api.libs.json.{JsArray, Json}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.mvc.Action
import com.keepit.model._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import play.api.libs.json.JsArray

class ShoeboxDataPipeController @Inject() (
    db: Database,
    userRepo: UserRepo,
    normUriRepo: NormalizedURIRepo,
    collectionRepo: CollectionRepo,
    bookmarkRepo: BookmarkRepo,
    phraseRepo: PhraseRepo
  ) extends ShoeboxServiceController with Logging {

  def getIndexable(seqNum: Long, fetchSize: Int) = Action { request =>
    val uris = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getIndexable(SequenceNumber(seqNum), fetchSize)
    }
    //todo(eishay): need to have a dedicated serializer for those
    Ok(Json.toJson(uris))
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

}
