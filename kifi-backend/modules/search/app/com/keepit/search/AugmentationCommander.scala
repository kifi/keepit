package com.keepit.search

import com.keepit.model.{ Hashtag, Library, NormalizedURI, User }
import com.keepit.common.db.Id
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.search.graph.keep.{ KeepRecord, ShardedKeepIndexer, KeepFields }
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
import org.apache.lucene.util.BytesRef
import com.keepit.search.engine.SearchFactory
import com.keepit.common.core._
object AugmentationCommander {
  type DistributionPlan = (Set[Shard[NormalizedURI]], Seq[(ServiceInstance, Set[Shard[NormalizedURI]])])
}

@ImplementedBy(classOf[AugmentationCommanderImpl])
trait AugmentationCommander {
  def augmentation(itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse]
  def distAugmentation(shards: Set[Shard[NormalizedURI]], itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse]
}

class AugmentationCommanderImpl @Inject() (
    activeShards: ActiveShards,
    shardedKeepIndexer: ShardedKeepIndexer,
    searchFactory: SearchFactory,
    val searchClient: SearchServiceClient) extends AugmentationCommander with Sharding with Logging {

  def augmentation(itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    val uris = (itemAugmentationRequest.context.corpus.keySet ++ itemAugmentationRequest.items).map(_.uri)
    val restrictedPlan = getRestrictedDistributionPlan(itemAugmentationRequest.context.userId, uris)
    plannedAugmentation(restrictedPlan, itemAugmentationRequest)
  }

  private def getRestrictedDistributionPlan(userId: Id[User], uris: Set[Id[NormalizedURI]]): DistributionPlan = {
    val (localShards, remotePlan) = distributionPlan(userId, activeShards)
    val relevantShards = activeShards.all.filter { shard => uris.exists(shard.contains(_)) }
    val relevantLocalShards = localShards intersect relevantShards
    val relevantRemotePlan = remotePlan.map { case (instance, shards) => (instance, shards intersect relevantShards) }.filter(_._2.nonEmpty)
    (relevantLocalShards, relevantRemotePlan)
  }

  private def plannedAugmentation(plan: DistributionPlan, request: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    val (localShards, remotePlan) = plan
    val futureRemoteAugmentationResponses = searchClient.distAugmentation(remotePlan, request)
    val futureLocalAugmentationResponse = distAugmentation(localShards, request)
    Future.sequence(futureRemoteAugmentationResponses :+ futureLocalAugmentationResponse).map { augmentationResponses =>
      augmentationResponses.reduceLeft { (mergedResponse, nextResponse) =>
        ItemAugmentationResponse(mergedResponse.infos ++ nextResponse.infos, mergedResponse.scores merge nextResponse.scores)
      }
    }
  }

  def distAugmentation(shards: Set[Shard[NormalizedURI]], itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    if (shards.isEmpty) Future.successful(ItemAugmentationResponse.empty)
    else {
      val ItemAugmentationRequest(items, context) = itemAugmentationRequest

      val futureLibraryFilter = context.libraryFilter match {
        case Some(libraryIds) => Future.successful(libraryIds)
        case None => searchFactory.getLibraryIdsFuture(context.userId, None).imap {
          case (ownedLibraries, followedLibraries, trustedLibraries) =>
            (ownedLibraries ++ followedLibraries ++ trustedLibraries).map(Id[Library](_))
        }
      }

      val futureUserFilter = context.userFilter match {
        case Some(userIds) => Future.successful(userIds)
        case None => searchFactory.getFriendIdsFuture(context.userId).imap(_.map(Id[User](_)) + context.userId)
      }

      for {
        libraryFilter <- futureLibraryFilter
        userFilter <- futureUserFilter
        allAugmentationInfos <- getAugmentationInfos(shards, context.userId, libraryFilter, userFilter, items ++ context.corpus.keySet)
      } yield {
        val contextualAugmentationInfos = context.corpus.collect { case (item, weight) if allAugmentationInfos.contains(item) => (allAugmentationInfos(item) -> weight) }
        val contextualScores = computeAugmentationScores(contextualAugmentationInfos)
        val relevantAugmentationInfos = items.collect { case item if allAugmentationInfos.contains(item) => item -> allAugmentationInfos(item) }.toMap
        ItemAugmentationResponse(relevantAugmentationInfos, contextualScores)
      }
    }
  }

  private def getAugmentationInfos(shards: Set[Shard[NormalizedURI]], userId: Id[User], libraryFilter: Set[Id[Library]], userFilter: Set[Id[User]], items: Set[Item]): Future[Map[Item, AugmentationInfo]] = {
    val userIdFilter = LongArraySet.fromSet(userFilter.map(_.id))
    val libraryIdFilter = LongArraySet.fromSet(libraryFilter.map(_.id))
    val futureAugmentationInfosByShard: Seq[Future[Map[Item, AugmentationInfo]]] = items.groupBy(item => shards.find(_.contains(item.uri))).collect {
      case (Some(shard), itemsInShard) =>
        SafeFuture {
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher
          itemsInShard.map { item => item -> getAugmentationInfo(keepSearcher, userIdFilter, libraryIdFilter)(item) }.toMap
        }
    }.toSeq
    Future.sequence(futureAugmentationInfosByShard).map(_.reduce(_ ++ _))
  }

  private def getAugmentationInfo(keepSearcher: Searcher, userIdFilter: LongArraySet, libraryIdFilter: LongArraySet)(item: Item): AugmentationInfo = {
    val uriTerm = new Term(KeepFields.uriField, item.uri.id.toString)
    val keeps = new ListBuffer[RestrictedKeepInfo]()
    var publishedKeeps = 0

    (keepSearcher.indexReader.getContext.leaves()).foreach { atomicReaderContext =>
      val reader = atomicReaderContext.reader().asInstanceOf[WrappedSubReader]
      val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
      val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
      val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
      val recordDocValue = reader.getBinaryDocValues(KeepFields.recordField)
      val docs = reader.termDocsEnum(uriTerm)

      def tags(docId: Int): Set[Hashtag] = {
        val ref = new BytesRef()
        recordDocValue.get(docId, ref)
        val record = KeepRecord.fromByteArray(ref.bytes, ref.offset, ref.length)
        record.tags
      }

      if (docs != null) {
        var docId = docs.nextDoc()
        while (docId < NO_MORE_DOCS) {

          val libraryId = libraryIdDocValues.get(docId)
          val userId = userIdDocValues.get(docId)
          val visibility = visibilityDocValues.get(docId)

          if (libraryIdFilter.findIndex(libraryId) >= 0 || (item.keptIn.isDefined && item.keptIn.get.id == libraryId)) { // kept in my libraries or preferred keep
            val userIdOpt = if (userIdFilter.findIndex(userId) >= 0) Some(Id[User](userId)) else None
            keeps += RestrictedKeepInfo(Some(Id(libraryId)), userIdOpt, tags(docId))
          } else if (userIdFilter.findIndex(userId) >= 0) visibility match { // kept by my friends
            case PUBLISHED => keeps += RestrictedKeepInfo(Some(Id(libraryId)), Some(Id(userId)), tags(docId))
            case DISCOVERABLE => keeps += RestrictedKeepInfo(None, Some(Id(userId)), Set.empty)
            case SECRET => // ignore
          }
          else if (visibility == PUBLISHED) { // kept in a public library
            publishedKeeps += 1
            //todo(Léo): define which published libraries are relevant
          }

          docId = docs.nextDoc()
        }
      }
    }
    AugmentationInfo(keeps.toList, publishedKeeps)
  }

  private def computeAugmentationScores(weigthedAugmentationInfos: Iterable[(AugmentationInfo, Float)]): AugmentationScores = {
    val libraryScores = MutableMap[Id[Library], Float]() withDefaultValue 0f
    val userScores = MutableMap[Id[User], Float]() withDefaultValue 0f
    val tagScores = MutableMap[Hashtag, Float]() withDefaultValue 0f

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
    AugmentationScores(libraryScores.toMap, userScores.toMap, tagScores.toMap)
  }
}
