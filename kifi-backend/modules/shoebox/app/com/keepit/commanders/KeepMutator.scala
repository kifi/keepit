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
  def updateKeepNote(userId: Id[User], oldKeep: Keep, newNote: String)(implicit session: RWSession): Keep
  def updateKeepTitle(oldKeep: Keep, newTitle: String)(implicit session: RWSession): Keep
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
  keepRepo: KeepRepo,
  ktlRepo: KeepToLibraryRepo,
  ktlCommander: KeepToLibraryCommander,
  ktuRepo: KeepToUserRepo,
  ktuCommander: KeepToUserCommander,
  kteCommander: KeepToEmailCommander,
  keepSourceRepo: KeepSourceAttributionRepo,
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
  tagCommander: TagCommander,
  implicit val airbrake: AirbrakeNotifier,
  implicit val imageConfig: S3ImageConfig,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends KeepMutator with Logging {

  // Updates note on keep, making sure tags are in sync.
  // i.e., the note is the source of truth, and tags are added/removed appropriately
  def updateKeepNote(userId: Id[User], oldKeep: Keep, newNote: String)(implicit session: RWSession): Keep = {
    val noteToPersist = Some(newNote.trim).filter(_.nonEmpty)
    val updatedKeep = oldKeep.withOwner(userId).withNote(noteToPersist)

    if (oldKeep.note.nonEmpty || noteToPersist.nonEmpty) {
      log.info(s"[updateKeepNote] ${oldKeep.id.get}: Note changing from ${oldKeep.note} to $noteToPersist")
    }

    session.onTransactionSuccess {
      searchClient.updateKeepIndex()
    }

    syncTagsFromNote(updatedKeep, userId)
  }

  def updateKeepTitle(oldKeep: Keep, newTitle: String)(implicit session: RWSession): Keep = {
    keepRepo.save(oldKeep.withTitle(Some(newTitle)))
  }

  def setKeepOwner(keep: Keep, newOwner: Id[User])(implicit session: RWSession): Keep = {
    keepRepo.save(keep.withOwner(newOwner).withRecipients(keep.recipients.plusUser(newOwner))) tap { updatedKeep =>
      ktuCommander.internKeepInUser(updatedKeep, newOwner, None, addedAt = Some(keep.keptAt))
    }
  }

  // Assumes keep.note is set to be the updated value
  private def syncTagsFromNote(keep: Keep, userId: Id[User])(implicit session: RWSession): Keep = {
    val noteTags = Hashtags.findAllHashtagNames(keep.note.getOrElse("")).map(Hashtag(_))
    val existingTags = tagCommander.getTagInfoForKeeps(Seq(keep.id.get))(session)(keep.id.get).filter(_.messageId.isEmpty)
    val newTags = noteTags.filter(nt => !existingTags.exists(_.tag.normalized == nt.normalized))
    val tagsToRemove = existingTags
      .filter(et => et.userId.exists(_ == userId) && et.messageId.isEmpty && !noteTags.exists(_.normalized == et.tag.normalized))
      .map(_.tag)

    tagCommander.addTagsToKeep(keep.id.get, newTags, Some(userId), None)
    tagCommander.removeTagsFromKeepsNote(Seq(keep.id.get), tagsToRemove)

    if (newTags.nonEmpty || tagsToRemove.nonEmpty) {
      log.info(s"[updateKeepNote] ${keep.id.get}: Added tags [${newTags.mkString(",")}]. Removed tags: [${tagsToRemove.mkString(",")}]")
    }

    keepRepo.save(keep)
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
      session.onTransactionSuccess { searchClient.updateKeepIndex() }
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
    tagCommander.removeAllTagsFromKeeps(Seq(keep.id.get))
    keepRepo.deactivate(keep)
  }

  def moveKeep(k: Keep, toLibrary: Library, userId: Id[User])(implicit session: RWSession): Either[LibraryError, Keep] = {
    ktlCommander.removeKeepFromAllLibraries(k.id.get)
    ktlCommander.internKeepInLibrary(k, toLibrary, addedBy = Some(userId))
    Right(keepRepo.save(k.withLibraries(Set(toLibrary.id.get))))
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
