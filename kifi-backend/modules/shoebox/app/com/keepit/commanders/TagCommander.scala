package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Provider }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.kifi.macros.json

import scala.collection.mutable
import scala.concurrent.ExecutionContext

/* Does NOT check permissions to edit */
@ImplementedBy(classOf[TagCommanderImpl])
trait TagCommander {
  def getTagsForKeeps(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[Hashtag]]
  def getCountForUser(userId: Id[User])(implicit session: RSession): Int
  def tagsForUser(userId: Id[User], offset: Int, pageSize: Int, sort: TagSorting): Seq[FakedBasicCollection]
  def removeTagsFromKeeps(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int
}

class TagCommanderImpl @Inject() (
    db: Database,
    collectionRepo: CollectionRepo,
    keepTagRepo: KeepTagRepo,
    userValueRepo: UserValueRepo,
    searchClient: SearchServiceClient,
    libraryAnalytics: LibraryAnalytics,
    basicCollectionCache: BasicCollectionByIdCache,
    keepToCollectionRepo: KeepToCollectionRepo,
    implicit val executionContext: ExecutionContext,
    keepCommander: Provider[KeepCommander],
    clock: Clock) extends TagCommander with Logging {

  def getCountForUser(userId: Id[User])(implicit session: RSession): Int = {
    collectionRepo.count(userId)
  }

  // todo: Plug in KeepTag.
  def tagsForUser(userId: Id[User], offset: Int, pageSize: Int, sort: TagSorting): Seq[FakedBasicCollection] = {
    db.readOnlyMaster { implicit s =>
      sort match {
        case TagSorting.NumKeeps => collectionRepo.getByUserSortedByNumKeeps(userId, offset, pageSize)
        case TagSorting.Name => collectionRepo.getByUserSortedByName(userId, offset, pageSize)
        case TagSorting.LastKept => collectionRepo.getByUserSortedByLastKept(userId, offset, pageSize)
      }
    }.map { case (collectionSummary, keepCount) => FakedBasicCollection.fromTag(collectionSummary.name, Some(keepCount)) }
  }

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

    combined.toMap.withDefaultValue(Seq.empty)
  }

  // Reminder: does not check permissions. Do that yourself before calling this!
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