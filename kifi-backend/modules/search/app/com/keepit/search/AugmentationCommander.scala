package com.keepit.search

import com.keepit.model.{ Library, NormalizedURI, User }
import com.keepit.common.db.Id
import com.keepit.search.Item.{ Tag }
import com.google.inject.Inject
import com.keepit.search.graph.keep.{ ShardedKeepIndexer, KeepFields }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.index.Term
import com.keepit.search.index.WrappedSubReader
import scala.collection.JavaConversions._
import com.keepit.search.util.LongArraySet
import com.keepit.search.graph.library.LibraryFields.Visibility.{ SECRET, DISCOVERABLE, PUBLISHED }
import scala.collection.mutable.{ ListBuffer, Map => MutableMap }
import com.keepit.search.sharding.{ ActiveShards, Sharding, Shard }
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.zookeeper.ServiceInstance
import com.keepit.search.AugmentationCommander.DistributionPlan
import com.keepit.common.logging.Logging

object AugmentationCommander {
  type DistributionPlan = (Set[Shard[NormalizedURI]], Seq[(ServiceInstance, Set[Shard[NormalizedURI]])])
}

trait AugmentationCommander {
  def augment(userId: Id[User], keptIn: Set[Id[Library]], keptBy: Set[Id[User]], item: Item): Future[AugmentedItem]
  def augment(plan: DistributionPlan, userId: Id[User], keptIn: Set[Id[Library]], keptBy: Set[Id[User]], weightedItems: Seq[(Item, Float)], offset: Int, limit: Int): Future[Seq[AugmentedItem]]
  def distAugmentation(shards: Set[Shard[NormalizedURI]], itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse]
}

class AugmentationCommanderImpl @Inject() (
    activeShards: ActiveShards,
    shardedKeepIndexer: ShardedKeepIndexer,
    val searchClient: SearchServiceClient) extends Sharding with Logging {

  def augment(userId: Id[User], keptIn: Set[Id[Library]], keptBy: Set[Id[User]], item: Item): Future[AugmentedItem] = {
    val (localShards, remotePlan) = distributionPlan(userId, activeShards)
    val relevantShard = activeShards.all.find(_.contains(item.uri)).get
    val restrictedPlan: DistributionPlan = {
      if (localShards.contains(relevantShard)) (Set(relevantShard), Seq.empty)
      else (Set.empty, remotePlan.find(_._2.contains(relevantShard)).map { case (relevantInstance, _) => (relevantInstance, Set(relevantShard)) }.toSeq)
    }
    augment(restrictedPlan, userId, keptIn, keptBy, Seq((item, 1)), 0, 1).map(_.head)
  }

  def augment(plan: DistributionPlan, userId: Id[User], keptIn: Set[Id[Library]], keptBy: Set[Id[User]], weightedItems: Seq[(Item, Float)], offset: Int, limit: Int): Future[Seq[AugmentedItem]] = {
    val items = weightedItems.drop(offset).take(limit).map(_._1)
    getAugmentationInfosAndScores(plan, userId, keptIn, keptBy, items.toSet, weightedItems.toMap).map {
      case (infos, scores) =>
        items.map { item => augmentItem(item, infos(item), scores) }
    }
  }

  private def getAugmentationInfosAndScores(plan: DistributionPlan, userId: Id[User], keptIn: Set[Id[Library]], keptBy: Set[Id[User]], items: Set[Item], context: Map[Item, Float]): Future[(Map[Item, AugmentationInfo], ContextualAugmentationScores)] = {
    val (localShards, remotePlan) = plan
    val request = ItemAugmentationRequest(userId, keptIn, keptBy, items, context)
    val futureRemoteAugmentationResponses = searchClient.distAugmentation(remotePlan, request)
    val futureLocalAugmentationResponse = distAugmentation(localShards, request)
    Future.sequence(futureRemoteAugmentationResponses :+ futureLocalAugmentationResponse).map { augmentationResponses =>
      val mergedResponse = augmentationResponses.reduceLeft { (mergedResponse, nextResponse) =>
        ItemAugmentationResponse(mergedResponse.infos ++ nextResponse.infos, mergedResponse.scores merge nextResponse.scores)
      }
      (mergedResponse.infos, mergedResponse.scores)
    }
  }

  private def augmentItem(item: Item, info: AugmentationInfo, augmentationScores: ContextualAugmentationScores): AugmentedItem = {
    val kept = item.keptIn.flatMap { libraryId =>
      info.keeps.find(_.keptIn == Some(libraryId)).map { keepInfo =>
        val sortedTags = keepInfo.tags.sortBy(augmentationScores.tagScores.getOrElse(_, 0f))
        val userIdOpt = keepInfo.keptBy
        (libraryId, userIdOpt, sortedTags)
      }
    }

    val (allKeeps, allTags) = info.keeps.foldLeft(Set.empty[(Option[Id[Library]], Option[Id[User]])], Set.empty[Tag]) {
      case ((moreKeeps, moreTags), RestrictedKeepInfo(libraryIdOpt, userIdOpt, tags)) => (moreKeeps + ((libraryIdOpt, userIdOpt)), moreTags ++ tags)
    }

    val (moreKeeps, moreTags) = kept match {
      case Some((libraryId, userIdOpt, tags)) => (allKeeps - ((Some(libraryId), userIdOpt)), allTags -- tags)
      case None => (allKeeps, allTags)
    }

    val moreSortedKeeps = moreKeeps.toSeq.sortBy {
      case (libraryIdOpt, userIdOpt) => (
        libraryIdOpt.flatMap(augmentationScores.libraryScores.get) getOrElse 0f,
        userIdOpt.flatMap(augmentationScores.userScores.get) getOrElse 0f
      )
    }
    val moreSortedTags = moreTags.toSeq.sortBy(augmentationScores.tagScores.getOrElse(_, 0f))
    AugmentedItem(item.uri, kept, moreSortedKeeps, moreSortedTags)
  }

  def distAugmentation(shards: Set[Shard[NormalizedURI]], itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    val ItemAugmentationRequest(userId, keptIn, keptBy, items, context) = itemAugmentationRequest
    getShardedAugmentationInfos(shards, userId, keptIn, keptBy, items ++ context.keySet).map { allAugmentationInfos =>
      val contextualAugmentationInfos = context.collect { case (item, weight) if allAugmentationInfos.contains(item) => (allAugmentationInfos(item) -> weight) }
      val contextualScores = computeAugmentationScores(contextualAugmentationInfos)
      val relevantAugmentationInfos = items.collect { case item if allAugmentationInfos.contains(item) => item -> allAugmentationInfos(item) }.toMap
      ItemAugmentationResponse(relevantAugmentationInfos, contextualScores)
    }
  }

  private def getShardedAugmentationInfos(shards: Set[Shard[NormalizedURI]], userId: Id[User], keptIn: Set[Id[Library]], keptBy: Set[Id[User]], items: Set[Item]): Future[Map[Item, AugmentationInfo]] = {
    if (shards.isEmpty) Future.successful(Map.empty)
    else {
      val userIdFilter = LongArraySet.fromSet(keptBy.map(_.id))
      val libraryIdFilter = LongArraySet.fromSet(keptIn.map(_.id))
      val futureAugmentationInfosByShard: Seq[Future[Map[Item, AugmentationInfo]]] = items.groupBy(item => shards.find(_.contains(item.uri))).collect {
        case (Some(shard), itemsInShard) =>
          SafeFuture {
            val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher
            itemsInShard.map { item => item -> getAugmentationInfo(keepSearcher, userIdFilter, libraryIdFilter)(item) }.toMap
          }
      }.toSeq
      Future.sequence(futureAugmentationInfosByShard).map(_.reduce(_ ++ _))
    }
  }

  private def getAugmentationInfo(keepSearcher: Searcher, userIdFilter: LongArraySet, libraryIdFilter: LongArraySet)(item: Item): AugmentationInfo = {
    val uriTerm = new Term(KeepFields.uriField, item.uri.id.toString)
    val keeps = new ListBuffer[RestrictedKeepInfo]()

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

        if (libraryIdFilter.findIndex(libraryId) >= 0 || (item.keptIn.isDefined && item.keptIn.get.id == libraryId)) { // kept in my libraries or preferred keep
          val userIdOpt = if (userIdFilter.findIndex(userId) >= 0) Some(Id[User](userId)) else None
          keeps += RestrictedKeepInfo(Some(Id(libraryId)), userIdOpt, tags)
        } else if (userIdFilter.findIndex(userId) >= 0) visibility match { // kept by my friends
          case PUBLISHED => keeps += RestrictedKeepInfo(Some(Id(libraryId)), Some(Id(userId)), tags)
          case DISCOVERABLE => keeps += RestrictedKeepInfo(None, Some(Id(userId)), Seq.empty)
          case SECRET => // ignore
        }
        else if (visibility == PUBLISHED) { // kept in a public library
          //todo(Léo): define which published libraries are relevant
        }

        docId = docs.nextDoc()
      }
    }
    AugmentationInfo(keeps.toList)
  }

  private def computeAugmentationScores(weigthedAugmentationInfos: Iterable[(AugmentationInfo, Float)]): ContextualAugmentationScores = {
    val libraryScores = MutableMap[Id[Library], Float]() withDefaultValue 0f
    val userScores = MutableMap[Id[User], Float]() withDefaultValue 0f
    val tagScores = MutableMap[Tag, Float]() withDefaultValue 0f

    weigthedAugmentationInfos.foreach {
      case (info, weight) =>
        (info.keeps).foreach {
          case RestrictedKeepInfo(libraryIdOpt, userIdOpt, tags) =>
            libraryIdOpt.foreach { libraryId => libraryScores(libraryId) = libraryScores(libraryId) + weight }
            userIdOpt.foreach { userId => userScores(userId) = userScores(userId) + weight }
            tags.foreach { tag =>
              tagScores(tag) = tagScores(tag) + weight
            }
        }
    }
    ContextualAugmentationScores(libraryScores.toMap, userScores.toMap, tagScores.toMap)
  }
}
