package com.keepit.search.augmentation

import com.keepit.common.db.Id
import com.keepit.model.{ Organization, NormalizedURI, Library, User }
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.library.LibraryIndexable
import com.keepit.slack.models.SlackTeamId
import scala.collection.mutable.{ ListBuffer }
import com.keepit.common.CollectionHelpers

class AugmentedItem(userId: Id[User], allFriends: Set[Id[User]], allOrganizations: Set[Id[Organization]], val allSlackTeamIds: Set[SlackTeamId], allLibraries: Set[Id[Library]], scores: AugmentationScores)(item: AugmentableItem, info: FullAugmentationInfo) {
  def uri: Id[NormalizedURI] = item.uri
  def keep = primaryKeep
  def isSecret(librarySearcher: Searcher) = if (myKeeps.isEmpty) None else Some(myKeeps.flatMap(_.libraries).forall(LibraryIndexable.isSecret(librarySearcher, _)))

  // Keeps
  private lazy val primaryKeep = item.keepId.flatMap { keepId => info.keeps.find(_.id == keepId) }
  lazy val (myKeeps, moreKeeps) = AugmentedItem.sortKeeps(userId, allFriends, allOrganizations, allLibraries, scores, info.keeps)
  lazy val keeps = myKeeps ++ moreKeeps
  def otherPublishedKeeps: Int = info.otherPublishedKeeps
  def otherDiscoverableKeeps: Int = info.otherDiscoverableKeeps
  def keepsTotal: Int = keeps.length + otherPublishedKeeps + otherDiscoverableKeeps

  // Libraries

  lazy val libraries = {
    val candidates = for {
      k <- keeps
      o <- k.owner.toSeq // I guess technically we should be using addedBy here
      l <- k.libraries
    } yield (l, o, k.keptAt)
    CollectionHelpers.dedupBy(candidates)(_._1)
  }

  def librariesTotal = info.librariesTotal

  // Keepers

  lazy val keepers = CollectionHelpers.dedupBy(keeps.flatMap(keep => keep.owner.map((_, keep.keptAt))))(_._1)

  lazy val (relatedKeepers, otherKeepers) = keepers.partition { case (keeperId, _) => allFriends.contains(keeperId) || userId == keeperId }

  def keepersTotal = info.keepersTotal

  // Tags
  private lazy val primaryTags = primaryKeep.toSeq.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_)))
  private lazy val myTags = myKeeps.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_)))
  private lazy val moreTags = moreKeeps.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_))).toSeq

  def tags = CollectionHelpers.dedupBy(myTags ++ primaryTags.filterNot(_.isSensitive) ++ moreTags.filterNot(_.isSensitive))(_.normalized)

  def toLimitedAugmentationInfo(maxKeepsShown: Int, maxKeepersShown: Int, maxLibrariesShown: Int, maxTagsShown: Int) = {

    val keep = primaryKeep
    val keepsShown = keeps.take(maxKeepsShown)
    val keepsOmitted = keeps.size - keepsShown.size

    val keepersShown = relatedKeepers.take(maxKeepersShown)
    val keepersOmitted = relatedKeepers.size - keepersShown.size

    val librariesShown = libraries.take(maxLibrariesShown)
    val librariesOmitted = libraries.size - librariesShown.size

    val tagsShown = tags.take(maxTagsShown)
    val tagsOmitted = tags.size - tagsShown.size

    LimitedAugmentationInfo(keep, keepsShown, keepsOmitted, keepsTotal, keepersShown, keepersOmitted, keepersTotal, librariesShown, librariesOmitted, librariesTotal, tagsShown, tagsOmitted)
  }
}

object AugmentedItem {
  private[AugmentedItem] def sortKeeps(userId: Id[User], friends: Set[Id[User]], organizations: Set[Id[Organization]], libraries: Set[Id[Library]], scores: AugmentationScores, keeps: Seq[KeepDocument]) = { // this method should be stable

    val sortedKeeps = keeps.sortBy(keep => (keep.owner.map(-scores.byUser(_)), keep.libraries.map(-scores.byLibrary(_)).sum)) // sort primarily by most relevant user

    val myKeeps = new ListBuffer[KeepDocument]()
    val keepsFromMyLibraries = new ListBuffer[KeepDocument]()
    val keepsFromMyOrgs = new ListBuffer[KeepDocument]()
    val keepsFromMyNetwork = new ListBuffer[KeepDocument]()
    val otherKeeps = new ListBuffer[KeepDocument]()
    sortedKeeps.foreach { keep =>
      val keepCategory = {
        if (keep.users.contains(userId)) myKeeps
        else if (keep.libraries.exists(libraries.contains)) keepsFromMyLibraries
        else if (keep.organizations.exists(organizations.contains)) keepsFromMyOrgs
        else if (keep.owner.exists(friends.contains)) keepsFromMyNetwork
        else otherKeeps
      }
      keepCategory += keep
    }
    val moreKeeps = keepsFromMyLibraries ++ keepsFromMyOrgs ++ keepsFromMyNetwork ++ otherKeeps
    (myKeeps.toList, moreKeeps.toList)
  }

  def apply(userId: Id[User], allFriends: Set[Id[User]], allOrganizations: Set[Id[Organization]], allSlackTeamIds: Set[SlackTeamId], allLibraries: Set[Id[Library]], scores: AugmentationScores)(item: AugmentableItem, info: FullAugmentationInfo) = {
    new AugmentedItem(userId, allFriends, allOrganizations, allSlackTeamIds, allLibraries, scores)(item, info)
  }
}
