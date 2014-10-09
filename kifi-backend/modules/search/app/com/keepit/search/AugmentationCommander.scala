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
import scala.collection.mutable.{ ListBuffer, Map => MutableMap, Set => MutableSet }
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
import java.text.Normalizer
import com.keepit.controllers.util.SearchControllerUtil.BasicLibrary

object AugmentationCommander {
  type DistributionPlan = (Set[Shard[NormalizedURI]], Seq[(ServiceInstance, Set[Shard[NormalizedURI]])])
}

@ImplementedBy(classOf[AugmentationCommanderImpl])
trait AugmentationCommander {
  def augmentation(itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse]
  def distAugmentation(shards: Set[Shard[NormalizedURI]], itemAugmentationRequest: ItemAugmentationRequest): Future[ItemAugmentationResponse]
  def getAugmentedItems(itemAugmentationRequest: ItemAugmentationRequest): Future[Map[AugmentableItem, AugmentedItem]]
}

class AugmentationCommanderImpl @Inject() (
    activeShards: ActiveShards,
    shardedKeepIndexer: ShardedKeepIndexer,
    searchFactory: SearchFactory,
    val searchClient: DistributedSearchServiceClient) extends AugmentationCommander with Sharding with Logging {

  def getAugmentedItems(itemAugmentationRequest: ItemAugmentationRequest): Future[Map[AugmentableItem, AugmentedItem]] = {
    val futureAugmentationResponse = augmentation(itemAugmentationRequest)
    val userId = itemAugmentationRequest.context.userId
    val futureFriends = searchFactory.getFriendIdsFuture(userId).imap(_.map(Id[User](_)))
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
    val uniqueKeepers = MutableSet[Long]() // todo(Léo, Yasu): This won't scale with very popular pages, will have to implement something like HyperLogLog counting

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
            uniqueKeepers += userId
            keeps += RestrictedKeepInfo(record.externalId, Some(Id(libraryId)), Some(Id(userId)), record.tags) // todo(Léo): Revisit user attribution for collaborative libraries (currently contributor == library owner)
          } else if (userIdFilter.findIndex(userId) >= 0) visibility match { // kept by my friends
            case PUBLISHED =>
              val record = getKeepRecord(docId)
              uniqueKeepers += userId
              keeps += RestrictedKeepInfo(record.externalId, Some(Id(libraryId)), Some(Id(userId)), record.tags)
            case DISCOVERABLE =>
              val record = getKeepRecord(docId)
              uniqueKeepers += userId
              keeps += RestrictedKeepInfo(record.externalId, None, Some(Id(userId)), Set.empty)
            case SECRET => // ignore
          }
          else visibility match { // kept by others
            case PUBLISHED =>
              uniqueKeepers += userId
              otherPublishedKeeps += 1
            //todo(Léo): define which published libraries are relevant (should we count irrelevant library keeps in otherDiscoverableKeeps?)
            case DISCOVERABLE =>
              uniqueKeepers += userId
              otherDiscoverableKeeps += 1
            case SECRET => // ignore
          }

          docId = docs.nextDoc()
        }
      }
    }
    AugmentationInfo(keeps.toList, otherPublishedKeeps, otherDiscoverableKeeps, uniqueKeepers.size)
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

case class AugmentedItem(userId: Id[User], friendIds: Set[Id[User]], libraryIds: Set[Id[Library]], scores: AugmentationScores)(item: AugmentableItem, info: AugmentationInfo) {
  def uri: Id[NormalizedURI] = item.uri
  def keep = primaryKeep
  def isSecret(isSecretLibrary: Id[Library] => Boolean) = myKeeps.nonEmpty && myKeeps.flatMap(_.keptIn).forall(isSecretLibrary)

  // Keeps
  private lazy val primaryKeep = item.keptIn.flatMap { libraryId => info.keeps.find(_.keptIn == Some(libraryId)) }
  private lazy val sortedKeeps = info.keeps.sortBy(keep => (keep.keptBy.map(-scores.byUser(_)), keep.keptIn.map(-scores.byLibrary(_)))) // sort primarily by most relevant user
  lazy val (myKeeps, moreKeeps) = AugmentedItem.classifyKeeps(userId, friendIds, libraryIds, sortedKeeps)

  def keeps = myKeeps ++ moreKeeps
  def otherPublishedKeeps: Int = info.otherPublishedKeeps
  def otherDiscoverableKeeps: Int = info.otherDiscoverableKeeps

  // Libraries

  lazy val libraries = sortedKeeps.collect { case RestrictedKeepInfo(_, Some(libraryId), _, _) => libraryId }

  // Keepers
  lazy val keepers = {
    val uniqueKeepers = MutableSet[Id[User]]()
    sortedKeeps.collect {
      case RestrictedKeepInfo(_, _, Some(keeperId), _) if !uniqueKeepers.contains(keeperId) =>
        uniqueKeepers += keeperId
        keeperId
    }
  }
  lazy val friends = keepers.filter(friendIds.contains)

  // Tags
  private lazy val primaryTags = primaryKeep.toSeq.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_)))
  private lazy val myTags = myKeeps.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_)))
  private lazy val moreTags = moreKeeps.flatMap(_.tags.toSeq.sortBy(-scores.byTag(_))).toSeq

  def tags = {
    val uniqueNormalizedTags = MutableSet[String]()
    (myTags.iterator ++ primaryTags.iterator ++ moreTags.iterator).filter { tag =>
      val normalizedTag = AugmentedItem.normalizeTag(tag)
      val showTag = !uniqueNormalizedTags.contains(normalizedTag)
      uniqueNormalizedTags += normalizedTag
      showTag
    }.toSeq
  }
}

object AugmentedItem {
  private[AugmentedItem] def classifyKeeps(userId: Id[User], friends: Set[Id[User]], libraries: Set[Id[Library]], sortedKeeps: Seq[RestrictedKeepInfo]) = { // this method should be stable
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

  private val diacriticalMarksRegex = "\\p{InCombiningDiacriticalMarks}+".r
  @inline private[AugmentedItem] def normalizeTag(tag: Hashtag): String = diacriticalMarksRegex.replaceAllIn(Normalizer.normalize(tag.tag.trim, Normalizer.Form.NFD), "").toLowerCase

}
