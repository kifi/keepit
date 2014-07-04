package com.keepit.controllers.internal

import com.keepit.common.db.slick.Database.Slave
import com.keepit.common.db.SequenceNumber
import play.api.libs.json.{JsNumber, JsObject, JsArray, Json}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.mvc.Action
import com.keepit.model._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import org.msgpack.ScalaMessagePack
import play.api.http.ContentTypes
import com.keepit.model.serialize.{UriIdAndSeqBatch, UriIdAndSeq}
import com.keepit.model.serialize.UriIdAndSeqBatch

class ShoeboxDataPipeController @Inject() (
    db: Database,
    userRepo: UserRepo,
    normUriRepo: NormalizedURIRepo,
    collectionRepo: CollectionRepo,
    keepRepo: KeepRepo,
    changedUriRepo: ChangedURIRepo,
    phraseRepo: PhraseRepo,
    userConnRepo: UserConnectionRepo,
    searchFriendRepo: SearchFriendRepo,
    socialConnectionRepo: SocialConnectionRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    emailAddressRepo: UserEmailAddressRepo
  ) extends ShoeboxServiceController with Logging {

  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val uris = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getIndexable(seqNum, fetchSize)
    }
    Ok(Json.toJson(uris))
  }

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val uris = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getIndexable(seqNum, fetchSize)
    }
    val indexables = uris map { u => IndexableUri(u) }
    Ok(Json.toJson(indexables))
  }

  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val scrapedStates = Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_FAILED, NormalizedURIStates.UNSCRAPABLE)
    val uris = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getChanged(seqNum, includeStates = scrapedStates,  limit = fetchSize)
    }
    val indexables = uris map { u => IndexableUri(u) }
    Ok(Json.toJson(indexables))
  }

  def getHighestUriSeq() = Action { request =>
    val seq = db.readOnly(2, Slave) { implicit s =>
      normUriRepo.getCurrentSeqNum()
    }
    Ok(SequenceNumber.format.writes(seq))
  }

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int) = Action { request =>
    Ok(Json.toJson(db.readOnly(2, Slave) { implicit s =>
      collectionRepo.getCollectionsChanged(seqNum, fetchSize)
    }))
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int) = Action { request =>
    val phrases = db.readOnly(2, Slave) { implicit s =>
      phraseRepo.getPhrasesChanged(seqNum, fetchSize)
    }
    Ok(Json.toJson(phrases))
  }

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action { request =>
    val bookmarks = db.readOnly(2, Slave) { implicit session =>
      keepRepo.getBookmarksChanged(seqNum, fetchSize)
    }
    Ok(Json.toJson(bookmarks))
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int) = Action { request =>
    val users = db.readOnly(2, Slave) { implicit s =>
      userRepo.getUsersSince(seqNum, fetchSize)
    }
    Ok(JsArray(users.map{ u => Json.toJson(u)}))
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = Action { request =>
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

  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int) = Action { request =>
    val changes = db.readOnly(2, Slave) { implicit s =>
      userConnRepo.getUserConnectionChanged(seqNum, fetchSize)
    }
    Ok(Json.toJson(changes))
  }

  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int) = Action { request =>
    val changes = db.readOnly(2, Slave){ implicit s =>
      searchFriendRepo.getSearchFriendsChanged(seqNum, fetchSize)
    }
    Ok(Json.toJson(changes))
  }

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = Action { request =>
    val indexableSocialConnections = db.readOnly(2, Slave) { implicit session =>
      socialConnectionRepo.getConnAndNetworkBySeqNumber(seqNum, fetchSize).map { case (firstUserId, secondUserId, state, seq, networkType) =>
        IndexableSocialConnection(firstUserId, secondUserId, networkType, state, seq)
      }
    }
    val json = Json.toJson(indexableSocialConnections)
    Ok(json)
  }

  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = Action { request =>
    val socialUserInfos = db.readOnly(2, Slave) { implicit session => socialUserInfoRepo.getBySequenceNumber(seqNum, fetchSize) }
    val json = Json.toJson(socialUserInfos)
    Ok(json)
  }

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = Action { request =>
    val modifiedEmails = db.readOnly(2, Slave) { implicit session => emailAddressRepo.getBySequenceNumber(SequenceNumber[UserEmailAddress](seqNum.value), fetchSize) }
    val updates = modifiedEmails.map { email =>
      EmailAccountUpdate(email.address, email.userId, email.verified, email.state == UserEmailAddressStates.INACTIVE, SequenceNumber(email.seq.value))
    }
    val json = Json.toJson(updates)
    Ok(json)
  }
}
