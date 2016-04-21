package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Provider }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.typeahead.{ UserHashtagTypeaheadUserIdKey, UserHashtagTypeaheadCache }
import com.kifi.macros.json
import com.keepit.common.core._

import scala.collection.mutable
import scala.concurrent.ExecutionContext

/* Only deals with actual tag records, does not update dependencies like Keep.note. */
/* Does NOT check permissions to edit */
@ImplementedBy(classOf[TagCommanderImpl])
trait TagCommander {
  def getCountForUser(userId: Id[User])(implicit session: RSession): Int
  def addTagsToKeep(keepId: Id[Keep], tags: Traversable[Hashtag], userIdOpt: Option[Id[User]], messageIdOpt: Option[Id[Message]])(implicit session: RWSession): Unit
  def getTagsForKeep(keepId: Id[Keep])(implicit session: RSession): Seq[Hashtag]
  def getTagsForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[Hashtag]]
  def getTagInfoForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[TagInfo]]
  def tagsForUser(userId: Id[User], offset: Int, pageSize: Int, sort: TagSorting): Seq[FakedBasicCollection]
  def getKeepsByTagAndUser(tag: Hashtag, userId: Id[User])(implicit session: RSession): Seq[Id[Keep]]
  def removeTagsFromKeepsNote(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int
  def removeTagsFromKeepsByUser(userId: Id[User], keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int
  def removeTagsFromKeepsByMessage(messageId: Id[Message], keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int
  def removeAllTagsFromKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RWSession): Int
  def getTagsForMessage(messageId: Id[Message])(implicit session: RSession): Seq[Hashtag]
}

class TagCommanderImpl @Inject() (
    db: Database,
    keepTagRepo: KeepTagRepo,
    keepRepo: KeepRepo,
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends TagCommander with Logging {

  def getCountForUser(userId: Id[User])(implicit session: RSession): Int = {
    keepTagRepo.countForUser(userId)
  }

  def addTagsToKeep(keepId: Id[Keep], tags: Traversable[Hashtag], userIdOpt: Option[Id[User]], messageIdOpt: Option[Id[Message]])(implicit session: RWSession): Unit = {
    val existingTags = getTagsForKeep(keepId).map(t => t.normalized -> t).toMap

    tags.foreach { tag =>
      existingTags.get(tag.normalized) match {
        case Some(existing) => existing
        case None => keepTagRepo.save(KeepTag(tag = tag, keepId = keepId, messageId = messageIdOpt, userId = userIdOpt))
      }
    }
  }

  def tagsForUser(userId: Id[User], page: Int, pageSize: Int, sort: TagSorting): Seq[FakedBasicCollection] = {

    db.readOnlyMaster { implicit s =>
      keepTagRepo.getTagsByUser(userId, page * pageSize, pageSize, sort).collect {
        case r if r._2 > 0 =>
          FakedBasicCollection.fromTag(r._1, Some(r._2))
      }
    }
  }

  def getTagsForKeep(keepId: Id[Keep])(implicit session: RSession): Seq[Hashtag] = getTagsForKeeps(Seq(keepId))(session)(keepId)

  def getTagsForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[Hashtag]] = {
    keepTagRepo.getByKeepIds(keepIds).map { kt =>
      kt._1 -> kt._2.map(_.tag)
    }.withDefaultValue(Seq.empty)
  }

  // Less efficient than getTagsForKeeps
  def getTagInfoForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[TagInfo]] = {
    keepTagRepo.getByKeepIds(keepIds).map { kt =>
      kt._1 -> kt._2.map(k => TagInfo(k.tag, k.userId, k.messageId))
    }.withDefaultValue(Seq.empty)
  }

  def getKeepsByTagAndUser(tag: Hashtag, userId: Id[User])(implicit session: RSession): Seq[Id[Keep]] = {
    keepTagRepo.getAllByTagAndUser(tag, userId).map(_.keepId)
  }

  // Does not update note field, only tags that came from notes! Use KeepMutator.updateKeepNote for note updating needs.
  def removeTagsFromKeepsNote(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {
    keepTagRepo.removeTagsFromKeepNotes(keepIds, tags)
  }

  def removeTagsFromKeepsByUser(userId: Id[User], keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {
    keepTagRepo.removeTagsFromKeepByUser(userId, keepIds, tags)
  }

  def removeTagsFromKeepsByMessage(messageId: Id[Message], keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {
    keepTagRepo.removeTagsFromKeepByMessage(messageId, keepIds, tags)
  }

  // Primarily useful when unkeeping. Does not update keep notes. Responsibility of caller.
  def removeAllTagsFromKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RWSession): Int = {
    removeTagsFromKeeps(keepIds, getTagsForKeeps(keepIds).values.flatten)
  }

  // Reminder: does not check permissions. Do that yourself before calling this!
  // Removes all occurrences of each tag, even if they're there multiple times.
  // Does not update keep notes. Responsibility of caller.
  // Private because no one currently needs this externally.
  private def removeTagsFromKeeps(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {
    keepTagRepo.removeTagsFromKeep(keepIds, tags)
  }

  def getTagsForMessage(messageId: Id[Message])(implicit session: RSession): Seq[Hashtag] = {
    keepTagRepo.getByMessage(messageId).map(_.tag)
  }

}

/* Bulk actions regarding tags. Calls keepMutator when appropriate. */
class BulkTagCommander @Inject() (
    db: Database,
    keepTagRepo: KeepTagRepo,
    keepRepo: KeepRepo,
    keepMutator: KeepMutator,
    tagCommander: TagCommander,
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends Logging {
  // Used for tag management
  def removeAllForUserTag(userId: Id[User], tag: Hashtag)(implicit session: RWSession) = {

    val deactivatedTags = keepTagRepo.getAllByTagAndUser(tag, userId).map { kt =>
      keepTagRepo.deactivate(kt)
      kt
    }
    val keepsToChange = deactivatedTags.flatMap { kt =>
      if (kt.messageId.isEmpty) { // If not from message, remove from note
        Some(kt.keepId)
      } else None
    }

    // Update notes when appropriate
    val keeps = keepRepo.getActiveByIds(keepsToChange.toSet).values
    keeps.foreach {
      case keep =>
        if (keep.isActive && keep.note.nonEmpty) {
          val note = Hashtags.removeHashtagsFromString(keep.note.getOrElse(""), Set(tag))
          keepMutator.updateKeepNote(userId, keep, note)
        }
    }

    deactivatedTags.length
  }

  def replaceAllForUserTag(userId: Id[User], oldTag: Hashtag, newTag: Hashtag)(implicit session: RWSession) = {
    // todo / note: Notice that the message was not updated. Worth it? Until then, skipping tags from messages.

    val renamedTags = keepTagRepo.getAllByTagAndUser(oldTag, userId).filter(_.messageId.isEmpty).map { kt =>
      keepTagRepo.save(kt.copy(tag = newTag))
    }
    val keepsToChange = renamedTags.flatMap { kt =>
      if (kt.messageId.isEmpty) { // If not from message, replace in note
        Some(kt.keepId)
      } else None
    }

    // Update notes when appropriate
    val keeps = keepRepo.getActiveByIds(keepsToChange.toSet).values
    keeps.foreach { keep =>
      if (keep.isActive && keep.note.nonEmpty) {
        val note = Hashtags.replaceTagNameFromString(keep.note.getOrElse(""), oldTag.tag, newTag.tag)
        keepMutator.updateKeepNote(userId, keep, note)
      }
    }

    renamedTags.length
  }
}

// This is for old clients who expect a collection.id
// What we actually do is use the actual tag as the id as well.
@json case class FakedBasicCollection(id: String, name: String, keeps: Option[Int])
object FakedBasicCollection {
  def fromTag(tag: Hashtag, keepCountOpt: Option[Int]): FakedBasicCollection = {
    FakedBasicCollection(tag.tag, tag.tag, keepCountOpt)
  }
}

sealed trait TagSorting { def name: String }
object TagSorting {
  case object NumKeeps extends TagSorting { val name = "num_keeps" }
  case object Name extends TagSorting { val name = "name" }
  case object LastKept extends TagSorting { val name = "last_kept" }

  def apply(str: String) = str match {
    case NumKeeps.name => NumKeeps
    case Name.name => Name
    case _ => LastKept
  }
}

// This is merely a subset of KeepTag so that Collection tags can be returned. When all tags are in KeepTag, that can be returned.
case class TagInfo(tag: Hashtag, userId: Option[Id[User]], messageId: Option[Id[Message]])
