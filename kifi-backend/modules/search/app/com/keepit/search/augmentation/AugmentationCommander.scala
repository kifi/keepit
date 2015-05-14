package com.keepit.search.augmentation

import com.keepit.model.{ Hashtag, Library, NormalizedURI, User }
import com.keepit.common.db.Id
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.search.index.graph.keep.{ KeepRecord, ShardedKeepIndexer, KeepFields }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.index.Term
import com.keepit.search.index.{ Searcher, WrappedSubReader }
import scala.collection.JavaConversions._
import com.keepit.search.util.LongArraySet
import com.keepit.search.index.graph.library.LibraryFields.Visibility.{ SECRET, DISCOVERABLE, PUBLISHED }
import scala.collection.mutable.{ ListBuffer, Map => MutableMap, Set => MutableSet }
import com.keepit.search.index.sharding.{ ActiveShards, Sharding, Shard }
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.zookeeper.ServiceInstance
import com.keepit.common.logging.Logging
import com.keepit.search.engine.{ LibraryQualityEvaluator, SearchFactory }
import com.keepit.common.core._
import com.keepit.search.{ LibraryContext, DistributedSearchServiceClient }
import com.keepit.search.augmentation.AugmentationCommander.DistributionPlan
import com.keepit.search.index.graph.library.{ LibraryIndexable, LibraryIndexer }

object AugmentationCommander {
  type DistributionPlan = (Set[Shard[NormalizedURI]], Seq[(ServiceInstance, Set[Shard[NormalizedURI]])])
}

@ImplementedBy(classOf[AugmentationCommanderImpl])
trait AugmentationCommander {
  def augmentation(itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse]
  def distAugmentation(shards: Set[Shard[NormalizedURI]], itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse]
  def getAugmentedItems(itemAugmentationRequest: ItemAugmentationRequest): Future[Map[AugmentableItem, AugmentedItem]]
}

@Singleton
class AugmentationCommanderImpl @Inject() (
    activeShards: ActiveShards,
    shardedKeepIndexer: ShardedKeepIndexer,
    libraryIndexer: LibraryIndexer,
    searchFactory: SearchFactory,
    libraryQualityEvaluator: LibraryQualityEvaluator,
    val searchClient: DistributedSearchServiceClient) extends AugmentationCommander with Sharding with Logging {

  def getAugmentedItems(itemAugmentationRequest: ItemAugmentationRequest): Future[Map[AugmentableItem, AugmentedItem]] = {
    val futureAugmentationResponse = augmentation(itemAugmentationRequest)
    val userId = itemAugmentationRequest.context.userId
    val futureFriends = searchFactory.getSearchFriends(userId).imap(_.map(Id[User](_)))
    val futureLibraries = searchFactory.getLibraryIdsFuture(userId, LibraryContext.None).imap(_._2.map(Id[Library](_)))
    for {
      augmentationResponse <- futureAugmentationResponse
      friends <- futureFriends
      libraries <- futureLibraries
    } yield {
      augmentationResponse.infos.map { case (item, info) => item -> AugmentedItem(userId, friends, libraries, augmentationResponse.scores)(item, info) }
    }
  }

  def augmentation(itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    log.info(s"Processing $itemAugmentationRequest")
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
      augmentationResponses.foldLeft(ItemAugmentationResponse.empty) { (mergedResponse, nextResponse) =>
        ItemAugmentationResponse(mergedResponse.infos ++ nextResponse.infos, mergedResponse.scores merge nextResponse.scores)
      }
    }
  }

  def distAugmentation(shards: Set[Shard[NormalizedURI]], itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    if (shards.isEmpty) Future.successful(ItemAugmentationResponse.empty)
    else {
      val ItemAugmentationRequest(items, context, showPublishedLibraries) = itemAugmentationRequest

      // todo(Léo): clean up these sets to avoid back and forth conversions between typed Ids and Long

      val futureLibraryFilter = searchFactory.getLibraryIdsFuture(context.userId, LibraryContext.None).imap {
        case (_, followedLibraries, _, _) => followedLibraries.map(Id[Library](_))
      }

      val futureUserFilter = searchFactory.getSearchFriends(context.userId).imap(_.map(Id[User](_)) + context.userId)

      val futureRestrictedUserIds = searchFactory.getRestrictedUsers(Some(context.userId).filter(_.id >= 0)).imap(_.map(Id[User](_)))

      for {
        libraryFilter <- futureLibraryFilter
        userFilter <- futureUserFilter
        restrictedUserIds <- futureRestrictedUserIds
        allAugmentationInfos <- getAugmentationInfos(shards, libraryFilter, userFilter, restrictedUserIds, items ++ context.corpus.keySet, showPublishedLibraries.exists(identity))
      } yield {
        val contextualAugmentationInfos = context.corpus.collect { case (item, weight) if allAugmentationInfos.contains(item) => (allAugmentationInfos(item) -> weight) }
        val contextualScores = computeAugmentationScores(contextualAugmentationInfos)
        val relevantAugmentationInfos = items.collect { case item if allAugmentationInfos.contains(item) => item -> allAugmentationInfos(item) }.toMap
        ItemAugmentationResponse(relevantAugmentationInfos, contextualScores)
      }
    }
  }

  private def getAugmentationInfos(shards: Set[Shard[NormalizedURI]], libraryFilter: Set[Id[Library]], userFilter: Set[Id[User]], restrictedUserIds: Set[Id[User]], items: Set[AugmentableItem], showPublishedLibraries: Boolean): Future[Map[AugmentableItem, FullAugmentationInfo]] = {
    val userIdFilter = LongArraySet.fromSet(userFilter.map(_.id))
    val restrictedUserIdFilter = LongArraySet.fromSet(restrictedUserIds.map(_.id))
    val libraryIdFilter = LongArraySet.fromSet(libraryFilter.map(_.id))
    val futureAugmentationInfosByShard: Seq[Future[Map[AugmentableItem, FullAugmentationInfo]]] = items.groupBy(item => shards.find(_.contains(item.uri))).collect {
      case (Some(shard), itemsInShard) =>
        SafeFuture {
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher
          val librarySearcher = libraryIndexer.getSearcher
          itemsInShard.map { item => item -> getAugmentationInfo(keepSearcher, librarySearcher, userIdFilter, restrictedUserIdFilter, libraryIdFilter, showPublishedLibraries)(item) }.toMap
        }
    }.toSeq
    Future.sequence(futureAugmentationInfosByShard).map(_.foldLeft(Map.empty[AugmentableItem, FullAugmentationInfo])(_ ++ _))
  }

  // todo(Léo): this is currently very much unrestricted in order to push for library discovery
  private def showThisPublishedLibrary(librarySearcher: Searcher, libraryId: Long): Boolean = {
    LibraryIndexable.getName(librarySearcher, libraryId).exists { name =>
      !libraryQualityEvaluator.isPoorlyNamed(name)
    }
  }

  private def getAugmentationInfo(keepSearcher: Searcher, librarySearcher: Searcher, userIdFilter: LongArraySet, restrictedUserIdFilter: LongArraySet, libraryIdFilter: LongArraySet, showPublishedLibraries: Boolean)(item: AugmentableItem): FullAugmentationInfo = {
    val uriTerm = new Term(KeepFields.uriField, item.uri.id.toString)
    val keeps = new ListBuffer[RestrictedKeepInfo]()
    var otherPublishedKeeps = 0
    var otherDiscoverableKeeps = 0
    val uniqueKeepers = MutableSet[Long]() // todo(Léo, Yasu): This won't scale with very popular pages, will have to implement something like HyperLogLog counting

    (keepSearcher.indexReader.getContext.leaves()).foreach { atomicReaderContext =>
      val reader = atomicReaderContext.reader().asInstanceOf[WrappedSubReader]
      val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
      val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
      val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
      val recordDocValue = reader.getBinaryDocValues(KeepFields.recordField)
      val docs = reader.termDocsEnum(uriTerm)

      def getKeepRecord(docId: Int): KeepRecord = {
        val ref = recordDocValue.get(docId)
        KeepRecord.fromByteArray(ref.bytes, ref.offset, ref.length)
      }

      if (docs != null) {
        var docId = docs.nextDoc()
        while (docId < NO_MORE_DOCS) {

          val libraryId = libraryIdDocValues.get(docId)
          val userId = userIdDocValues.get(docId)
          val visibility = visibilityDocValues.get(docId)

          if (item.keptIn.isDefined && item.keptIn.get.id == libraryId) { // canonical keep, get note
            val record = getKeepRecord(docId)
            uniqueKeepers += userId
            keeps += RestrictedKeepInfo(record.externalId, Some(record.keptAt), Some(Id(libraryId)), Some(Id(userId)), record.note, record.tags)
          } else if (libraryIdFilter.findIndex(libraryId) >= 0) { // kept in my libraries
            val record = getKeepRecord(docId)
            uniqueKeepers += userId
            keeps += RestrictedKeepInfo(record.externalId, Some(record.keptAt), Some(Id(libraryId)), Some(Id(userId)), None, record.tags) // todo(Léo): Revisit user attribution for collaborative libraries (currently contributor == library owner)
          } else if (userIdFilter.findIndex(userId) >= 0) visibility match { // kept by my friends
            case PUBLISHED =>
              val record = getKeepRecord(docId)
              uniqueKeepers += userId
              keeps += RestrictedKeepInfo(record.externalId, Some(record.keptAt), Some(Id(libraryId)), Some(Id(userId)), None, record.tags)
            case DISCOVERABLE =>
              val record = getKeepRecord(docId)
              uniqueKeepers += userId
              keeps += RestrictedKeepInfo(record.externalId, Some(record.keptAt), None, Some(Id(userId)), None, Set.empty)
            case SECRET => // ignore
          }
          else if (restrictedUserIdFilter.findIndex(userId) < 0) visibility match { // kept by others
            case PUBLISHED =>
              uniqueKeepers += userId
              if (showPublishedLibraries && showThisPublishedLibrary(librarySearcher, libraryId)) {
                val record = getKeepRecord(docId)
                keeps += RestrictedKeepInfo(record.externalId, Some(record.keptAt), Some(Id(libraryId)), Some(Id(userId)), None, record.tags)
              } else {
                otherPublishedKeeps += 1
              }
            case DISCOVERABLE =>
              uniqueKeepers += userId
              otherDiscoverableKeeps += 1
            case SECRET => // ignore
          }

          docId = docs.nextDoc()
        }
      }
    }
    FullAugmentationInfo(keeps.toList, otherPublishedKeeps, otherDiscoverableKeeps, uniqueKeepers.size)
  }

  private def computeAugmentationScores(weightedAugmentationInfos: Iterable[(FullAugmentationInfo, Float)]): AugmentationScores = {
    val libraryScores = MutableMap[Id[Library], Float]() withDefaultValue 0f
    val userScores = MutableMap[Id[User], Float]() withDefaultValue 0f
    val tagScores = MutableMap[Hashtag, Float]() withDefaultValue 0f

    weightedAugmentationInfos.foreach {
      case (info, weight) =>
        (info.keeps).foreach {
          case RestrictedKeepInfo(_, _, libraryIdOpt, userIdOpt, _, tags) =>
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
