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
    val searchClient: DistributedSearchServiceClient) extends AugmentationCommander with Sharding with Logging {

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
      augmentationResponses.foldLeft(ItemAugmentationResponse.empty) { (mergedResponse, nextResponse) =>
        ItemAugmentationResponse(mergedResponse.infos ++ nextResponse.infos, mergedResponse.scores merge nextResponse.scores)
      }
    }
  }

  def distAugmentation(shards: Set[Shard[NormalizedURI]], itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    if (shards.isEmpty) Future.successful(ItemAugmentationResponse.empty)
    else {
      val ItemAugmentationRequest(items, context) = itemAugmentationRequest

      val futureLibraryFilter = searchFactory.getLibraryIdsFuture(context.userId, LibraryContext.None).imap {
        case (_, followedLibraries, _, _) => followedLibraries.map(Id[Library](_))
      }

      val futureUserFilter = searchFactory.getFriendIdsFuture(context.userId).imap(_.map(Id[User](_)) + context.userId)

      for {
        libraryFilter <- futureLibraryFilter
        userFilter <- futureUserFilter
        allAugmentationInfos <- getAugmentationInfos(shards, libraryFilter, userFilter, items ++ context.corpus.keySet)
      } yield {
        val contextualAugmentationInfos = context.corpus.collect { case (item, weight) if allAugmentationInfos.contains(item) => (allAugmentationInfos(item) -> weight) }
        val contextualScores = computeAugmentationScores(contextualAugmentationInfos)
        val relevantAugmentationInfos = items.collect { case item if allAugmentationInfos.contains(item) => item -> allAugmentationInfos(item) }.toMap
        ItemAugmentationResponse(relevantAugmentationInfos, contextualScores)
      }
    }
  }

  private def getAugmentationInfos(shards: Set[Shard[NormalizedURI]], libraryFilter: Set[Id[Library]], userFilter: Set[Id[User]], items: Set[AugmentableItem]): Future[Map[AugmentableItem, AugmentationInfo]] = {
    val userIdFilter = LongArraySet.fromSet(userFilter.map(_.id))
    val libraryIdFilter = LongArraySet.fromSet(libraryFilter.map(_.id))
    val futureAugmentationInfosByShard: Seq[Future[Map[AugmentableItem, AugmentationInfo]]] = items.groupBy(item => shards.find(_.contains(item.uri))).collect {
      case (Some(shard), itemsInShard) =>
        SafeFuture {
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher
          itemsInShard.map { item => item -> getAugmentationInfo(keepSearcher, userIdFilter, libraryIdFilter)(item) }.toMap
        }
    }.toSeq
    Future.sequence(futureAugmentationInfosByShard).map(_.foldLeft(Map.empty[AugmentableItem, AugmentationInfo])(_ ++ _))
  }

  private def getAugmentationInfo(keepSearcher: Searcher, userIdFilter: LongArraySet, libraryIdFilter: LongArraySet)(item: AugmentableItem): AugmentationInfo = {
    val uriTerm = new Term(KeepFields.uriField, item.uri.id.toString)
    val keeps = new ListBuffer[RestrictedKeepInfo]()
    var otherPublishedKeeps = 0
    var otherDiscoverableKeeps = 0

    (keepSearcher.indexReader.getContext.leaves()).foreach { atomicReaderContext =>
      val reader = atomicReaderContext.reader().asInstanceOf[WrappedSubReader]
      val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
      val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
      val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
      val recordDocValue = reader.getBinaryDocValues(KeepFields.recordField)
      val docs = reader.termDocsEnum(uriTerm)

      def getKeepRecord(docId: Int): KeepRecord = {
        val ref = new BytesRef()
        recordDocValue.get(docId, ref)
        KeepRecord.fromByteArray(ref.bytes, ref.offset, ref.length)
      }

      if (docs != null) {
        var docId = docs.nextDoc()
        while (docId < NO_MORE_DOCS) {

          val libraryId = libraryIdDocValues.get(docId)
          val userId = userIdDocValues.get(docId)
          val visibility = visibilityDocValues.get(docId)

          if (libraryIdFilter.findIndex(libraryId) >= 0 || (item.keptIn.isDefined && item.keptIn.get.id == libraryId)) { // kept in my libraries or preferred keep
            val record = getKeepRecord(docId)
            keeps += RestrictedKeepInfo(record.externalId, Some(Id(libraryId)), Some(Id(userId)), record.tags) // todo(Léo): Revisit user attribution for collaborative libraries (currently contributor == library owner)
          } else if (userIdFilter.findIndex(userId) >= 0) visibility match { // kept by my friends
            case PUBLISHED =>
              val record = getKeepRecord(docId)
              keeps += RestrictedKeepInfo(record.externalId, Some(Id(libraryId)), Some(Id(userId)), record.tags)
            case DISCOVERABLE =>
              val record = getKeepRecord(docId)
              keeps += RestrictedKeepInfo(record.externalId, None, Some(Id(userId)), Set.empty)
            case SECRET => // ignore
          }
          else visibility match { // kept by others
            case PUBLISHED =>
              otherPublishedKeeps += 1
            //todo(Léo): define which published libraries are relevant (should we count irrelevant library keeps in otherDiscoverableKeeps?)
            case DISCOVERABLE =>
              otherDiscoverableKeeps += 1
            case SECRET => // ignore
          }

          docId = docs.nextDoc()
        }
      }
    }
    AugmentationInfo(keeps.toList, otherPublishedKeeps, otherDiscoverableKeeps)
  }

  private def computeAugmentationScores(weigthedAugmentationInfos: Iterable[(AugmentationInfo, Float)]): AugmentationScores = {
    val libraryScores = MutableMap[Id[Library], Float]() withDefaultValue 0f
    val userScores = MutableMap[Id[User], Float]() withDefaultValue 0f
    val tagScores = MutableMap[Hashtag, Float]() withDefaultValue 0f

    weigthedAugmentationInfos.foreach {
      case (info, weight) =>
        (info.keeps).foreach {
          case RestrictedKeepInfo(_, libraryIdOpt, userIdOpt, tags) =>
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

case class AugmentedItem(userId: Id[User], friends: Set[Id[User]], libraries: Set[Id[Library]], scores: AugmentationScores)(item: AugmentableItem, info: AugmentationInfo) {
  def uri: Id[NormalizedURI] = item.uri
  def otherPublishedKeeps: Int = info.otherPublishedKeeps
  def otherDiscoverableKeeps: Int = info.otherDiscoverableKeeps

  lazy val keep = item.keptIn.flatMap { libraryId =>
    info.keeps.find(_.keptIn == Some(libraryId)).map { keepInfo =>
      val sortedTags = keepInfo.tags.toSeq.sortBy(scores.byTag)
      val userIdOpt = keepInfo.keptBy
      (libraryId, userIdOpt, sortedTags)
    }
  }

  lazy private val (allKeeps, allTags) = info.keeps.foldLeft(Set.empty[(Option[Id[Library]], Option[Id[User]])], Set.empty[Hashtag]) {
    case ((moreKeeps, moreTags), RestrictedKeepInfo(_, libraryIdOpt, userIdOpt, tags)) => (moreKeeps + ((libraryIdOpt, userIdOpt)), moreTags ++ tags)
  }

  lazy private val (moreKeepsSet, moreTagsSet) = keep match {
    case Some((libraryId, userIdOpt, tags)) => (allKeeps - ((Some(libraryId), userIdOpt)), allTags -- tags)
    case None => (allKeeps, allTags)
  }

  lazy val moreKeeps = moreKeepsSet.toSeq.sortBy {
    case (libraryIdOpt, userIdOpt) => (-libraryIdOpt.map(scores.byLibrary).getOrElse(0f), -userIdOpt.map(scores.byUser).getOrElse(0f))
  }

  lazy val moreTags = moreTagsSet.toSeq.sortBy(-scores.byTag(_))
}
