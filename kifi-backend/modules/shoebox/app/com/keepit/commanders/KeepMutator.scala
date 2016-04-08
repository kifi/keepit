package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.integrity.UriIntegrityHelpers
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.slack.LibraryToSlackChannelPusher
import com.keepit.typeahead.{ HashtagHit, HashtagTypeahead, TypeaheadHit }
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

/**
 * EVERY method in KeepMutator takes in an implicit db session. It is used exclusively to perform
 * routine actions on a keep. Please do not use keepRepo.save directly. There are many entities
 * across multiple tables that must be updated when a keep changes.
 */
@ImplementedBy(classOf[KeepMutatorImpl])
trait KeepMutator {
  def persistKeep(k: Keep)(implicit session: RWSession): Keep
  def unsafeModifyKeepRecipients(keepId: Id[Keep], diff: KeepRecipientsDiff, userAttribution: Option[Id[User]])(implicit session: RWSession): Keep
  def updateKeepNote(userId: Id[User], oldKeep: Keep, newNote: String, freshTag: Boolean = true)(implicit session: RWSession): Keep
  def setKeepOwner(keep: Keep, newOwner: Id[User])(implicit session: RWSession): Keep
  def updateLastActivityAtIfLater(keepId: Id[Keep], lastActivityAt: DateTime)(implicit session: RWSession): Keep
  def moveKeep(k: Keep, toLibrary: Library, userId: Id[User])(implicit session: RWSession): Either[LibraryError, Keep]
  def copyKeep(k: Keep, toLibrary: Library, userId: Id[User], withSource: Option[KeepSource] = None)(implicit session: RWSession): Either[LibraryError, Keep]
  def deactivateKeep(keep: Keep)(implicit session: RWSession): Unit
  def refreshLibraries(keepId: Id[Keep])(implicit session: RWSession): Keep
  def refreshParticipants(keepId: Id[Keep])(implicit session: RWSession): Keep
  def changeUri(keep: Keep, newUri: NormalizedURI)(implicit session: RWSession): Unit
}

@Singleton
class KeepMutatorImpl @Inject() (
  searchClient: SearchServiceClient,
  keepToCollectionRepo: KeepToCollectionRepo,
  keepRepo: KeepRepo,
  ktlRepo: KeepToLibraryRepo,
  ktlCommander: KeepToLibraryCommander,
  ktuRepo: KeepToUserRepo,
  ktuCommander: KeepToUserCommander,
  kteCommander: KeepToEmailCommander,
  keepSourceRepo: KeepSourceAttributionRepo,
  collectionRepo: CollectionRepo,
  clock: Clock,
  libraryRepo: LibraryRepo,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  basicOrganizationGen: BasicOrganizationGen,
  libraryMembershipRepo: LibraryMembershipRepo,
  hashtagTypeahead: HashtagTypeahead,
  keepDecorator: KeepDecorator,
  twitterPublishingCommander: TwitterPublishingCommander,
  uriHelpers: UriIntegrityHelpers,
  slackPusher: LibraryToSlackChannelPusher,
  implicit val airbrake: AirbrakeNotifier,
  implicit val imageConfig: S3ImageConfig,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends KeepMutator with Logging {

  // Updates note on keep, making sure tags are in sync.
  // i.e., the note is the source of truth, and tags are added/removed appropriately
  def updateKeepNote(userId: Id[User], oldKeep: Keep, newNote: String, freshTag: Boolean)(implicit session: RWSession): Keep = {
    // todo IMPORTANT: check permissions here, this lets anyone edit anyone's keep.
    val noteToPersist = Some(newNote.trim).filter(_.nonEmpty)
    val updatedKeep = oldKeep.withOwner(userId).withNote(noteToPersist)
    val hashtagNamesToPersist = Hashtags.findAllHashtagNames(noteToPersist.getOrElse(""))
    val (keep, colls) = syncTagsToNoteAndSaveKeep(userId, updatedKeep, hashtagNamesToPersist.toSeq, freshTag = freshTag)
    session.onTransactionSuccess {
      searchClient.updateKeepIndex()
      slackPusher.schedule(oldKeep.recipients.libraries)
    }
    colls.foreach { c =>
      Try(collectionRepo.collectionChanged(c.id, c.isNewKeep, c.inactivateIfEmpty)) // deadlock prone
    }
    keep
  }

  def setKeepOwner(keep: Keep, newOwner: Id[User])(implicit session: RWSession): Keep = {
    keepRepo.save(keep.withOwner(newOwner).withRecipients(keep.recipients.plusUser(newOwner))) tap { updatedKeep =>
      ktuCommander.internKeepInUser(updatedKeep, newOwner, None, addedAt = Some(keep.keptAt))
    }
  }

  private def getOrCreateTag(userId: Id[User], name: String)(implicit session: RWSession): Collection = {
    val normalizedName = Hashtag(name.trim.replaceAll("""\s+""", " ").take(Collection.MaxNameLength))
    val collection = collectionRepo.getByUserAndName(userId, normalizedName, excludeState = None)
    collection match {
      case Some(t) if t.isActive => t
      case Some(t) => collectionRepo.save(t.copy(state = CollectionStates.ACTIVE, name = normalizedName, createdAt = clock.now()))
      case None => collectionRepo.save(Collection(userId = userId, name = normalizedName))
    }
  }

  // Given set of tags and keep, update keep note to reflect tag seq (create tags, remove tags, insert into note, remove from note)
  // i.e., source of tag truth is the tag seq, note will be brought in sync
  // Important: Caller's responsibility to call collectionRepo.collectionChanged from the return value for collections that changed
  case class ChangedCollection(id: Id[Collection], isNewKeep: Boolean, inactivateIfEmpty: Boolean)
  private def syncTagsToNoteAndSaveKeep(userId: Id[User], keep: Keep, allTagsKeepShouldHave: Seq[String], freshTag: Boolean = false)(implicit session: RWSession) = {
    // get all tags from hashtag names list
    val selectedTags = allTagsKeepShouldHave.flatMap { t => Try(getOrCreateTag(userId, t)).toOption }
    val selectedTagIds = selectedTags.map(_.id.get).toSet
    // get all active tags for keep to figure out which tags to add & which tags to remove
    val activeTagIds = keepToCollectionRepo.getCollectionsForKeep(keep.id.get).toSet
    val tagIdsToAdd = selectedTagIds -- activeTagIds
    val tagIdsToRemove = activeTagIds -- selectedTagIds
    var changedCollections = scala.collection.mutable.Set.empty[ChangedCollection]

    // fix k2c for tagsToAdd & tagsToRemove
    tagIdsToAdd.map { tagId =>
      keepToCollectionRepo.getOpt(keep.id.get, tagId) match {
        case None => keepToCollectionRepo.save(KeepToCollection(keepId = keep.id.get, collectionId = tagId))
        case Some(k2c) => keepToCollectionRepo.save(k2c.copy(state = KeepToCollectionStates.ACTIVE))
      }
      changedCollections += ChangedCollection(tagId, isNewKeep = freshTag, inactivateIfEmpty = false)
    }
    tagIdsToRemove.map { tagId =>
      keepToCollectionRepo.remove(keep.id.get, tagId)
      changedCollections += ChangedCollection(tagId, isNewKeep = false, inactivateIfEmpty = true)
    }

    // go through note field and find all hashtags
    val keepNote = keep.note.getOrElse("")
    val hashtagsInNote = Hashtags.findAllHashtagNames(keepNote)
    val hashtagsToPersistSet = allTagsKeepShouldHave.toSet

    // find hashtags to remove & to append
    val hashtagsToRemove = hashtagsInNote -- hashtagsToPersistSet
    val hashtagsToAppend = allTagsKeepShouldHave.filterNot(hashtagsInNote.contains)
    val noteWithHashtagsRemoved = Hashtags.removeTagNamesFromString(keepNote, hashtagsToRemove.toSet)
    val noteWithHashtagsAppended = Hashtags.addTagsToString(noteWithHashtagsRemoved, hashtagsToAppend)
    val finalNote = Some(noteWithHashtagsAppended.trim).filterNot(_.isEmpty)

    (keepRepo.save(keep.withNote(finalNote)), changedCollections)
  }

  def searchTags(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    implicit val hitOrdering = TypeaheadHit.defaultOrdering[(Hashtag, Int)]
    hashtagTypeahead.topN(userId, query, limit).map(_.map(_.info)).map(HashtagHit.highlight(query, _))
  }

  def persistKeep(k: Keep)(implicit session: RWSession): Keep = {
    require(k.userId.toSet subsetOf k.recipients.users, "keep owner is not one of the connected users")

    val oldKeepOpt = k.id.map(keepRepo.get)
    val oldRecipients = oldKeepOpt.map(_.recipients)
    val newKeep = {
      val saved = keepRepo.save(k.withRecipients(k.recipients union oldRecipients))
      (saved.note, saved.userId) match {
        case (Some(n), Some(uid)) if n.nonEmpty => updateKeepNote(uid, saved, saved.note.getOrElse("")) // Saves again, but easiest way to do it.
        case _ => saved
      }
    }

    val oldLibraries = oldRecipients.map(_.libraries).getOrElse(Set.empty)
    if (oldLibraries != newKeep.recipients.libraries) {
      val libraries = libraryRepo.getActiveByIds(newKeep.recipients.libraries -- oldLibraries).values
      libraries.foreach { lib => ktlCommander.internKeepInLibrary(newKeep, lib, newKeep.userId) }
    }

    val oldUsers = oldRecipients.map(_.users).getOrElse(Set.empty)
    if (oldUsers != newKeep.recipients.users) {
      val newUsers = newKeep.recipients.users -- oldUsers
      newUsers.foreach { userId => ktuCommander.internKeepInUser(newKeep, userId, addedBy = None, addedAt = None) }
    }

    val oldEmails = oldRecipients.map(_.emails).getOrElse(Set.empty)
    if (oldEmails != newKeep.recipients.emails) {
      val newEmails = newKeep.recipients.emails -- oldEmails
      newEmails.foreach { email => kteCommander.internKeepInEmail(newKeep, email, addedBy = None, addedAt = None) }
    }

    newKeep
  }
  def unsafeModifyKeepRecipients(keepId: Id[Keep], diff: KeepRecipientsDiff, userAttribution: Option[Id[User]])(implicit session: RWSession): Keep = {
    val oldKeep = keepRepo.get(keepId)
    keepRepo.save(oldKeep.withRecipients(oldKeep.recipients.diffed(diff))) tap { newKeep =>
      diff.users.added.foreach { added => ktuCommander.internKeepInUser(newKeep, added, userAttribution) }
      diff.users.removed.foreach { removed => ktuCommander.removeKeepFromUser(newKeep.id.get, removed) }
      diff.emails.added.foreach { added => kteCommander.internKeepInEmail(newKeep, added, userAttribution) }
      diff.emails.removed.foreach { removed => kteCommander.removeKeepFromEmail(newKeep.id.get, removed) }
      libraryRepo.getActiveByIds(diff.libraries.added).values.foreach { newLib =>
        ktlCommander.internKeepInLibrary(newKeep, newLib, userAttribution)
      }
      diff.libraries.removed.foreach { removed => ktlCommander.removeKeepFromLibrary(newKeep.id.get, removed) }
      session.onTransactionSuccess {
        slackPusher.schedule(diff.libraries.added)
      }
    }
  }
  def updateLastActivityAtIfLater(keepId: Id[Keep], time: DateTime)(implicit session: RWSession): Keep = {
    val oldKeep = keepRepo.get(keepId)
    val newKeep = oldKeep.withLastActivityAtIfLater(time)

    if (newKeep.lastActivityAt != oldKeep.lastActivityAt) {
      ktuRepo.getAllByKeepId(keepId).foreach(ktu => ktuRepo.save(ktu.withLastActivityAt(time)))
      ktlRepo.getAllByKeepId(keepId).foreach(ktl => ktlRepo.save(ktl.withLastActivityAt(time)))
      keepRepo.save(newKeep)
    } else oldKeep
  }
  def refreshLibraries(keepId: Id[Keep])(implicit session: RWSession): Keep = {
    val keep = keepRepo.getNoCache(keepId)
    val claimedLibraries = keep.recipients.libraries
    val actualLibraries = ktlRepo.getAllByKeepId(keepId).map(_.libraryId).toSet
    if (claimedLibraries != actualLibraries) {
      keepRepo.save(keep.withLibraries(actualLibraries))
    } else keep
  }
  def refreshParticipants(keepId: Id[Keep])(implicit session: RWSession): Keep = {
    val keep = keepRepo.getNoCache(keepId)
    val claimedUsers = keep.recipients.users
    val actualUsers = ktuRepo.getAllByKeepId(keepId).map(_.userId).toSet
    if (claimedUsers != actualUsers) {
      keepRepo.save(keep.withParticipants(actualUsers))
    } else keep
  }
  def changeUri(keep: Keep, newUri: NormalizedURI)(implicit session: RWSession): Unit = {
    if (keep.isInactive) {
      val newKeep = keepRepo.save(keep.withUriId(newUri.id.get))
      ktlCommander.syncKeep(newKeep)
      ktuCommander.syncKeep(newKeep)
      kteCommander.syncKeep(newKeep)
    } else {
      val newKeep = keepRepo.save(uriHelpers.improveKeepSafely(newUri, keep.withUriId(newUri.id.get)))
      ktlCommander.syncKeep(newKeep)
      ktuCommander.syncKeep(newKeep)
      kteCommander.syncKeep(newKeep)
    }
  }

  def deactivateKeep(keep: Keep)(implicit session: RWSession): Unit = {
    ktlCommander.removeKeepFromAllLibraries(keep.id.get)
    ktuCommander.removeKeepFromAllUsers(keep)
    kteCommander.removeKeepFromAllEmails(keep)
    keepSourceRepo.deactivateByKeepId(keep.id.get)
    keepToCollectionRepo.getByKeep(keep.id.get).foreach(keepToCollectionRepo.deactivate)
    keepRepo.deactivate(keep)
  }

  def moveKeep(k: Keep, toLibrary: Library, userId: Id[User])(implicit session: RWSession): Either[LibraryError, Keep] = {
    ktlRepo.getByUriAndLibrary(k.uriId, toLibrary.id.get) match {
      case None =>
        ktlCommander.removeKeepFromAllLibraries(k.id.get)
        ktlCommander.internKeepInLibrary(k, toLibrary, addedBy = Some(userId))
        Right(keepRepo.save(k.withLibraries(Set(toLibrary.id.get))))
      case Some(obstacle) =>
        // TODO(ryan): surely this is insane behavior...why did I write tests that assume this happens?
        if (obstacle.keepId != k.id.get) {
          deactivateKeep(k)
        }
        Left(LibraryError.AlreadyExistsInDest)
    }
  }
  def copyKeep(k: Keep, toLibrary: Library, userId: Id[User], withSource: Option[KeepSource] = None)(implicit session: RWSession): Either[LibraryError, Keep] = {
    val currentKeeps = keepRepo.getByUriAndLibrariesHash(k.uriId, Set(toLibrary.id.get))
    currentKeeps match {
      case existingKeep +: _ =>
        Left(LibraryError.AlreadyExistsInDest)
      case _ =>
        val newKeep = Keep(
          userId = Some(userId),
          url = k.url,
          uriId = k.uriId,
          keptAt = clock.now,
          source = withSource.getOrElse(k.source),
          originalKeeperId = k.originalKeeperId.orElse(Some(userId)),
          recipients = KeepRecipients(libraries = Set(toLibrary.id.get), users = Set(userId), emails = Set.empty),
          title = k.title,
          note = k.note
        )
        val copied = persistKeep(newKeep)
        Right(copied)
    }
  }
}
