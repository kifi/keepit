package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.model._

class ShoeboxDataPipeCommander @Inject() (
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
    libraryMembershipRepo: LibraryMembershipRepo) {

  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = {
    db.readOnlyReplica(2) { implicit s =>
      normUriRepo.getIndexable(seqNum, fetchSize)
    }
  }

  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = {
    val scrapedStates = Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_FAILED, NormalizedURIStates.UNSCRAPABLE)
    val uris = db.readOnlyReplica(2) { implicit s =>
      normUriRepo.getChanged(seqNum, includeStates = scrapedStates, limit = fetchSize)
    }
    val indexables = uris map { u => IndexableUri(u) }
    indexables
  }

  def getHighestUriSeq() = {
    db.readOnlyReplica(2) { implicit s =>
      normUriRepo.getCurrentSeqNum()
    }
  }

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int) = {
    db.readOnlyReplica(2) { implicit s =>
      collectionRepo.getCollectionsChanged(seqNum, fetchSize)
    }
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int) = {
    db.readOnlyReplica(2) { implicit s =>
      phraseRepo.getPhrasesChanged(seqNum, fetchSize)
    }
  }

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = {
    db.readOnlyReplica(2) { implicit session =>
      keepRepo.getBookmarksChanged(seqNum, fetchSize)
    }
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int) = {
    val users = db.readOnlyReplica(2) { implicit s =>
      userRepo.getUsersSince(seqNum, fetchSize)
    }
    users
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = {
    val changes = db.readOnlyReplica(2) { implicit s =>
      changedUriRepo.getChangesBetween(lowSeq, highSeq).map { change =>
        (change.oldUriId, normUriRepo.get(change.newUriId))
      }
    }
    changes
  }

  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int) = {
    val changes = db.readOnlyReplica(2) { implicit s =>
      userConnRepo.getUserConnectionChanged(seqNum, fetchSize)
    }
    changes
  }

  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int) = {
    val changes = db.readOnlyReplica(2) { implicit s =>
      searchFriendRepo.getSearchFriendsChanged(seqNum, fetchSize)
    }
    changes
  }

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = {
    val indexableSocialConnections = db.readOnlyReplica(2) { implicit session =>
      socialConnectionRepo.getConnAndNetworkBySeqNumber(seqNum, fetchSize).map {
        case (firstUserId, secondUserId, state, seq, networkType) =>
          IndexableSocialConnection(firstUserId, secondUserId, networkType, state, seq)
      }
    }
    indexableSocialConnections
  }

  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = {
    val socialUserInfos = db.readOnlyReplica(2) { implicit session => socialUserInfoRepo.getBySequenceNumber(seqNum, fetchSize) }
    socialUserInfos
  }

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = {
    val modifiedEmails = db.readOnlyReplica(2) { implicit session => emailAddressRepo.getBySequenceNumber(SequenceNumber[UserEmailAddress](seqNum.value), fetchSize) }
    val updates = modifiedEmails.map { email =>
      EmailAccountUpdate(email.address, email.userId, email.verified, email.state == UserEmailAddressStates.INACTIVE, SequenceNumber(email.seq.value))
    }
    updates
  }

  def getLibrariesAndMembershipsChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = {
    val librariesWithMembersChanged = db.readOnlyReplica(2) { implicit session =>
      val changedLibraries = libraryRepo.getBySequenceNumber(seqNum, fetchSize)
      changedLibraries.map { library =>
        val memberships = libraryMembershipRepo.getWithLibraryId(library.id.get)
        LibraryAndMemberships(library, memberships)
      }
    }
    librariesWithMembersChanged
  }

  def getLibraryMembershipChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int) = {
    val libraryMembershipsChanged = db.readOnlyReplica { implicit session =>
      val memberships = libraryMembershipRepo.getBySequenceNumber(seqNum, fetchSize)
      val preferredLibs = libraryRepo.getLibrariesByKind(memberships.map(_.libraryId).distinct.toSet).toSet
      memberships.filter(membership => preferredLibs.contains(membership.libraryId))
    }
    libraryMembershipsChanged
  }

  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = {
    val keepAndTagsChanged = db.readOnlyReplica(2) { implicit session =>
      val changedKeeps = keepRepo.getBySequenceNumber(seqNum, fetchSize)
      val collectionIdsByKeepIds = changedKeeps.map { keep => (keep.id.get -> keepToCollectionRepo.getCollectionsForKeep(keep.id.get).toSet) }.toMap
      val uniqueCollectionIds = collectionIdsByKeepIds.values.flatten.toSet
      val tagsByCollectionIds = uniqueCollectionIds.map { collectionId => (collectionId -> collectionRepo.get(collectionId).name) }.toMap
      changedKeeps.map { keep => KeepAndTags(keep, collectionIdsByKeepIds(keep.id.get).map(tagsByCollectionIds(_))) }
    }
    keepAndTagsChanged
  }

}
