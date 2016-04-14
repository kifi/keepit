package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Provider }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.model._
import com.keepit.search.SearchServiceClient
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
  def getForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[TagInfo]]
  def tagsForUser(userId: Id[User], offset: Int, pageSize: Int, sort: TagSorting): Seq[FakedBasicCollection]
  def getKeepsByTagAndUser(tag: Hashtag, userId: Id[User])(implicit session: RSession): Seq[Id[Keep]]
  def removeTagsFromKeeps(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int
  def removeAllTagsFromKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RWSession): Int
}

class TagCommanderImpl @Inject() (
    db: Database,
    keepTagRepo: KeepTagRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    keepRepo: KeepRepo,
    collectionRepo: CollectionRepo,
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends TagCommander with Logging {

  // todo:
  /*

  getByUser (userId, sorting, limit, offset)
  getByMessage (messageId)

  clear UserHashtagTypeaheadUserIdKey when user's typeahead changes

   */

  def getCountForUser(userId: Id[User])(implicit session: RSession): Int = {
    collectionRepo.count(userId) + keepTagRepo.countForUser(userId)
  }

  def addTagsToKeep(keepId: Id[Keep], tags: Traversable[Hashtag], userIdOpt: Option[Id[User]], messageIdOpt: Option[Id[Message]])(implicit session: RWSession): Unit = {
    val existingTags = keepTagRepo.getByKeepIds(Seq(keepId))(session)(keepId).map(t => t.tag.normalized -> t).toMap

    tags.foreach { tag =>
      existingTags.get(tag.normalized) match {
        case Some(existing) => existing
        case None => keepTagRepo.save(KeepTag(tag = tag, keepId = keepId, messageId = messageIdOpt, userId = userIdOpt))
      }
    }
  }

  def tagsForUser(userId: Id[User], offset: Int, pageSize: Int, sort: TagSorting): Seq[FakedBasicCollection] = {

    // todo: Plug in KeepTag.
    db.readOnlyMaster { implicit s =>
      val collectionResults = sort match {
        case TagSorting.NumKeeps => collectionRepo.getByUserSortedByNumKeeps(userId, offset, pageSize)
        case TagSorting.Name => collectionRepo.getByUserSortedByName(userId, offset, pageSize)
        case TagSorting.LastKept => collectionRepo.getByUserSortedByLastKept(userId, offset, pageSize)
      }

      val keepTags = keepTagRepo.getTagsByUser(userId, offset, pageSize, sort).map(r => FakedBasicCollection.fromTag(r._1, Some(r._2)))

      keepTags ++ collectionResults.map { case (collectionSummary, keepCount) => FakedBasicCollection.fromTag(collectionSummary.name, Some(keepCount)) }
    }
  }

  def getTagsForKeep(keepId: Id[Keep])(implicit session: RSession): Seq[Hashtag] = getTagsForKeeps(Seq(keepId))(session)(keepId)

  def getTagsForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[Hashtag]] = {
    val combined = mutable.HashMap[Id[Keep], Seq[Hashtag]]().withDefaultValue(Seq.empty)

    keepTagRepo.getByKeepIds(keepIds).map { kt =>
      kt._1 -> kt._2.map(_.tag)
    }.foreach {
      case (kId, hts) =>
        combined += ((kId, combined(kId) ++ hts))
    }

    keepIds.map { keepId =>
      keepId -> collectionRepo.getHashtagsByKeepId(keepId).toSeq
    }.toMap.foreach {
      case (kId, hts) =>
        combined += ((kId, combined(kId) ++ hts))
    }

    combined.mapValues(d => d.distinctBy(_.normalized)).toMap.withDefaultValue(Seq.empty)
  }

  // Less efficient than getTagsForKeeps
  def getForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[TagInfo]] = {
    val combined = mutable.HashMap[Id[Keep], Seq[TagInfo]]().withDefaultValue(Seq.empty)

    keepTagRepo.getByKeepIds(keepIds).map { kt =>
      kt._1 -> kt._2.map(k => TagInfo(k.tag, k.userId, k.messageId))
    }.foreach {
      case (kId, hts) =>
        combined += ((kId, combined(kId) ++ hts))
    }

    val keepOwners = keepRepo.getActiveByIds(keepIds.toSet).mapValues(_.userId).withDefaultValue(None)
    keepIds.map { keepId =>
      keepId -> collectionRepo.getHashtagsByKeepId(keepId).toSeq
    }.toMap.foreach {
      case (kId, hts) =>
        combined += ((kId, combined(kId) ++ hts.map(t => TagInfo(t, keepOwners(kId), None))))
    }

    combined.mapValues(d => d.distinctBy(_.tag.normalized)).toMap.withDefaultValue(Seq.empty)
  }

  def getKeepsByTagAndUser(tag: Hashtag, userId: Id[User])(implicit session: RSession): Seq[Id[Keep]] = {
    val collKeepIds = collectionRepo.getByUserAndName(userId, tag).map { coll =>
      keepToCollectionRepo.getKeepsForTag(coll.id.get)
    }.getOrElse(Seq.empty)
    keepTagRepo.getAllByTagAndUser(tag, userId).map(_.keepId) ++ collKeepIds
  }

  // Reminder: does not check permissions. Do that yourself before calling this!
  // Does not update keep notes. Responsibility of caller.
  def removeTagsFromKeeps(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {

    val ktRemovals = keepTagRepo.removeTagsFromKeep(keepIds, tags)

    var colRemovals = 0
    keepIds.flatMap { keepId =>
      keepToCollectionRepo.getByKeep(keepId).map { ktc =>
        keepToCollectionRepo.deactivate(ktc)
        colRemovals += 1
        ktc.collectionId
      }
    }.toSet.foreach { cid: Id[Collection] =>
      collectionRepo.collectionChanged(cid, isNewKeep = false, inactivateIfEmpty = true)
    }

    ktRemovals + colRemovals
  }

  // Primarily useful when unkeeping. Does not update keep notes. Responsibility of caller.
  def removeAllTagsFromKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RWSession): Int = {
    removeTagsFromKeeps(keepIds, getTagsForKeeps(keepIds).values.flatten)
  }

}

/* Bulk actions regarding tags. Calls keepMutator when appropriate. */
class BulkTagCommander @Inject() (
    db: Database,
    keepTagRepo: KeepTagRepo,
    keepRepo: KeepRepo,
    keepMutator: KeepMutator,
    tagCommander: TagCommander,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
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

    // Collection ⨯ ⨯ ⨯
    val collectionKeepIds = collectionRepo.getByUserAndName(userId, tag).map { coll =>
      val keepIds = keepToCollectionRepo.getKeepsForTag(coll.id.get)
      tagCommander.removeTagsFromKeeps(keepIds, Seq(tag))
      keepIds
    }.getOrElse(Seq.empty)
    // Collection ⨯ ⨯ ⨯

    // Update notes when appropriate
    val keeps = keepRepo.getActiveByIds((keepsToChange ++ collectionKeepIds).toSet).values
    keeps.foreach {
      case keep =>
        if (keep.isActive && keep.note.nonEmpty) {
          val note = Hashtags.removeHashtagsFromString(keep.note.getOrElse(""), Set(tag))
          keepMutator.updateKeepNote(userId, keep, note)
        }
    }

    deactivatedTags.length + collectionKeepIds.length
  }

  def replaceAllForUserTag(userId: Id[User], oldTag: Hashtag, newTag: Hashtag)(implicit session: RWSession) = {
    val renamedTags = keepTagRepo.getAllByTagAndUser(oldTag, userId).map { kt =>
      keepTagRepo.save(kt.copy(tag = newTag))
    }
    val keepsToChange = renamedTags.flatMap { kt =>
      if (kt.messageId.isEmpty) { // If not from message, replace in note
        Some(kt.keepId)
      } else None
    }

    // Collection ⨯ ⨯ ⨯
    val collectionKeepIds = collectionRepo.getByUserAndName(userId, oldTag).map { coll =>
      collectionRepo.save(coll.copy(name = newTag))
      keepToCollectionRepo.getKeepsForTag(coll.id.get)
    }.getOrElse(Seq.empty)
    // Collection ⨯ ⨯ ⨯

    // Update notes when appropriate
    val keeps = keepRepo.getActiveByIds((keepsToChange ++ collectionKeepIds).toSet).values
    keeps.foreach { keep =>
      if (keep.isActive && keep.note.nonEmpty) {
        val note = Hashtags.replaceTagNameFromString(keep.note.getOrElse(""), oldTag.tag, newTag.tag)
        keepMutator.updateKeepNote(userId, keep, note)
      }
    }

    renamedTags.length
    // todo: Clean up CollectionRepo, KeepToCollectionRepo
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
