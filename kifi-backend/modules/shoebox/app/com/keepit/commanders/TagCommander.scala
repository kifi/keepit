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
      val collectionResults = (sort match {
        case TagSorting.NumKeeps => collectionRepo.getByUserSortedByNumKeeps(userId, page, pageSize)
        case TagSorting.Name => collectionRepo.getByUserSortedByName(userId, page, pageSize)
        case TagSorting.LastKept => collectionRepo.getByUserSortedByLastKept(userId, page, pageSize)
      }).map { case (collectionSummary, keepCount) => (collectionSummary.name, keepCount) }

      val keepTags = keepTagRepo.getTagsByUser(userId, page * pageSize, pageSize, sort)

      val allTags = { // This just combines the two results, trying to keep the original order
        val all = (keepTags ++ collectionResults).zipWithIndex
        val combined = mutable.Map.empty[Hashtag, (Int, Option[Int])].withDefaultValue((0, None))
        all.foreach {
          case ((tag, count), idx) =>
            val (cnt, prevIdx) = combined(tag)
            combined += ((tag, (cnt + count, Some(prevIdx.getOrElse(idx)))))
        }
        combined.toSeq.sortBy(_._2._2).collect {
          case e if e._2._2.nonEmpty =>
            (e._1, e._2._1)
        }
      }
      (sort match {
        case TagSorting.NumKeeps => allTags.sortBy(_._2)(Ordering[Int].reverse)
        case TagSorting.Name => allTags.sortBy(_._1.tag.toLowerCase)
        case TagSorting.LastKept => allTags
      }).collect {
        case r if r._2 > 0 =>
          FakedBasicCollection.fromTag(r._1, Some(r._2))
      }
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
  def getTagInfoForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[TagInfo]] = {
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
    val colIdKeepIds = ExternalId.asOpt[Collection](tag.tag).flatMap(cid => collectionRepo.getByUserAndExternalId(userId, cid)).map { c =>
      keepRepo.getByUserAndCollection(userId, c.id.get, None, None, 1000).map(_.id.get)
    }.getOrElse(Seq.empty)

    keepTagRepo.getAllByTagAndUser(tag, userId).map(_.keepId) ++ collKeepIds ++ colIdKeepIds
  }

  // Does not update note field, only tags that came from notes! Use KeepMutator.updateKeepNote for note updating needs.
  def removeTagsFromKeepsNote(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {

    val ktRemovals = keepTagRepo.removeTagsFromKeepNotes(keepIds, tags)

    var colRemovals = 0
    val normalizedTags = tags.map(_.normalized).toSet
    val collections = mutable.Map.empty[Id[Collection], Collection]
    keepIds.flatMap { keepId =>
      keepToCollectionRepo.getByKeep(keepId).map { ktc =>
        val coll = collections.getOrElseUpdate(ktc.collectionId, collectionRepo.get(ktc.collectionId))
        if (normalizedTags.contains(coll.name.normalized)) {
          keepToCollectionRepo.deactivate(ktc)
          colRemovals += 1
          Some(ktc.collectionId)
        } else None
      }
    }.toSet.flatten.foreach { cid: Id[Collection] =>
      collectionRepo.collectionChanged(cid, isNewKeep = false, inactivateIfEmpty = true)
    }

    ktRemovals + colRemovals
  }

  def removeTagsFromKeepsByUser(userId: Id[User], keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {

    val ktRemovals = keepTagRepo.removeTagsFromKeepByUser(userId, keepIds, tags)

    var colRemovals = 0
    val normalizedTags = tags.map(_.normalized).toSet
    val collections = mutable.Map.empty[Id[Collection], Collection]
    keepIds.flatMap { keepId =>
      keepToCollectionRepo.getByKeep(keepId).map { ktc =>
        val coll = collections.getOrElseUpdate(ktc.collectionId, collectionRepo.get(ktc.collectionId))
        if (coll.userId == userId && normalizedTags.contains(coll.name.normalized)) {
          keepToCollectionRepo.deactivate(ktc)
          colRemovals += 1
          Some(ktc.collectionId)
        } else None
      }
    }.toSet.flatten.foreach { cid: Id[Collection] =>
      collectionRepo.collectionChanged(cid, isNewKeep = false, inactivateIfEmpty = true)
    }
    ktRemovals + colRemovals
  }

  def removeTagsFromKeepsByMessage(messageId: Id[Message], keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {
    // No message tags in the old Collection table!
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

    val ktRemovals = keepTagRepo.removeTagsFromKeep(keepIds, tags)

    var colRemovals = 0
    val normalizedTags = tags.map(_.normalized).toSet
    val collections = mutable.Map.empty[Id[Collection], Collection]
    keepIds.flatMap { keepId =>
      keepToCollectionRepo.getByKeep(keepId).map { ktc =>
        val coll = collections.getOrElseUpdate(ktc.collectionId, collectionRepo.get(ktc.collectionId))
        if (normalizedTags.contains(coll.name.normalized)) {
          keepToCollectionRepo.deactivate(ktc)
          colRemovals += 1
          Some(ktc.collectionId)
        } else None
      }
    }.toSet.flatten.foreach { cid: Id[Collection] =>
      collectionRepo.collectionChanged(cid, isNewKeep = false, inactivateIfEmpty = true)
    }

    ktRemovals + colRemovals
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
      tagCommander.removeTagsFromKeepsByUser(userId, keepIds, Seq(tag))
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
