package com.keepit.search

import com.keepit.model.{ Library, NormalizedURI, User }
import com.keepit.common.db.Id
import com.keepit.search.Item.{ Tag }
import com.google.inject.Inject
import com.keepit.search.graph.keep.{ KeepIndexer, KeepFields }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.index.Term
import com.keepit.search.index.WrappedSubReader
import scala.collection.JavaConversions._
import com.keepit.search.util.LongArraySet
import com.keepit.search.graph.library.LibraryFields.Visibility.{ SECRET, DISCOVERABLE, PUBLISHED }
import scala.collection.mutable.{ ListBuffer, Map => MutableMap }

object Item {
  type Tag = String
}

sealed trait Item {
  def uri: Id[NormalizedURI]
  def keptIn: Option[Id[Library]]
}

case class WeightedItem(uri: Id[NormalizedURI], keptIn: Option[Id[Library]], weight: Float) extends Item

case class AugmentedItem(
  uri: Id[NormalizedURI],
  keptIn: Option[Id[Library]],
  keptBy: Option[Id[User]],
  tags: Seq[Tag],
  moreKeeps: Seq[(Option[Id[Library]], Option[Id[User]])],
  moreTags: Seq[Tag]) extends Item

case class AugmentationInfo(
  preferredKeepWithTags: Option[(Some[Id[Library]], Option[Id[User]], Seq[Tag])],
  moreKeepsWithTags: List[(Option[Id[Library]], Option[Id[User]], Seq[Tag])])

trait AugmentationCommander {
  def augment(viewerId: Id[User], keptBy: Set[Id[User]], keptIn: Set[Id[Library]], item: Item): AugmentedItem
  def augment(viewerId: Id[User], keptBy: Set[Id[User]], keptIn: Set[Id[Library]], items: Seq[Item], offset: Int, limit: Int): Seq[AugmentedItem]
}

class AugmentationCommanderImpl @Inject() (keepIndexer: KeepIndexer) {

  def augment(viewerId: Id[User], keptBy: Set[Id[User]], keptIn: Set[Id[Library]], item: Item): AugmentedItem = {
    augment(viewerId, keptBy, keptIn, Seq(WeightedItem(item.uri, item.keptIn, 1)), 0, 1).head
  }

  def augment(viewerId: Id[User], keptBy: Set[Id[User]], keptIn: Set[Id[Library]], items: Seq[WeightedItem], offset: Int, limit: Int): Seq[AugmentedItem] = {
    val userIdFilter = LongArraySet.fromSet(keptBy.map(_.id))
    val libraryIdFilter = LongArraySet.fromSet(keptIn.map(_.id))
    val keepSearcher = keepIndexer.getSearcher
    val itemsWithAugmentationInfo = items.map(item => item -> getAugmentationInfo(keepSearcher, userIdFilter, libraryIdFilter)(item))
    val (libraryScores, userScores, tagScores) = getAugmentationScores(itemsWithAugmentationInfo)
    itemsWithAugmentationInfo.drop(offset).take(limit).map { case (item, info) => augmentItem(libraryScores, userScores, tagScores)(item, info) }
  }

  private def getAugmentationInfo(keepSearcher: Searcher, userIdFilter: LongArraySet, libraryIdFilter: LongArraySet)(item: Item): AugmentationInfo = {
    val uriTerm = new Term(KeepFields.uriField, item.uri.id.toString)
    var preferredKeep: Option[(Some[Id[Library]], Option[Id[User]], Seq[Tag])] = None
    val moreKeeps = new ListBuffer[(Option[Id[Library]], Option[Id[User]], Seq[Tag])]()

    (keepSearcher.indexReader.getContext.leaves()).foreach { atomicReaderContext =>
      val reader = atomicReaderContext.reader().asInstanceOf[WrappedSubReader]
      val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
      val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
      val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
      val docs = reader.termDocsEnum(uriTerm)

      var docId = docs.nextDoc()
      while (docId < NO_MORE_DOCS) {

        val libraryId = libraryIdDocValues.get(docId)
        val userId = userIdDocValues.get(docId)
        val visibility = visibilityDocValues.get(docId)
        val tags: Seq[String] = ??? //todo(Léo): implement

        if (item.keptIn.isDefined && item.keptIn.get.id == libraryId) { // preferred keep
          val userIdOpt = if (userIdFilter.findIndex(userId) >= 0) Some(Id[User](userId)) else None
          preferredKeep = Some((Some(Id(libraryId)), userIdOpt, tags))
        } else if (libraryIdFilter.findIndex(libraryId) >= 0) { // kept in my libraries
          val userIdOpt = if (userIdFilter.findIndex(userId) >= 0) Some(Id[User](userId)) else None
          moreKeeps += ((Some(Id(libraryId)), userIdOpt, tags))
        } else if (userIdFilter.findIndex(userId) >= 0) visibility match { // kept by my friends
          case PUBLISHED => moreKeeps += ((Some(Id(libraryId)), Some(Id(userId)), tags))
          case DISCOVERABLE => moreKeeps += ((None, Some(Id(userId)), Seq.empty))
          case SECRET => // ignore
        }
        else if (visibility == PUBLISHED) { // kept in a public library
          //todo(Léo): define which published libraries are relevant
        }

        docId = docs.nextDoc()
      }
    }
    AugmentationInfo(preferredKeep, moreKeeps.toList)
  }

  private def getAugmentationScores(itemsWithAugmentationInfo: Seq[(WeightedItem, AugmentationInfo)]): (Map[Id[Library], Float], Map[Id[User], Float], Map[String, Float]) = {
    val libraryStatistics = MutableMap[Id[Library], Float]() withDefaultValue 0f
    val userStatistics = MutableMap[Id[User], Float]() withDefaultValue 0f
    val tagStatistics = MutableMap[Tag, Float]() withDefaultValue 0f

    itemsWithAugmentationInfo.foreach {
      case (item, info) =>
        val weight = item.weight
        (info.preferredKeepWithTags.toList ::: info.moreKeepsWithTags).foreach {
          case (libraryIdOpt, userIdOpt, tags) =>
            libraryIdOpt.foreach { libraryId => libraryStatistics(libraryId) = libraryStatistics(libraryId) + weight }
            userIdOpt.foreach { userId => userStatistics(userId) = userStatistics(userId) + weight }
            tags.foreach { tag =>
              tagStatistics(tag) = tagStatistics(tag) + weight
            }
        }
    }
    (libraryStatistics.toMap, userStatistics.toMap, tagStatistics.toMap)
  }

  private def augmentItem(libraryScores: Map[Id[Library], Float], userScores: Map[Id[User], Float], tagScores: Map[String, Float])(item: Item, info: AugmentationInfo): AugmentedItem = {
    val (uniqueKeeps, uniqueTags) = info.moreKeepsWithTags.foldLeft(Set.empty[(Option[Id[Library]], Option[Id[User]])], Set.empty[Tag]) {
      case ((moreKeeps, moreTags), (libraryIdOpt, userIdOpt, tags)) => (moreKeeps + ((libraryIdOpt, userIdOpt)), moreTags ++ tags)
    }
    val preferredTags = (info.preferredKeepWithTags.map(_._3) getOrElse Seq.empty).sortBy(tagScores.getOrElse(_, 0f))
    val moreTags = (uniqueTags -- preferredTags).toSeq.sortBy(tagScores.getOrElse(_, 0f))
    val moreKeeps = {
      val sortedKeeps = uniqueKeeps.toSeq.sortBy { case (libraryIdOpt, userIdOpt) => (libraryIdOpt.flatMap(libraryScores.get) getOrElse 0f, userIdOpt.flatMap(userScores.get) getOrElse 0f) }
      var uniqueUsers = Set.empty[Id[User]]
      sortedKeeps.filter {
        case (libraryIdOpt, userIdOpt) =>
          val isUserAlreadyShown = userIdOpt.exists(uniqueUsers.contains)
          userIdOpt.foreach { userId => uniqueUsers += userId }
          isUserAlreadyShown
      }
    }
    AugmentedItem(
      item.uri,
      item.keptIn,
      info.preferredKeepWithTags.flatMap(_._2),
      preferredTags,
      moreKeeps,
      moreTags
    )
  }
}
