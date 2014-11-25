package com.keepit.controllers.internal

import com.keepit.common.db.slick.Database.Replica
import com.keepit.common.db.SequenceNumber
import play.api.libs.json.{ JsNumber, JsObject, JsArray, Json }
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.mvc.Action
import com.keepit.model._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import org.msgpack.ScalaMessagePack
import play.api.http.ContentTypes
import com.keepit.model.serialize.{ UriIdAndSeqBatch, UriIdAndSeq }
import com.keepit.model.serialize.UriIdAndSeqBatch

class ShoeboxDataPipeController @Inject() (
    db: Database,
    userRepo: UserRepo,
    normUriRepo: NormalizedURIRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    keepRepo: KeepRepo,
    changedUriRepo: ChangedURIRepo,
    phraseRepo: PhraseRepo,
    userConnRepo: UserConnectionRepo,
    searchFriendRepo: SearchFriendRepo,
    socialConnectionRepo: SocialConnectionRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    emailAddressRepo: UserEmailAddressRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo) extends ShoeboxServiceController with Logging {

  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val uris = db.readOnlyReplica { implicit s =>
      normUriRepo.getIndexable(seqNum, fetchSize)
    }
    Ok(Json.toJson(uris))
  }

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val uris = db.readOnlyReplica { implicit s =>
      normUriRepo.getIndexable(seqNum, fetchSize)
    }
    val indexables = uris map { u => IndexableUri(u) }
    Ok(Json.toJson(indexables))
  }

  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>
    val scrapedStates = Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_FAILED, NormalizedURIStates.UNSCRAPABLE)
    val uris = db.readOnlyReplica { implicit s =>
      normUriRepo.getChanged(seqNum, includeStates = scrapedStates, limit = fetchSize)
    }
    val indexables = uris map { u => IndexableUri(u) }
    Ok(Json.toJson(indexables))
  }

  def getHighestUriSeq() = Action { request =>
    val seq = db.readOnlyReplica { implicit s =>
      normUriRepo.getCurrentSeqNum()
    }
    Ok(SequenceNumber.format.writes(seq))
  }

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int) = Action { request =>
    Ok(Json.toJson(db.readOnlyReplica { implicit s =>
      collectionRepo.getCollectionsChanged(seqNum, fetchSize)
    }))
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int) = Action { request =>
    val phrases = db.readOnlyReplica { implicit s =>
      phraseRepo.getPhrasesChanged(seqNum, fetchSize)
    }
    Ok(Json.toJson(phrases))
  }

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action { request =>
    val bookmarks = db.readOnlyReplica { implicit session =>
      keepRepo.getBookmarksChanged(seqNum, fetchSize)
    }
    Ok(Json.toJson(bookmarks))
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int) = Action { request =>
    val users = db.readOnlyReplica { implicit s =>
      userRepo.getUsersSince(seqNum, fetchSize)
    }
    Ok(JsArray(users.map { u => Json.toJson(u) }))
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = Action { request =>
    val changes = db.readOnlyReplica { implicit s =>
      changedUriRepo.getChangesBetween(lowSeq, highSeq).map { change =>
        (change.oldUriId, normUriRepo.get(change.newUriId))
      }
    }
    val jsChanges = changes.map {
      case (id, uri) =>
        JsObject(List("id" -> JsNumber(id.id), "uri" -> Json.toJson(uri)))
    }
    Ok(JsArray(jsChanges))
  }

  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int) = Action { request =>
    val changes = db.readOnlyReplica { implicit s =>
      userConnRepo.getUserConnectionChanged(seqNum, fetchSize)
    }
    Ok(Json.toJson(changes))
  }

  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int) = Action { request =>
    val changes = db.readOnlyReplica { implicit s =>
      searchFriendRepo.getSearchFriendsChanged(seqNum, fetchSize)
    }
    Ok(Json.toJson(changes))
  }

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = Action { request =>
    val indexableSocialConnections = db.readOnlyReplica { implicit session =>
      socialConnectionRepo.getConnAndNetworkBySeqNumber(seqNum, fetchSize).map {
        case (firstUserId, secondUserId, state, seq, networkType) =>
          IndexableSocialConnection(firstUserId, secondUserId, networkType, state, seq)
      }
    }
    val json = Json.toJson(indexableSocialConnections)
    Ok(json)
  }

  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = Action { request =>
    val socialUserInfos = db.readOnlyReplica { implicit session => socialUserInfoRepo.getBySequenceNumber(seqNum, fetchSize) }
    val json = Json.toJson(socialUserInfos)
    Ok(json)
  }

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = Action { request =>
    val modifiedEmails = db.readOnlyReplica { implicit session => emailAddressRepo.getBySequenceNumber(SequenceNumber[UserEmailAddress](seqNum.value), fetchSize) }
    val updates = modifiedEmails.map { email =>
      EmailAccountUpdate(email.address, email.userId, email.verified, email.state == UserEmailAddressStates.INACTIVE, SequenceNumber(email.seq.value))
    }
    val json = Json.toJson(updates)
    Ok(json)
  }

  def getLibrariesAndMembershipsChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action { request =>
    val librariesWithMembersChanged = db.readOnlyReplica { implicit session =>
      val changedLibraries = libraryRepo.getBySequenceNumber(seqNum, fetchSize)
      changedLibraries.map { library =>
        val memberships = libraryMembershipRepo.getWithLibraryId(library.id.get)
        LibraryAndMemberships(library, memberships.map(_.toLibraryMembershipView))
      }
    }
    Ok(Json.toJson(librariesWithMembersChanged))
  }

  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action { request =>
    val keepAndTagsChanged = db.readOnlyReplica { implicit session =>
      val changedKeeps = keepRepo.getBySequenceNumber(seqNum, fetchSize)
      changedKeeps.map { keep => KeepAndTags(keep, if (keep.isActive) collectionRepo.getTagsByKeepId(keep.id.get) else Set()) }
    }
    Ok(Json.toJson(keepAndTagsChanged))
  }

  def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action { request =>
    val libs = db.readOnlyReplica { implicit s => libraryRepo.getBySequenceNumber(seqNum, fetchSize) } map Library.toLibraryView
    Ok(Json.toJson(libs))
  }

  def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action { request =>
    val libs = db.readOnlyReplica { implicit s =>
      val libraries = libraryRepo.getBySequenceNumber(seqNum, fetchSize)
      val keepCountsByLibraryId = keepRepo.getCountsByLibrary(libraries.map(_.id.get).toSet).withDefaultValue(0)
      libraries map { library => Library.toDetailedLibraryView(library, keepCountsByLibraryId(library.id.get)) }
    }
    Ok(Json.toJson(libs))
  }

  def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int) = Action { request =>
    val mem = db.readOnlyReplica { implicit s => libraryMembershipRepo.getBySequenceNumber(seqNum, fetchSize) } map { _.toLibraryMembershipView }
    Ok(Json.toJson(mem))
  }
}
