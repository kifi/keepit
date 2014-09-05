package com.keepit.controllers.internal

import com.keepit.commanders.ShoeboxDataPipeCommander
import com.keepit.common.db.SequenceNumber
import play.api.libs.json.{ JsNumber, JsObject, JsArray, Json }
import com.google.inject.Inject
import play.api.mvc.Action
import com.keepit.model._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging

class ShoeboxDataPipeController @Inject() (
    dataPipeCommander: ShoeboxDataPipeCommander) extends ShoeboxServiceController with Logging {

  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val uris = dataPipeCommander.getIndexable(seqNum, fetchSize)
    Ok(Json.toJson(uris))
  }

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val uris = dataPipeCommander.getIndexable(seqNum, fetchSize)
    val indexables = uris map { u => IndexableUri(u) }
    Ok(Json.toJson(indexables))
  }

  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val indexables = dataPipeCommander.getScrapedUris(seqNum, fetchSize)
    Ok(Json.toJson(indexables))
  }

  def getHighestUriSeq() = Action { request =>
    val seq = dataPipeCommander.getHighestUriSeq()
    Ok(SequenceNumber.format.writes(seq))
  }

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int) = Action { request =>
    val result = dataPipeCommander.getCollectionsChanged(seqNum, fetchSize)
    Ok(Json.toJson(result))
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int) = Action { request =>
    val phrases = dataPipeCommander.getPhrasesChanged(seqNum, fetchSize)
    Ok(Json.toJson(phrases))
  }

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action { request =>
    val bookmarks = dataPipeCommander.getBookmarksChanged(seqNum, fetchSize)
    Ok(Json.toJson(bookmarks))
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int) = Action { request =>
    val users = dataPipeCommander.getUserIndexable(seqNum, fetchSize)
    Ok(JsArray(users.map { u => Json.toJson(u) }))
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = Action { request =>
    val changes = dataPipeCommander.getNormalizedUriUpdates(lowSeq, highSeq)
    val jsChanges = changes.map {
      case (id, uri) =>
        JsObject(List("id" -> JsNumber(id.id), "uri" -> Json.toJson(uri)))
    }
    Ok(JsArray(jsChanges))
  }

  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int) = Action { request =>
    val changes = dataPipeCommander.getUserConnectionsChanged(seqNum, fetchSize)
    Ok(Json.toJson(changes))
  }

  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int) = Action { request =>
    val changes = dataPipeCommander.getSearchFriendsChanged(seqNum, fetchSize)
    Ok(Json.toJson(changes))
  }

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = Action { request =>
    val indexableSocialConnections = dataPipeCommander.getIndexableSocialConnections(seqNum, fetchSize)
    val json = Json.toJson(indexableSocialConnections)
    Ok(json)
  }

  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = Action { request =>
    val socialUserInfos = dataPipeCommander.getIndexableSocialUserInfos(seqNum, fetchSize)
    val json = Json.toJson(socialUserInfos)
    Ok(json)
  }

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = Action { request =>
    val updates = dataPipeCommander.getEmailAccountUpdates(seqNum, fetchSize)
    val json = Json.toJson(updates)
    Ok(json)
  }

  def getLibrariesAndMembershipsChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action { request =>
    val librariesWithMembersChanged = dataPipeCommander.getLibrariesAndMembershipsChanged(seqNum, fetchSize)
    Ok(Json.toJson(librariesWithMembersChanged))
  }

  def getLibraryMembershipChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int) = Action { request =>
    val libraryMembershipsChanged = dataPipeCommander.getLibraryMembershipChanged(seqNum, fetchSize)
    Ok(Json.toJson(libraryMembershipsChanged))
  }

  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action { request =>
    val keepAndTagsChanged = dataPipeCommander.getKeepsAndTagsChanged(seqNum, fetchSize)
    Ok(Json.toJson(keepAndTagsChanged))
  }
}
