package com.keepit.search.augmentation

import com.keepit.common.db.Id
import com.keepit.model.{ NormalizedURI, Library, User }
import com.keepit.search.{ Searcher }
import com.keepit.search.graph.library.LibraryIndexable
import play.api.libs.json.{ JsBoolean, Json, JsObject }
import scala.collection.mutable.{ ListBuffer }
import com.keepit.common.Collection

class AugmentedItem(userId: Id[User], allFriends: Set[Id[User]], allLibraries: Set[Id[Library]], scores: AugmentationScores)(item: AugmentableItem, info: AugmentationInfo) {
  def uri: Id[NormalizedURI] = item.uri
  def keep = primaryKeep
  def isSecret(librarySearcher: Searcher) = myKeeps.nonEmpty && myKeeps.flatMap(_.keptIn).forall(LibraryIndexable.isSecret(librarySearcher, _))

  // Keeps
  private lazy val primaryKeep = item.keptIn.flatMap { libraryId => info.keeps.find(_.keptIn == Some(libraryId)) }
  lazy val (myKeeps, moreKeeps) = AugmentedItem.sortKeeps(userId, allFriends, allLibraries, scores, info.keeps)
  lazy val keeps = myKeeps ++ moreKeeps
  def otherPublishedKeeps: Int = info.otherPublishedKeeps
  def otherDiscoverableKeeps: Int = info.otherDiscoverableKeeps

  // Libraries

  lazy val libraries = keeps.collect { case RestrictedKeepInfo(_, Some(libraryId), Some(keeperId), _) => (libraryId, keeperId) }

  // Keepers

  lazy val keepers = Collection.dedupBy(keeps.flatMap(_.keptBy))(identity)

  lazy val (relatedKeepers, otherKeepers) = keepers.partition(keeperId => allFriends.contains(keeperId) || userId == keeperId)

  def keepersTotal = info.keepersTotal

  // Tags
  private lazy val primaryTags = primaryKeep.toSeq.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_)))
  private lazy val myTags = myKeeps.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_)))
  private lazy val moreTags = moreKeeps.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_))).toSeq

  def tags = Collection.dedupBy(myTags ++ primaryTags.filterNot(_.isSensitive) ++ moreTags.filterNot(_.isSensitive))(_.normalized)
}

object AugmentedItem {
  private[AugmentedItem] def sortKeeps(userId: Id[User], friends: Set[Id[User]], libraries: Set[Id[Library]], scores: AugmentationScores, keeps: Seq[RestrictedKeepInfo]) = { // this method should be stable

    val sortedKeeps = keeps.sortBy(keep => (keep.keptBy.map(-scores.byUser(_)), keep.keptIn.map(-scores.byLibrary(_)))) // sort primarily by most relevant user

    val myKeeps = new ListBuffer[RestrictedKeepInfo]()
    val keepsFromMyLibraries = new ListBuffer[RestrictedKeepInfo]()
    val keepsFromMyFriends = new ListBuffer[RestrictedKeepInfo]()
    val otherKeeps = new ListBuffer[RestrictedKeepInfo]()
    sortedKeeps.foreach { keep =>
      val keepCategory = {
        if (keep.keptBy.exists(_ == userId)) myKeeps
        else if (keep.keptIn.exists(libraries.contains)) keepsFromMyLibraries
        else if (keep.keptBy.exists(friends.contains)) keepsFromMyFriends
        else otherKeeps
      }
      keepCategory += keep
    }
    val moreKeeps = keepsFromMyLibraries ++ keepsFromMyFriends ++ otherKeeps
    (myKeeps.toList, moreKeeps.toList)
  }

  def apply(userId: Id[User], allFriends: Set[Id[User]], allLibraries: Set[Id[Library]], scores: AugmentationScores)(item: AugmentableItem, info: AugmentationInfo) = {
    new AugmentedItem(userId, allFriends, allLibraries, scores)(item, info)
  }

  def writesAugmentationFields(librarySearcher: Searcher,
    userId: Id[User],
    maxKeepersShown: Int,
    maxLibrariesShown: Int,
    maxTagsShown: Int,
    augmentedItems: Seq[AugmentedItem]): (Seq[JsObject], Seq[Id[User]], Seq[Id[Library]]) = {
    val allKeepersShown = augmentedItems.map(_.relatedKeepers.take(maxKeepersShown))
    val allLibrariesShown = augmentedItems.map(_.libraries.take(maxLibrariesShown))

    val userIds = ((allKeepersShown.flatten ++ allLibrariesShown.flatMap(_.map(_._2))).toSet - userId).toSeq
    val userIndexById = userIds.zipWithIndex.toMap + (userId -> -1)

    val libraryIds = allLibrariesShown.flatMap(_.map(_._1)).distinct
    val libraryIndexById = libraryIds.zipWithIndex.toMap

    val augmentationFields = augmentedItems.zipWithIndex.map {
      case (augmentedItem, itemIndex) =>
        val secret = augmentedItem.isSecret(librarySearcher)

        val keepersShown = allKeepersShown(itemIndex).map(userIndexById(_))
        val keepersOmitted = augmentedItem.relatedKeepers.size - keepersShown.size
        val keepersTotal = augmentedItem.keepersTotal

        val librariesShown = allLibrariesShown(itemIndex).flatMap { case (libraryId, keeperId) => Seq(libraryIndexById(libraryId), userIndexById(keeperId)) }
        val librariesOmitted = augmentedItem.libraries.size - librariesShown.size / 2

        val tagsShown = augmentedItem.tags.take(maxTagsShown)
        val tagsOmitted = augmentedItem.tags.size - tagsShown.size

        Json.obj(
          "secret" -> JsBoolean(secret),
          "keepers" -> keepersShown,
          "keepersOmitted" -> keepersOmitted,
          "keepersTotal" -> keepersTotal,
          "libraries" -> librariesShown,
          "librariesOmitted" -> librariesOmitted,
          "tags" -> tagsShown,
          "tagsOmitted" -> tagsOmitted
        )
    }
    (augmentationFields, userIds, libraryIds)
  }
}
