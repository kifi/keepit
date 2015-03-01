package com.keepit.controllers.internal

import akka.actor.ActorSystem
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.service.RequestConsolidator
import play.api.libs.json._
import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.slick.Database
import play.api.mvc.Action
import com.keepit.model._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class DataPipelineExecutor(val context: ExecutionContext)

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
    libraryMembershipRepo: LibraryMembershipRepo,
    executor: DataPipelineExecutor) extends ShoeboxServiceController with Logging {

  implicit val context = executor.context

  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val uris = db.readOnlyReplica { implicit s =>
        normUriRepo.getIndexable(seqNum, fetchSize)
      }
      Ok(Json.toJson(uris))
    }
  }

  private[this] val consolidateGetIndexableUrisReq = new RequestConsolidator[(SequenceNumber[NormalizedURI], Int), Seq[IndexableUri]](ttl = 60 seconds)

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action.async { request =>
    val future = consolidateGetIndexableUrisReq((seqNum, fetchSize)) { key =>
      SafeFuture {
        db.readOnlyReplica { implicit s =>
          normUriRepo.getIndexable(seqNum, fetchSize)
        }
      } map { uris => uris map { u => IndexableUri(u) } }
    }
    future.map { indexables =>
      if (indexables.isEmpty) consolidateGetIndexableUrisReq.remove((seqNum, fetchSize))
      Ok(Json.toJson(indexables))
    }
  }

  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val scrapedStates = Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_FAILED, NormalizedURIStates.UNSCRAPABLE)
      val uris = db.readOnlyReplica { implicit s =>
        normUriRepo.getChanged(seqNum, includeStates = scrapedStates, limit = fetchSize)
      }
      val indexables = uris map { u => IndexableUri(u) }
      Ok(Json.toJson(indexables))
    }
  }

  def getHighestUriSeq() = Action.async { request =>
    SafeFuture {
      val seq = db.readOnlyReplica { implicit s =>
        normUriRepo.getCurrentSeqNum()
      }
      Ok(SequenceNumber.format.writes(seq))
    }
  }

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      Ok(Json.toJson(db.readOnlyReplica { implicit s =>
        collectionRepo.getCollectionsChanged(seqNum, fetchSize)
      }))
    }
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val phrases = db.readOnlyReplica { implicit s =>
        phraseRepo.getPhrasesChanged(seqNum, fetchSize)
      }
      Ok(Json.toJson(phrases))
    }
  }

  private[this] val consolidateGetBookmarksChangedReq = new RequestConsolidator[(SequenceNumber[Keep], Int), Seq[Keep]](ttl = 60 seconds)

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action.async { request =>
    val future = consolidateGetBookmarksChangedReq((seqNum, fetchSize)) { key =>
      db.readOnlyReplicaAsync { implicit session =>
        keepRepo.getBookmarksChanged(seqNum, fetchSize)
      }
    }
    future.map { bookmarks =>
      if (bookmarks.isEmpty) consolidateGetBookmarksChangedReq.remove((seqNum, fetchSize))
      Ok(Json.toJson(bookmarks))
    }
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val users = db.readOnlyReplica { implicit s =>
        userRepo.getUsersSince(seqNum, fetchSize)
      }
      Ok(JsArray(users.map { u => Json.toJson(u) }))
    }
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = Action.async { request =>
    SafeFuture {
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
  }

  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val changes = db.readOnlyReplica { implicit s =>
        userConnRepo.getUserConnectionChanged(seqNum, fetchSize)
      }
      Ok(Json.toJson(changes))
    }
  }

  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val changes = db.readOnlyReplica { implicit s =>
        searchFriendRepo.getSearchFriendsChanged(seqNum, fetchSize)
      }
      Ok(Json.toJson(changes))
    }
  }

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val indexableSocialConnections = db.readOnlyReplica { implicit session =>
        socialConnectionRepo.getConnAndNetworkBySeqNumber(seqNum, fetchSize).map {
          case (firstUserId, secondUserId, state, seq, networkType) =>
            IndexableSocialConnection(firstUserId, secondUserId, networkType, state, seq)
        }
      }
      val json = Json.toJson(indexableSocialConnections)
      Ok(json)
    }
  }

  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val socialUserInfos = db.readOnlyReplica { implicit session => socialUserInfoRepo.getBySequenceNumber(seqNum, fetchSize) }
      val json = Json.toJson(socialUserInfos)
      Ok(json)
    }
  }

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val modifiedEmails = db.readOnlyReplica { implicit session => emailAddressRepo.getBySequenceNumber(SequenceNumber[UserEmailAddress](seqNum.value), fetchSize) }
      val updates = modifiedEmails.map { email =>
        EmailAccountUpdate(email.address, email.userId, email.verified, email.state == UserEmailAddressStates.INACTIVE, SequenceNumber(email.seq.value))
      }
      val json = Json.toJson(updates)
      Ok(json)
    }
  }

  def getLibrariesAndMembershipsChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val librariesWithMembersChanged = db.readOnlyReplica { implicit session =>
        val changedLibraries = libraryRepo.getBySequenceNumber(seqNum, fetchSize)
        changedLibraries.map { library =>
          //using a limit this is a temp fix to make the data flow, it creates a proble for people who follow a lib with more then 1000 members
          val memberships = libraryMembershipRepo.getWithLibraryIdAndLimit(library.id.get, 1000)
          LibraryAndMemberships(library, memberships.map(_.toLibraryMembershipView))
        }
      }
      Ok(Json.toJson(librariesWithMembersChanged))
    }
  }

  def getLibraryMembership(id: Id[LibraryMembership]) = Action.async { request =>
    SafeFuture {
      val membership = db.readOnlyMaster { implicit session =>
        libraryMembershipRepo.get(id)
      }
      Ok(Json.toJson(membership))
    }
  }

  def getLibrariesAndMembershipIdsChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val librariesWithMembersChanged = db.readOnlyReplica { implicit session =>
        val changedLibraries = libraryRepo.getBySequenceNumber(seqNum, fetchSize)
        changedLibraries.map { library =>
          //using a limit this is a temp fix to make the data flow, it creates a proble for people who follow a lib with more then 1000 members
          val memberships = libraryMembershipRepo.getIdsWithLibraryId(library.id.get)
          LibraryAndMembershipsIds(library, memberships)
        }
      }
      Ok(Json.toJson(librariesWithMembersChanged))
    }
  }

  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val keepAndTagsChanged = db.readOnlyReplica { implicit session =>
        val changedKeeps = keepRepo.getBySequenceNumber(seqNum, fetchSize)
        changedKeeps.map { keep => KeepAndTags(keep, if (keep.isActive) collectionRepo.getTagsByKeepId(keep.id.get) else Set()) }
      }
      Ok(Json.toJson(keepAndTagsChanged))
    }
  }

  def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val libs = db.readOnlyReplica { implicit s => libraryRepo.getBySequenceNumber(seqNum, fetchSize) } map Library.toLibraryView
      Ok(Json.toJson(libs))
    }
  }

  def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val libs = db.readOnlyReplica { implicit s =>
        val libraries = libraryRepo.getBySequenceNumber(seqNum, fetchSize)
        val keepCountsByLibraryId = keepRepo.getCountsByLibrary(libraries.map(_.id.get).toSet).withDefaultValue(0)
        libraries map { library => Library.toDetailedLibraryView(library, keepCountsByLibraryId(library.id.get)) }
      }
      Ok(Json.toJson(libs))
    }
  }

  def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val mem = db.readOnlyReplica { implicit s => libraryMembershipRepo.getBySequenceNumber(seqNum, fetchSize) } map {
        _.toLibraryMembershipView
      }
      Ok(Json.toJson(mem))
    }
  }

  def dumpLibraryURIIds(libId: Id[Library]) = Action.async { implicit request =>
    SafeFuture {
      val keeps = db.readOnlyReplica { implicit s => keepRepo.getByLibrary(libId, offset = 0, limit = Integer.MAX_VALUE) }
      val ids = keeps.map {
        _.uriId
      }
      Ok(Json.toJson(ids))
    }
  }

}
