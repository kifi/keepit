package com.keepit.search.augmentation

import com.keepit.model._
import com.keepit.common.db.Id
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.search.index.graph.keep.{ KeepRecord, ShardedKeepIndexer, KeepFields }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.index.{ BinaryDocValues, NumericDocValues, Term }
import com.keepit.search.index.{ DocUtil, Searcher, WrappedSubReader }
import scala.collection.JavaConversions._
import com.keepit.search.util.LongArraySet
import scala.collection.mutable
import scala.collection.mutable.{ ListBuffer, Map => MutableMap, Set => MutableSet }
import com.keepit.search.index.sharding.{ ActiveShards, Sharding, Shard }
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.zookeeper.ServiceInstance
import com.keepit.common.logging.Logging
import com.keepit.search.engine._
import com.keepit.common.core._
import com.keepit.search.{ SearchFilter, DistributedSearchServiceClient }
import com.keepit.search.augmentation.AugmentationCommander.DistributionPlan
import com.keepit.search.index.graph.library.{ LibraryIndexable, LibraryIndexer }
import com.keepit.common.time._

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

  // todo(Léo): update to use search scopes
  def getAugmentedItems(itemAugmentationRequest: ItemAugmentationRequest): Future[Map[AugmentableItem, AugmentedItem]] = {
    val futureAugmentationResponse = augmentation(itemAugmentationRequest)
    val userId = itemAugmentationRequest.context.userId
    val futureFriends = searchFactory.getSearchFriends(userId).imap(_.map(Id[User](_)))
    val futureLibraries = searchFactory.getLibraryIdsFuture(userId, None).imap(_._2.map(Id[Library](_)))
    val futureOrganizations = searchFactory.getOrganizations(userId, None).imap(_.map(Id[Organization](_)))
    val futureSlackTeamIds = searchFactory.getSlackTeamIds(userId, None)

    for {
      augmentationResponse <- futureAugmentationResponse
      friends <- futureFriends
      libraries <- futureLibraries
      organizations <- futureOrganizations
      slackTeamIds <- futureSlackTeamIds
    } yield {
      augmentationResponse.infos.map { case (item, info) => item -> AugmentedItem(userId, friends, organizations, slackTeamIds, libraries, augmentationResponse.scores, libraryIndexer.getSearcher)(item, info) }
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
      val ItemAugmentationRequest(items, context, hideOtherPublishedKeeps) = itemAugmentationRequest

      // todo(Léo): clean up these sets to avoid back and forth conversions between typed Ids and Long

      val userId = context.userId
      val futureFriends = searchFactory.getSearchFriends(userId).imap(_.map(Id[User](_)))
      val futureRestrictedUsers = searchFactory.getRestrictedUsers(Some(context.userId).filter(_.id >= 0)).imap(_.map(Id[User](_)))
      val futureLibraries = searchFactory.getLibraryIdsFuture(userId, None).imap(_._2.map(Id[Library](_)))
      val futureOrganizations = searchFactory.getOrganizations(userId, None).imap(_.map(Id[Organization](_)))

      for {
        friends <- futureFriends
        restrictedUsers <- futureRestrictedUsers
        libraries <- futureLibraries
        organizations <- futureOrganizations
        allAugmentationInfos <- getAugmentationInfos(shards, userId, friends, restrictedUsers, libraries, organizations, items ++ context.corpus.keySet, hideOtherPublishedKeeps.exists(identity))
      } yield {
        val contextualAugmentationInfos = context.corpus.collect { case (item, weight) if allAugmentationInfos.contains(item) => (allAugmentationInfos(item) -> weight) }
        val contextualScores = computeAugmentationScores(contextualAugmentationInfos)
        val relevantAugmentationInfos = items.collect { case item if allAugmentationInfos.contains(item) => item -> allAugmentationInfos(item) }.toMap
        ItemAugmentationResponse(relevantAugmentationInfos, contextualScores)
      }
    }
  }

  private def getAugmentationInfos(shards: Set[Shard[NormalizedURI]], userId: Id[User], friends: Set[Id[User]], restrictedUsers: Set[Id[User]], libraries: Set[Id[Library]], organizations: Set[Id[Organization]], items: Set[AugmentableItem], hideOtherPublishedKeeps: Boolean): Future[Map[AugmentableItem, FullAugmentationInfo]] = {
    val friendsArray = LongArraySet.fromSet(friends.map(_.id))
    val restrictedUsersArray = LongArraySet.fromSet(restrictedUsers.map(_.id))
    val librariesArray = LongArraySet.fromSet(libraries.map(_.id))
    val organizationsArray = LongArraySet.fromSet(organizations.map(_.id))
    val getKeepVisibilityEvaluator: WrappedSubReader => KeepVisibilityEvaluator = KeepVisibilityEvaluator(
      userId = userId.id,
      myFriendIds = friendsArray,
      restrictedUserIds = restrictedUsersArray,
      myLibraryIds = librariesArray,
      myOrgIds = organizationsArray,
      SearchFilter.default // todo(Léo / Ryan): could use this, warning: UserScope currently filters keeps by owner, not participant (easy change)
    )

    val futureAugmentationInfosByShard: Seq[Future[Map[AugmentableItem, FullAugmentationInfo]]] = items.groupBy(item => shards.find(_.contains(item.uri))).collect {
      case (Some(shard), itemsInShard) =>
        SafeFuture {
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher
          val librarySearcher = libraryIndexer.getSearcher
          itemsInShard.map { item => item -> getAugmentationInfo(keepSearcher, librarySearcher, getKeepVisibilityEvaluator, hideOtherPublishedKeeps)(item) }.toMap
        }
    }.toSeq
    Future.sequence(futureAugmentationInfosByShard).map(_.foldLeft(Map.empty[AugmentableItem, FullAugmentationInfo])(_ ++ _))
  }

  // todo(Léo): this is currently very much unrestricted in order to push for library discovery
  private def showThisPublishedLibrary(librarySearcher: Searcher, libraryId: Id[Library]): Boolean = {
    LibraryIndexable.getName(librarySearcher, libraryId.id).exists { name =>
      !libraryQualityEvaluator.isPoorlyNamed(name)
    }
  }

  // todo(Léo): consider exists vs forall as keeps get in multiple libraries, a good published keep could be added to a bad library
  private def isIgnoredPublishedKeep(librarySearcher: Searcher, keepVisibility: Int, keepLibraries: Set[Id[Library]], keepRecord: KeepRecord, hideOtherPublishedKeeps: Boolean): Boolean = {
    (keepVisibility == Visibility.OTHERS) && (
      hideOtherPublishedKeeps ||
      keepRecord.tags.exists(_.normalized.contains("imported")) ||
      keepLibraries.exists(libraryId => !showThisPublishedLibrary(librarySearcher, libraryId))
    )
  }

  private def getAugmentationInfo(keepSearcher: Searcher, librarySearcher: Searcher, getKeepVisibilityEvaluator: WrappedSubReader => KeepVisibilityEvaluator, hideOtherPublishedKeeps: Boolean)(item: AugmentableItem): FullAugmentationInfo = {
    val uriTerm = new Term(KeepFields.uriField, item.uri.id.toString)
    val keeps = new ListBuffer[KeepDocument]()
    var otherPublishedKeeps = 0
    // todo(Léo, Yasu): This won't scale with very popular pages, will have to implement something like HyperLogLog counting
    val uniqueKeepers = MutableSet[Long]()
    val uniqueLibraries = MutableSet[Id[Library]]()

    (keepSearcher.indexReader.getContext.leaves()).foreach { atomicReaderContext =>
      val reader = atomicReaderContext.reader().asInstanceOf[WrappedSubReader]
      val keepVisibilityEvaluator = getKeepVisibilityEvaluator(reader)

      val keepIdMapper = reader.getIdMapper

      val ownerIdDocValues: NumericDocValues = reader.getNumericDocValues(KeepFields.ownerIdField)
      val userIdsDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.userIdsField)
      val libraryIdsDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.libraryIdsField)
      val orgIdsDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.orgIdsField)
      val keptAtDocValues: NumericDocValues = reader.getNumericDocValues(KeepFields.keptAtField)
      val recordDocValues = reader.getBinaryDocValues(KeepFields.recordField)

      val docs = reader.termDocsEnum(uriTerm)

      def getKeepRecord(docId: Int): KeepRecord = {
        val ref = recordDocValues.get(docId)
        KeepRecord.fromByteArray(ref.bytes, ref.offset, ref.length)
      }

      @inline def getIds[T](docValues: BinaryDocValues, docId: Int): Set[Id[T]] = {
        val ref = docValues.get(docId)
        DocUtil.toLongSet(ref).map(Id[T](_))
      }

      @inline def getAndCountLibraryIds(docValues: BinaryDocValues, docId: Int): Set[Id[Library]] = {
        val libraryIds = getIds[Library](docValues, docId)
        uniqueLibraries ++= libraryIds
        libraryIds
      }

      @inline def getAndCountOwnerId(ownerId: Long): Option[Id[User]] = if (ownerId < 0) None else {
        uniqueKeepers += ownerId
        Some(Id(ownerId))
      }

      if (docs != null) {
        var docId = docs.nextDoc()
        while (docId < NO_MORE_DOCS) {
          val keepId = keepIdMapper.getId(docId)
          val visibility = keepVisibilityEvaluator(docId)
          val isCanonicalKeep = item.keepId.isDefined && item.keepId.get.id == keepId

          if (visibility != Visibility.RESTRICTED || isCanonicalKeep) {
            val libraries = getAndCountLibraryIds(libraryIdsDocValues, docId)
            val record = getKeepRecord(docId)
            if (isIgnoredPublishedKeep(librarySearcher, visibility, libraries, record, hideOtherPublishedKeeps) && !isCanonicalKeep) { otherPublishedKeeps += 1 }
            else {
              val owner = getAndCountOwnerId(ownerIdDocValues.get(docId))
              val users = getIds[User](userIdsDocValues, docId)
              val organizations = getIds[Organization](orgIdsDocValues, docId)
              val keptAt = keptAtDocValues.get(docId).toDateTime
              keeps += KeepDocument(Id[Keep](keepId), keptAt, owner, users, libraries, organizations, record.note, record.tags)
            }
          }
          docId = docs.nextDoc()
        }
      }
    }
    FullAugmentationInfo(keeps.toList, otherPublishedKeeps, 0, uniqueLibraries.size, uniqueKeepers.size)
  }

  private def computeAugmentationScores(weightedAugmentationInfos: Iterable[(FullAugmentationInfo, Float)]): AugmentationScores = {
    val libraryScores = MutableMap[Id[Library], Float]() withDefaultValue 0f
    val userScores = MutableMap[Id[User], Float]() withDefaultValue 0f
    val tagScores = MutableMap[Hashtag, Float]() withDefaultValue 0f

    weightedAugmentationInfos.foreach {
      case (info, weight) =>
        (info.keeps).foreach { keep =>
          keep.libraries.foreach { libraryId => libraryScores(libraryId) = libraryScores(libraryId) + weight }
          keep.owner.foreach { ownerId => userScores(ownerId) = userScores(ownerId) + weight }
          keep.tags.foreach { tag =>
            tagScores(tag) = tagScores(tag) + weight
          }
        }
    }
    AugmentationScores(libraryScores.toMap, userScores.toMap, tagScores.toMap)
  }
}
