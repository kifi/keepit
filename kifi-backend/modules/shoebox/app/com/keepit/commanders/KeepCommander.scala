package com.keepit.commanders

import java.util.concurrent.{ Callable, TimeUnit }

import com.google.common.cache.{ Cache, CacheBuilder }
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.CollectionHelpers
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, StackTrace }
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import com.keepit.common.time._
import com.keepit.curator.CuratorServiceClient
import com.keepit.heimdal._
import com.keepit.integrity.UriIntegrityHelpers
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ AugmentableItem, ItemAugmentationRequest }
import com.keepit.typeahead.{ HashtagHit, HashtagTypeahead, TypeaheadHit }
import org.joda.time.DateTime
import play.api.http.Status.{ FORBIDDEN, NOT_FOUND }
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

case class RawBookmarksWithCollection(
  collection: Option[Either[ExternalId[Collection], String]], keeps: Seq[RawBookmarkRepresentation])

object RawBookmarksWithCollection {
  implicit val reads = (
    (__ \ 'collectionId).read(ExternalId.format[Collection])
    .map[Option[Either[ExternalId[Collection], String]]](c => Some(Left(c)))
    orElse (__ \ 'collectionName).readNullable[String]
    .map(_.map[Either[ExternalId[Collection], String]](Right(_))) and
    (__ \ 'keeps).read[Seq[RawBookmarkRepresentation]]
  )(RawBookmarksWithCollection.apply _)
}

case class BulkKeepSelection(
  keeps: Option[Seq[ExternalId[Keep]]],
  tag: Option[ExternalId[Collection]],
  exclude: Option[Seq[ExternalId[Keep]]])
object BulkKeepSelection {
  implicit val format = (
    (__ \ 'keeps).formatNullable[Seq[ExternalId[Keep]]] and
    (__ \ 'tag).formatNullable[ExternalId[Collection]] and
    (__ \ 'exclude).formatNullable[Seq[ExternalId[Keep]]]
  )(BulkKeepSelection.apply, unlift(BulkKeepSelection.unapply))
}

@ImplementedBy(classOf[KeepCommanderImpl])
trait KeepCommander {
  def idsToKeeps(ids: Seq[Id[Keep]])(implicit session: RSession): Seq[Keep]
  def getBasicKeeps(ids: Set[Id[Keep]]): Map[Id[Keep], BasicKeep]
  def getKeepsCountFuture(): Future[Int]
  def getKeep(libraryId: Id[Library], keepExtId: ExternalId[Keep], userId: Id[User]): Either[(Int, String), Keep]
  def allKeeps(before: Option[ExternalId[Keep]], after: Option[ExternalId[Keep]], collectionId: Option[ExternalId[Collection]], helprankOpt: Option[String], count: Int, userId: Id[User]): Future[Seq[KeepInfo]]
  def getKeepsInBulkSelection(selection: BulkKeepSelection, userId: Id[User]): Seq[Keep]
  def keepOne(rawBookmark: RawBookmarkRepresentation, userId: Id[User], libraryId: Id[Library], installationId: Option[ExternalId[KifiInstallation]], source: KeepSource, socialShare: SocialShare)(implicit context: HeimdalContext): (Keep, Boolean)
  def keepMultiple(rawBookmarks: Seq[RawBookmarkRepresentation], libraryId: Id[Library], userId: Id[User], source: KeepSource, collection: Option[Either[ExternalId[Collection], String]], separateExisting: Boolean = false)(implicit context: HeimdalContext): (Seq[KeepInfo], Option[Int], Seq[String], Option[Seq[KeepInfo]])
  def unkeepOneFromLibrary(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, KeepInfo]
  def unkeepManyFromLibrary(keepIds: Seq[ExternalId[Keep]], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, (Seq[KeepInfo], Seq[ExternalId[Keep]])]
  def updateKeepInLibrary(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User], title: Option[String])(implicit context: HeimdalContext): Either[(Int, String), Keep]
  def updateKeepNote(userId: Id[User], oldKeep: Keep, newNote: String)(implicit context: HeimdalContext)
  def editKeepTagBulk(collectionId: ExternalId[Collection], selection: BulkKeepSelection, userId: Id[User], isAdd: Boolean)(implicit context: HeimdalContext): Int
  def addToCollection(collectionId: Id[Collection], allKeeps: Seq[Keep], updateIndex: Boolean = true)(implicit context: HeimdalContext): Set[KeepToCollection]
  def removeFromCollection(collection: Collection, keeps: Seq[Keep])(implicit context: HeimdalContext): Set[KeepToCollection]
  def tagUrl(tag: Collection, rawBookmark: Seq[RawBookmarkRepresentation], userId: Id[User], libraryId: Id[Library], source: KeepSource, kifiInstallationId: Option[ExternalId[KifiInstallation]])(implicit context: HeimdalContext): Unit
  def tagKeeps(tag: Collection, userId: Id[User], keepIds: Seq[ExternalId[Keep]])(implicit context: HeimdalContext): (Seq[Keep], Seq[Keep])
  def getOrCreateTag(userId: Id[User], name: String)(implicit context: HeimdalContext): Collection
  def removeTag(id: ExternalId[Collection], url: String, userId: Id[User])(implicit context: HeimdalContext): Unit
  def clearTags(url: String, userId: Id[User]): Unit
  def tagsByUrl(url: String, userId: Id[User]): Seq[Collection]
  def keepWithSelectedTags(userId: Id[User], rawBookmark: RawBookmarkRepresentation, libraryId: Id[Library], source: KeepSource, selectedTagNames: Seq[String], socialShare: SocialShare)(implicit context: HeimdalContext): Either[String, (Keep, Seq[Collection])]
  def persistHashtagsForKeepAndSaveKeep(userId: Id[User], keep: Keep, selectedTagNames: Seq[String])(implicit session: RWSession, context: HeimdalContext)
  def searchTags(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[HashtagHit]]
  def suggestTags(userId: Id[User], keepIdOpt: Option[ExternalId[Keep]], query: Option[String], limit: Int): Future[Seq[(Hashtag, Seq[(Int, Int)])]]
  def assembleKeepExport(keepExports: Seq[KeepExport]): String
  def numKeeps(userId: Id[User]): Int
  def persistKeep(k: Keep, users: Set[Id[User]], libraries: Set[Id[Library]])(implicit session: RWSession): Keep
  def updateNote(keep: Keep, newNote: Option[String])(implicit session: RWSession): Keep
  def syncWithLibrary(keep: Keep, library: Library)(implicit session: RWSession): Keep
  def changeOwner(keep: Keep, newOwnerId: Id[User])(implicit session: RWSession): Keep
  def changeUri(keep: Keep, newUri: NormalizedURI)(implicit session: RWSession): Unit
  def deactivateKeep(keep: Keep)(implicit session: RWSession): Unit
  def moveKeep(k: Keep, toLibrary: Library, userId: Id[User])(implicit session: RWSession): Either[LibraryError, Keep]
  def copyKeep(k: Keep, toLibrary: Library, userId: Id[User], withSource: Option[KeepSource] = None)(implicit session: RWSession): Either[LibraryError, Keep]
  def getKeepStream(userId: Id[User], limit: Int, beforeExtId: Option[ExternalId[Keep]], afterExtId: Option[ExternalId[Keep]]): Future[Seq[KeepInfo]]
}

@Singleton
class KeepCommanderImpl @Inject() (
    db: Database,
    keepInterner: KeepInterner,
    searchClient: SearchServiceClient,
    globalKeepCountCache: GlobalKeepCountCache,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionCommander: CollectionCommander,
    keepRepo: KeepRepo,
    ktlRepo: KeepToLibraryRepo,
    ktlCommander: KeepToLibraryCommander,
    ktuRepo: KeepToUserRepo,
    ktuCommander: KeepToUserCommander,
    socialUserInfoRepo: SocialUserInfoRepo,
    collectionRepo: CollectionRepo,
    libraryAnalytics: LibraryAnalytics,
    heimdalClient: HeimdalServiceClient,
    airbrake: AirbrakeNotifier,
    normalizedURIInterner: NormalizedURIInterner,
    curator: CuratorServiceClient,
    clock: Clock,
    libraryCommander: LibraryCommander,
    libraryInfoCommander: LibraryInfoCommander,
    libraryRepo: LibraryRepo,
    userRepo: UserRepo,
    userExperimentRepo: UserExperimentRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    hashtagTypeahead: HashtagTypeahead,
    keepDecorator: KeepDecorator,
    twitterPublishingCommander: TwitterPublishingCommander,
    facebookPublishingCommander: FacebookPublishingCommander,
    librarySubscriptionCommander: LibrarySubscriptionCommander,
    uriHelpers: UriIntegrityHelpers,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends KeepCommander with Logging {

  def idsToKeeps(ids: Seq[Id[Keep]])(implicit session: RSession): Seq[Keep] = {
    val idToKeepMap = keepRepo.getByIds(ids.toSet)
    ids.map(idToKeepMap)
  }

  def getBasicKeeps(ids: Set[Id[Keep]]): Map[Id[Keep], BasicKeep] = {
    db.readOnlyReplica { implicit session =>
      val keeps = keepRepo.getByIds(ids)
      val users = userRepo.getAllUsers(keeps.collect {
        case (id, keep) => keep.userId
      }.toSeq)
      keeps.map {
        case (id, keep) => id -> BasicKeep(
          keep.externalId,
          keep.visibility,
          Library.publicId(keep.libraryId.get),
          users(keep.userId).externalId
        )
      }
    }
  }

  def getKeepsCountFuture(): Future[Int] = {
    globalKeepCountCache.getOrElseFuture(GlobalKeepCountKey()) {
      Future.sequence(searchClient.indexInfoList()).map { results =>
        var countMap = Map.empty[String, Int]
        results.flatMap(_._2).foreach { info =>
          /**
           * todo(eishay): we need to parse the index family.
           * Name will look like "KeepIndexer_2_4" where the family is "4" and shard id is "2".
           * If there is more then one family at the same time (i.e. "8" based shareds), we'll have double counting.
           * We need to get a count of only one family (say count both and pick the largest one).
           */
          if (info.name.startsWith("KeepIndex")) {
            countMap.get(info.name) match {
              case Some(count) if count >= info.numDocs =>
              case _ => countMap += (info.name -> info.numDocs)
            }
          }
        }
        countMap.values.sum
      }
    }
  }

  def getKeep(libraryId: Id[Library], keepExtId: ExternalId[Keep], userId: Id[User]): Either[(Int, String), Keep] = {
    db.readOnlyMaster { implicit session =>
      if (libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId).isDefined) {
        val oldWay = keepRepo.getByExtIdandLibraryId(keepExtId, libraryId) match {
          case Some(k) => Right(k)
          case None => Left(NOT_FOUND, "keep_not_found")
        }
        val newWay = keepRepo.getOpt(keepExtId) match {
          case Some(k) if ktlCommander.isKeepInLibrary(k.id.get, libraryId) => Right(k)
          case _ => Left(NOT_FOUND, "keep_not_found")
        }
        if (newWay != oldWay) log.info(s"[KTL-MATCH] getKeep: $newWay != $oldWay")

        oldWay
      } else {
        Left(FORBIDDEN, "library_access_denied")
      }
    }
  }

  private def getHelpRankRelatedKeeps(userId: Id[User], selector: HelpRankSelector, beforeOpt: Option[ExternalId[Keep]], afterOpt: Option[ExternalId[Keep]], count: Int): Future[Seq[(Keep, Option[Int], Option[Int])]] = {
    @inline def filter(counts: Seq[(Id[NormalizedURI], Int)])(implicit r: RSession): Seq[Id[NormalizedURI]] = {
      val uriIds = counts.map(_._1)
      val before = beforeOpt match {
        case None => uriIds
        case Some(beforeExtId) =>
          keepRepo.getByExtIdAndUser(beforeExtId, userId) match {
            case None => uriIds
            case Some(beforeKeep) => uriIds.dropWhile(_ != beforeKeep.uriId).drop(1)
          }
      }
      val after = afterOpt match {
        case None => before
        case Some(afterExtId) => keepRepo.getByExtIdAndUser(afterExtId, userId) match {
          case None => before
          case Some(afterKeep) => uriIds.takeWhile(_ != afterKeep.uriId)
        }
      }
      after
    }

    val keepIdsF = selector match {
      case HelpRankRekeeps => heimdalClient.getUriReKeepsWithCountsByKeeper(userId) map { counts => counts.map(c => c.uriId -> c.count) }
      case HelpRankDiscoveries => heimdalClient.getUriDiscoveriesWithCountsByKeeper(userId) map { counts => counts.map(c => c.uriId -> c.count) }
    }
    keepIdsF.flatMap { counts =>
      val keeps = db.readOnlyReplica { implicit session =>
        val keepUriIds = filter(counts)
        val km = keepRepo.bulkGetByUserAndUriIds(userId, keepUriIds.toSet)
        val sorted = km.valuesIterator.toList.sortBy(_.id).reverse
        if (count > 0) sorted.take(count) else sorted
      }

      // Fetch counts
      val keepIds = keeps.map(_.id.get).toSet
      val discCountsF = heimdalClient.getDiscoveryCountsByKeepIds(userId, keepIds)
      val rekeepCountsF = heimdalClient.getReKeepCountsByKeepIds(userId, keepIds)
      for {
        discCounts <- discCountsF
        rekeepCounts <- rekeepCountsF
      } yield {
        val discMap = discCounts.map { c => c.keepId -> c.count }.toMap
        val rkMap = rekeepCounts.map { c => c.keepId -> c.count }.toMap
        keeps.map { keep =>
          (keep, discMap.get(keep.id.get), rkMap.get(keep.id.get))
        }
      }
    }
  }

  private def getKeepsFromCollection(userId: Id[User], collectionId: ExternalId[Collection], beforeOpt: Option[ExternalId[Keep]], afterOpt: Option[ExternalId[Keep]], count: Int): Seq[Keep] = {
    db.readOnlyReplica { implicit session =>
      val collectionOpt = collectionRepo.getByUserAndExternalId(userId, collectionId)
      val keeps = collectionOpt.map { collection =>
        keepRepo.getByUserAndCollection(userId, collection.id.get, beforeOpt, afterOpt, count)
      } getOrElse Seq.empty

      keeps
    }
  }

  private def getKeeps(userId: Id[User], beforeOpt: Option[ExternalId[Keep]], afterOpt: Option[ExternalId[Keep]], count: Int): Seq[Keep] = {
    db.readOnlyReplica { implicit session =>
      keepRepo.getByUser(userId, beforeOpt, afterOpt, count)
    }
  }

  // Please do not add to this. It mixes concerns and data sources.
  def allKeeps(
    before: Option[ExternalId[Keep]],
    after: Option[ExternalId[Keep]],
    collectionId: Option[ExternalId[Collection]],
    helprankOpt: Option[String],
    count: Int,
    userId: Id[User]): Future[Seq[KeepInfo]] = {

    // The Option[Int]s are help rank counts. Only included when looking at help rank info currently.
    val keepsF: Future[Seq[(Keep, Option[Int], Option[Int])]] = (collectionId, helprankOpt) match {
      case (Some(c), _) => // collectionId is set
        val keeps = getKeepsFromCollection(userId, c, before, after, count)
        Future.successful(keeps.map((_, None, None)))
      case (_, Some(hr)) => // helprankOpt is set
        getHelpRankRelatedKeeps(userId, HelpRankSelector(hr), before, after, count)
      case _ => // neither is set, deliver normal paginated keeps list
        val keeps = getKeeps(userId, before, after, count)
        Future.successful(keeps.map((_, None, None)))
    }

    keepsF.flatMap {
      case keepsWithHelpRankCounts =>
        val (keeps, clickCounts, rkCounts) = keepsWithHelpRankCounts.unzip3
        keepDecorator.decorateKeepsIntoKeepInfos(Some(userId), false, keeps, ProcessedImageSize.Large.idealSize, withKeepTime = true)
    }
  }

  def getKeepsInBulkSelection(selection: BulkKeepSelection, userId: Id[User]): Seq[Keep] = {
    val MAX_KEEPS_IN_COLLECTION = 1000
    val (collectionKeeps, individualKeeps) = db.readOnlyMaster { implicit s =>
      val collectionKeeps = selection.tag flatMap { tagExtId =>
        val tagIdOpt = collectionRepo.getByUserAndExternalId(userId, tagExtId).flatMap(_.id)
        tagIdOpt map { tagId =>
          val keepCount = collectionRepo.getBookmarkCount(tagId)
          if (keepCount <= MAX_KEEPS_IN_COLLECTION) {
            airbrake.notify(s"Maximum number of keeps in collection reached for user $userId and collection $tagId")
            Seq()
          } else {
            keepRepo.getByUserAndCollection(userId, tagId, None, None, MAX_KEEPS_IN_COLLECTION)
          }
        }
      } getOrElse Seq()
      val individualKeeps = selection.keeps map { keepExtIds =>
        keepExtIds flatMap { keepExtId =>
          keepRepo.getByExtIdAndUser(keepExtId, userId)
        }
      } getOrElse Seq()
      (collectionKeeps, individualKeeps)
    }
    // Get distinct keeps
    val filter: (Keep => Boolean) = selection.exclude match {
      case Some(excluded) => { keep => keep.id.nonEmpty && !excluded.contains(keep) }
      case None => _.id.nonEmpty
    }
    (individualKeeps ++ collectionKeeps).filter(filter).groupBy(_.id.get).values.flatten.toSeq
  }

  // TODO: if keep is already in library, return it and indicate whether userId is the user who originally kept it
  def keepOne(rawBookmark: RawBookmarkRepresentation, userId: Id[User], libraryId: Id[Library], installationId: Option[ExternalId[KifiInstallation]], source: KeepSource, socialShare: SocialShare)(implicit context: HeimdalContext): (Keep, Boolean) = {
    log.info(s"[keep] $rawBookmark")
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    val (keep, isNewKeep) = keepInterner.internRawBookmark(rawBookmark, userId, library, source).get
    if (isNewKeep) { librarySubscriptionCommander.sendNewKeepMessage(keep, library) }
    postSingleKeepReporting(keep, isNewKeep, library, socialShare)
    (keep, isNewKeep)
  }

  def keepMultiple(rawBookmarks: Seq[RawBookmarkRepresentation], libraryId: Id[Library], userId: Id[User], source: KeepSource, collection: Option[Either[ExternalId[Collection], String]], separateExisting: Boolean = false)(implicit context: HeimdalContext): (Seq[KeepInfo], Option[Int], Seq[String], Option[Seq[KeepInfo]]) = {
    val library = db.readOnlyReplica { implicit session => // change to readOnlyReplica when we can be 100% sure every user has libraries
      libraryRepo.get(libraryId)
    }
    val (newKeeps, existingKeeps, failures) = keepInterner.internRawBookmarksWithStatus(rawBookmarks, userId, library, source)

    newKeeps.foreach { keep => librarySubscriptionCommander.sendNewKeepMessage(keep, library) }

    val keeps = newKeeps ++ existingKeeps
    log.info(s"[keepMulti] keeps(len=${keeps.length}):${keeps.mkString(",")}")
    val addedToCollection = collection flatMap {
      case Left(collectionId) => db.readOnlyReplica { implicit s => collectionRepo.getOpt(collectionId) }
      case Right(name) => Some(getOrCreateTag(userId, name))
    } map { coll =>
      addToCollection(coll.id.get, keeps).size
    }
    SafeFuture {
      searchClient.updateKeepIndex()
      keeps.foreach { keep => curator.updateUriRecommendationFeedback(userId, keep.uriId, UriRecommendationFeedback(kept = Some(true))) }
    }
    val (returnedKeeps, existingKeepsOpt) = if (separateExisting) {
      (newKeeps, Some(existingKeeps))
    } else {
      (newKeeps ++ existingKeeps, None)
    }
    (returnedKeeps.map(KeepInfo.fromKeep), addedToCollection, failures map (_.url), existingKeepsOpt map (_.map(KeepInfo.fromKeep)))
  }

  def unkeepOneFromLibrary(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, KeepInfo] = {
    unkeepManyFromLibrary(Seq(keepId), libId, userId) match {
      case Left(why) => Left(why)
      case Right((Seq(), _)) => Left("keep_not_found")
      case Right((Seq(info), _)) => Right(info)
    }
  }

  def unkeepManyFromLibrary(keepIds: Seq[ExternalId[Keep]], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, (Seq[KeepInfo], Seq[ExternalId[Keep]])] = {
    db.readOnlyMaster { implicit session =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId)
    } match {
      case Some(mem) if mem.canWrite =>
        val (keeps, invalidKeepIds) = db.readWrite { implicit s =>
          val (keepsE, invalidKeepIdsE) = keepIds.map { kId =>
            keepRepo.getByExtIdandLibraryId(kId, libId, excludeSet = Set.empty) match {
              case Some(k) =>
                Left(k)
              case None =>
                Right(kId)
            }
          }.partition(_.isLeft)

          val keeps = keepsE.map(_.left.get)
          val invalidKeepIds = invalidKeepIdsE.map(_.right.get)

          val inactivatedKeeps = keeps.map { k =>
            // TODO(ryan): stop deactivating keeps and instead just detach them from libraries
            // just uncomment the line below this and rework some of this
            // ktlCommander.removeKeepFromLibrary(k.id.get, libId)
            deactivateKeep(k)
            k.withState(KeepStates.INACTIVE).withNote(None)
          }
          finalizeUnkeeping(keeps, userId)

          // Inactivate tags, update tag
          val phantomActiveKeeps = keeps.map(_.copy(state = KeepStates.ACTIVE))
          (keepToCollectionRepo.getCollectionsForKeeps(phantomActiveKeeps) zip keeps).flatMap {
            case (colls, keep) =>
              log.info(s"[unkeepManyFromLibrary] Removing tags from ${keep.id.get}: ${colls.mkString(",")}")
              colls.foreach { collId => keepToCollectionRepo.remove(keep.id.get, collId) }
              colls
          }.foreach { coll =>
            collectionRepo.collectionChanged(coll, inactivateIfEmpty = true)
          }

          (inactivatedKeeps, invalidKeepIds)
        }

        Right((keeps map KeepInfo.fromKeep, invalidKeepIds))
      case _ =>
        Left("permission_denied")
    }
  }

  private def finalizeUnkeeping(keeps: Seq[Keep], userId: Id[User])(implicit session: RWSession, context: HeimdalContext): Unit = {
    // TODO: broadcast over any open user channels
    keeps.groupBy(_.libraryId).toList.collect {
      case (Some(libId), _) =>
        val library = libraryRepo.get(libId)
        libraryRepo.save(library.copy(keepCount = keepRepo.getCountByLibrary(libId)))
        libraryAnalytics.unkeptPages(userId, keeps, library, context)
    }
    searchClient.updateKeepIndex()
  }

  // TODO(ryan): Murder whoever decided "updateKeep" was descriptive
  def updateKeepInLibrary(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User], title: Option[String])(implicit context: HeimdalContext): Either[(Int, String), Keep] = {
    db.readOnlyMaster { implicit session =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId)
    } match {
      case Some(mem) if mem.canWrite =>
        val normTitle = title.map(_.trim).filter(_.nonEmpty)
        db.readWrite { implicit s =>
          keepRepo.getByExtIdandLibraryId(keepId, libId) match {
            case Some(keep) if normTitle.isDefined && normTitle != keep.title =>
              val keep2 = keepRepo.save(keep.withTitle(normTitle))
              searchClient.updateKeepIndex()
              libraryAnalytics.updatedKeep(keep, keep2, context)
              Right(keep2)
            case Some(keep) =>
              Right(keep)
            case None =>
              Left((NOT_FOUND, "keep_not_found"))
          }
        }
      case _ =>
        Left((FORBIDDEN, "permission_denied"))
    }
  }

  def updateKeepNote(userId: Id[User], oldKeep: Keep, newNote: String)(implicit context: HeimdalContext) = {
    val noteToPersist = Some(newNote.trim).filter(_.nonEmpty)
    if (noteToPersist != oldKeep.note) {
      val updatedKeep = oldKeep.copy(userId = userId, note = noteToPersist)
      val hashtagNamesToPersist = Hashtags.findAllHashtagNames(noteToPersist.getOrElse(""))
      db.readWrite { implicit s =>
        persistHashtagsForKeepAndSaveKeep(userId, updatedKeep, hashtagNamesToPersist.toSeq)(s, context)
      }
    }
  }

  def editKeepTagBulk(collectionId: ExternalId[Collection], selection: BulkKeepSelection, userId: Id[User], isAdd: Boolean)(implicit context: HeimdalContext): Int = {
    db.readOnlyReplica { implicit s =>
      collectionRepo.getByUserAndExternalId(userId, collectionId)
    } map { collection =>
      val keeps = getKeepsInBulkSelection(selection, userId)
      assert(collection.id.nonEmpty, s"Collection id is undefined: $collection")
      if (isAdd) addToCollection(collection.id.get, keeps) else removeFromCollection(collection, keeps)
      keeps.length
    } getOrElse 0
  }

  def addToCollection(collectionId: Id[Collection], allKeeps: Seq[Keep], updateIndex: Boolean = true)(implicit context: HeimdalContext): Set[KeepToCollection] = timing(s"addToCollection($collectionId,${allKeeps.length})") {
    val trace = new StackTrace()
    val result: Iterator[KeepToCollection] = allKeeps.grouped(50) flatMap { keeps =>
      try {
        db.readWrite(attempts = 3) { implicit s =>
          val keepsById = keeps.map(keep => keep.id.get -> keep).toMap
          val collection = collectionRepo.get(collectionId)
          val existing = keepToCollectionRepo.getByCollection(collectionId, excludeState = None).toSet
          val newKeepIds = keepsById.keySet -- existing.map(_.keepId)
          val newK2C = newKeepIds map { kId => KeepToCollection(keepId = kId, collectionId = collectionId) }
          timing(s"addToCollection($collectionId,${keeps.length}) -- keepToCollection.insertAll", 50) {
            keepToCollectionRepo.insertAll(newK2C.toSeq)
          }
          val activated = existing collect {
            case ktc if ktc.isInactive && keepsById.contains(ktc.keepId) =>
              keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE, createdAt = clock.now()))
          }

          val updatedCollection = timing(s"addToCollection($collectionId,${keeps.length}) -- collection.modelChanged", 50) {
            collectionRepo.collectionChanged(collectionId, (newK2C.size + activated.size) > 0, inactivateIfEmpty = false)
          }
          val tagged: Set[KeepToCollection] = (activated ++ newK2C).toSet
          val taggingAt = currentDateTime
          tagged.foreach { ktc =>
            val targetKeep = keepsById(ktc.keepId)
            val noteStr = targetKeep.note.getOrElse("")
            val persistedNote = Some(Hashtags.addNewHashtagsToString(noteStr, Seq(collection.name))).filter(_.nonEmpty)
            if (persistedNote != targetKeep.note) {
              val updatedKeep = updateNote(targetKeep, persistedNote) // notify keep index
              libraryAnalytics.taggedPage(updatedCollection, updatedKeep, context, taggingAt)
            }
          }
          tagged
        }
      } catch {
        case t: Throwable =>
          airbrake.notify(s"error attaching collection id $collectionId to a batch of ${keeps.length} of ${allKeeps.length} keeps. 3 sample keeps: ${allKeeps.take(3)}", trace.withCause(t))
          Set.empty[KeepToCollection]
      }
    }
    if (updateIndex) {
      searchClient.updateKeepIndex()
    }
    result.toSet
  }

  def removeFromCollection(collection: Collection, keeps: Seq[Keep])(implicit context: HeimdalContext): Set[KeepToCollection] = {
    db.readWrite(attempts = 2) { implicit s =>
      val keepsById = keeps.map(keep => keep.id.get -> keep).toMap
      val removed = keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None) collect {
        case ktc if ktc.state != KeepToCollectionStates.INACTIVE && keepsById.contains(ktc.keepId) =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
      }
      collectionRepo.collectionChanged(collection.id.get, inactivateIfEmpty = true)

      val removedAt = currentDateTime
      removed.foreach { ktc =>
        val targetKeep = keepsById(ktc.keepId)
        val editedNote = targetKeep.note.map { noteStr =>
          Hashtags.removeHashtagsFromString(noteStr, Set(collection.name))
        }.filterNot(_.isEmpty)
        val updatedKeep = updateNote(targetKeep, editedNote) // notify keep index
        libraryAnalytics.untaggedPage(collection, updatedKeep, context, removedAt)
      }
      removed.toSet
    } tap { _ =>
      searchClient.updateKeepIndex()
    }
  }

  def tagUrl(tag: Collection, rawBookmark: Seq[RawBookmarkRepresentation], userId: Id[User], libraryId: Id[Library], source: KeepSource, kifiInstallationId: Option[ExternalId[KifiInstallation]])(implicit context: HeimdalContext) = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    val (bookmarks, _) = keepInterner.internRawBookmarks(rawBookmark, userId, library, source)
    addToCollection(tag.id.get, bookmarks) // why doesn't this update search?
  }

  def tagKeeps(tag: Collection, userId: Id[User], keepIds: Seq[ExternalId[Keep]])(implicit context: HeimdalContext): (Seq[Keep], Seq[Keep]) = {
    val (canEditKeep, cantEditKeeps) = db.readOnlyMaster { implicit s =>
      val canAccess = Map[Id[Library], Boolean]().withDefault(id => libraryCommander.canModifyLibrary(id, userId))
      keepIds map keepRepo.get partition { keep =>
        keep.libraryId.exists(canAccess)
      }
    }
    addToCollection(tag.id.get, canEditKeep)
    (canEditKeep, cantEditKeeps)
  }

  def getOrCreateTag(userId: Id[User], name: String)(implicit context: HeimdalContext): Collection = {
    val normalizedName = Hashtag(name.trim.replaceAll("""\s+""", " ").take(Collection.MaxNameLength))
    val collection = db.readOnlyReplica { implicit s =>
      collectionRepo.getByUserAndName(userId, normalizedName, excludeState = None)
    }
    collection match {
      case Some(t) if t.isActive => t
      case Some(t) => db.readWrite { implicit s => collectionRepo.save(t.copy(state = CollectionStates.ACTIVE, name = normalizedName, createdAt = clock.now())) } tap (libraryAnalytics.createdTag(_, context))
      case None => db.readWrite { implicit s => collectionRepo.save(Collection(userId = userId, name = normalizedName)) } tap (libraryAnalytics.createdTag(_, context))
    }
  }

  def removeTag(id: ExternalId[Collection], url: String, userId: Id[User])(implicit context: HeimdalContext): Unit = {
    val keep = db.readWrite { implicit s =>
      for {
        uri <- normalizedURIInterner.getByUri(url)
        keep <- keepRepo.getByUriAndUser(uri.id.get, userId)
        collection <- collectionRepo.getOpt(id)
      } yield {
        keepToCollectionRepo.remove(keepId = keep.id.get, collectionId = collection.id.get)
        collectionRepo.collectionChanged(collection.id.get, inactivateIfEmpty = true)
        keepRepo.save(keep) // notify keep index
        libraryAnalytics.untaggedPage(collection, keep, context)
        keep
      }
    }
    searchClient.updateKeepIndex()
  }

  //todo(hopefully not Léo): this method does not report to analytics, let's fix this after we get rid of Collection

  def clearTags(url: String, userId: Id[User]): Unit = {
    val keeps = db.readWrite { implicit s =>
      for {
        uri <- normalizedURIInterner.getByUri(url).toSeq
        keep <- keepRepo.getByUriAndUser(uri.id.get, userId).toSeq
        ktc <- keepToCollectionRepo.getByKeep(keep.id.get)
      } yield {
        keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
        collectionRepo.collectionChanged(ktc.collectionId, inactivateIfEmpty = true)
        keepRepo.save(keep) // notify keep index
      }
    }
    searchClient.updateKeepIndex()
  }

  def tagsByUrl(url: String, userId: Id[User]): Seq[Collection] = {
    db.readOnlyMaster { implicit s =>
      for {
        uri <- normalizedURIInterner.getByUri(url).toSeq
        keep <- keepRepo.getByUriAndUser(uri.id.get, userId).toSeq
        collectionId <- keepToCollectionRepo.getCollectionsForKeep(keep.id.get)
      } yield {
        collectionRepo.get(collectionId)
      }
    }
  }

  //todo(hopefully not Léo): this method does not report to analytics, let's fix this after we get rid of Collection
  def keepWithSelectedTags(userId: Id[User], rawBookmark: RawBookmarkRepresentation, libraryId: Id[Library], source: KeepSource, selectedTagNames: Seq[String], socialShare: SocialShare)(implicit context: HeimdalContext): Either[String, (Keep, Seq[Collection])] = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    keepInterner.internRawBookmark(rawBookmark, userId, library, source) match {
      case Failure(e) => Left(e.getMessage)
      case Success((keep, isNewKeep)) =>
        val tags = db.readWrite { implicit s =>
          persistHashtagsAndSaveKeep(userId, keep, selectedTagNames)(s, context)
          keepToCollectionRepo.getCollectionsForKeep(keep.id.get).map { id => collectionRepo.get(id) }
        }
        postSingleKeepReporting(keep, isNewKeep, library, socialShare)
        Right((keep, tags))
    }
  }

  def persistHashtagsForKeepAndSaveKeep(userId: Id[User], keep: Keep, selectedTagNames: Seq[String])(implicit session: RWSession, context: HeimdalContext) = {
    persistHashtagsAndSaveKeep(userId, keep, selectedTagNames)(session, context)
    searchClient.updateKeepIndex()
  }

  // Changes a keep's notes based on the hashtags to persist!
  private def persistHashtagsAndSaveKeep(userId: Id[User], keep: Keep, selectedTagNames: Seq[String])(implicit session: RWSession, context: HeimdalContext) = {
    // get all tags from hashtag names list
    val selectedTags = selectedTagNames.map { getOrCreateTag(userId, _) }
    val selectedTagIds = selectedTags.map(_.id.get).toSet
    // get all active tags for keep to figure out which tags to add & which tags to remove
    val activeTagIds = keepToCollectionRepo.getCollectionsForKeep(keep.id.get).toSet
    val tagIdsToAdd = selectedTagIds.filterNot(activeTagIds.contains(_))
    val tagIdsToRemove = activeTagIds.filterNot(selectedTagIds.contains(_))

    // fix k2c for tagsToAdd & tagsToRemove
    tagIdsToAdd.map { tagId =>
      keepToCollectionRepo.getOpt(keep.id.get, tagId) match {
        case None => keepToCollectionRepo.save(KeepToCollection(keepId = keep.id.get, collectionId = tagId))
        case Some(k2c) => keepToCollectionRepo.save(k2c.copy(state = KeepToCollectionStates.ACTIVE))
      }
      collectionRepo.collectionChanged(tagId, true, inactivateIfEmpty = false)
    }
    tagIdsToRemove.map { tagId =>
      keepToCollectionRepo.remove(keep.id.get, tagId)
      collectionRepo.collectionChanged(tagId, false, inactivateIfEmpty = true)
    }

    // go through note field and find all hashtags
    val keepNote = keep.note.getOrElse("")
    val hashtagsInNote = Hashtags.findAllHashtagNames(keepNote)
    val hashtagsToPersistSet = selectedTagNames.toSet

    // find hashtags to remove & to append
    val hashtagsToRemove = hashtagsInNote.filterNot(hashtagsToPersistSet.contains(_))
    val hashtagsToAppend = selectedTagNames.filterNot(hashtagsInNote.contains(_))
    val noteWithHashtagsRemoved = Hashtags.removeHashtagNamesFromString(keepNote, hashtagsToRemove.toSet)
    val noteWithHashtagsAppended = Hashtags.appendHashtagNamesToString(noteWithHashtagsRemoved, hashtagsToAppend)
    val finalNote = Some(noteWithHashtagsAppended.trim).filterNot(_.isEmpty)

    val updatedKeep = updateNote(keep, finalNote)
    libraryAnalytics.updatedKeep(keep, updatedKeep, context)
  }

  private def postSingleKeepReporting(keep: Keep, isNewKeep: Boolean, library: Library, socialShare: SocialShare): Unit = SafeFuture {
    log.info(s"postSingleKeepReporting for user ${keep.userId} with $socialShare keep ${keep.title}")
    if (socialShare.twitter) twitterPublishingCommander.publishKeep(keep.userId, keep, library)
    if (socialShare.facebook) facebookPublishingCommander.publishKeep(keep.userId, keep, library)
    searchClient.updateKeepIndex()
    curator.updateUriRecommendationFeedback(keep.userId, keep.uriId, UriRecommendationFeedback(kept = Some(true)))
  }

  def searchTags(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    implicit val hitOrdering = TypeaheadHit.defaultOrdering[(Hashtag, Int)]
    hashtagTypeahead.topN(userId, query, limit).map(_.map(_.info)).map(HashtagHit.highlight(query, _))
  }

  private def searchTagsForKeep(userId: Id[User], keepIdOpt: Option[ExternalId[Keep]], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    val futureHits = searchTags(userId, query, None)
    val existingTags = keepIdOpt.map { keepId =>
      db.readOnlyMaster { implicit session =>
        keepRepo.getOpt(keepId).map { keep =>
          collectionRepo.getHashtagsByKeepId(keep.id.get)
        }.getOrElse(Set.empty)
      }
    }.getOrElse(Set.empty)
    futureHits.imap { hits =>
      val validHits = hits.filterNot(hit => existingTags.contains(hit.tag))
      limit.map(validHits.take(_)) getOrElse validHits
    }
  }

  private def suggestTagsForKeep(userId: Id[User], keepId: ExternalId[Keep], limit: Option[Int]): Future[Seq[Hashtag]] = {
    val keep = db.readOnlyMaster { implicit session => keepRepo.get(keepId) }
    val item = AugmentableItem(keep.uriId, Some(keep.libraryId.get))
    val futureAugmentationResponse = searchClient.augmentation(ItemAugmentationRequest.uniform(userId, item))
    val existingNormalizedTags = db.readOnlyMaster { implicit session => collectionRepo.getHashtagsByKeepId(keep.id.get).map(_.normalized) }
    futureAugmentationResponse.map { response =>
      val suggestedTags = {
        val restrictedKeeps = response.infos(item).keeps.toSet
        val safeTags = restrictedKeeps.flatMap {
          case myKeep if myKeep.keptBy == Some(userId) => myKeep.tags
          case anotherKeep => anotherKeep.tags.filterNot(_.isSensitive)
        }
        val validTags = safeTags.filterNot(tag => existingNormalizedTags.contains(tag.normalized))
        CollectionHelpers.dedupBy(validTags.toSeq.sortBy(-response.scores.byTag(_)))(_.normalized)
      }
      limit.map(suggestedTags.take(_)) getOrElse suggestedTags
    }
  }

  def suggestTags(userId: Id[User], keepIdOpt: Option[ExternalId[Keep]], query: Option[String], limit: Int): Future[Seq[(Hashtag, Seq[(Int, Int)])]] = {
    query.map(_.trim).filter(_.nonEmpty) match {
      case Some(validQuery) => searchTagsForKeep(userId, keepIdOpt, validQuery, Some(limit)).map(_.map(hit => (hit.tag, hit.matches)))
      case None if keepIdOpt.isDefined => suggestTagsForKeep(userId, keepIdOpt.get, Some(limit)).map(_.map((_, Seq.empty[(Int, Int)])))
      case None => Future.successful(Seq.empty) // We don't support this case yet
    }
  }

  def assembleKeepExport(keepExports: Seq[KeepExport]): String = {
    // HTML format that follows Delicious exports
    val before = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
                   |<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
                   |<!--This is an automatically generated file.
                   |It will be read and overwritten.
                   |Do Not Edit! -->
                   |<Title>Kifi Bookmarks Export</Title>
                   |<H1>Bookmarks</H1>
                   |<DL>
                   |""".stripMargin
    val after = "\n</DL>"

    def createExport(keep: KeepExport): String = {
      // Parse Tags
      val title = keep.title getOrElse ""
      val tagString = keep.tags map { tags =>
        s""" TAGS="${tags.replace("&", "&amp;").replace("\"", "")}""""
      } getOrElse ""
      val date = keep.createdAt.getMillis() / 1000
      val line =
        s"""<DT><A HREF="${keep.url}" ADD_DATE="${date}"${tagString}>${title.replace("&", "&amp;")}</A>"""
      line
    }
    before + keepExports.map(createExport).mkString("\n") + after
  }

  // Until we can refactor all clients to use libraries instead of privacy, we need to look up the library.
  // This should be removed as soon as we can. - Andrew
  private val librariesByUserId: Cache[Id[User], (Library, Library)] = CacheBuilder.newBuilder().concurrencyLevel(4).initialCapacity(128).maximumSize(128).expireAfterWrite(30, TimeUnit.SECONDS).build()
  private def getLibFromPrivacy(isPrivate: Boolean, userId: Id[User])(implicit session: RWSession) = {
    val (main, secret) = librariesByUserId.get(userId, new Callable[(Library, Library)] {
      def call() = libraryInfoCommander.getMainAndSecretLibrariesForUser(userId)
    })
    if (isPrivate) {
      secret
    } else {
      main
    }
  }

  def numKeeps(userId: Id[User]): Int = db.readOnlyReplica { implicit s => keepRepo.getCountByUser(userId) }

  def persistKeep(k: Keep, userIds: Set[Id[User]], libraryIds: Set[Id[Library]])(implicit session: RWSession): Keep = {
    require(userIds.contains(k.userId), "keep owner is not one of the connected users")
    require(libraryIds.contains(k.libraryId.get), "keep's library is not one of the connected libraries")
    val keep = keepRepo.save(k.withLibraries(libraryIds).withParticipants(userIds))

    userIds.foreach { userId => ktuCommander.internKeepInUser(keep, userId, keep.userId) }
    val libraries = libraryRepo.getByIds(libraryIds).values
    libraries.foreach { lib => ktlCommander.internKeepInLibrary(keep, lib, keep.userId) }

    keep
  }
  private def refreshLibrariesHash(keep: Keep)(implicit session: RWSession): Keep = {
    val libraries = ktlRepo.getAllByKeepId(keep.id.get).map(_.libraryId).toSet
    keepRepo.save(keep.withLibraries(libraries))
  }
  private def refreshParticipantsHash(keep: Keep)(implicit session: RWSession): Keep = {
    val users = ktuRepo.getAllByKeepId(keep.id.get).map(_.userId).toSet
    keepRepo.save(keep.withParticipants(users))
  }
  def updateNote(keep: Keep, newNote: Option[String])(implicit session: RWSession): Keep = {
    keepRepo.save(keep.withNote(newNote))
  }
  def syncWithLibrary(keep: Keep, library: Library)(implicit session: RWSession): Keep = {
    require(keep.libraryId == library.id, "keep.libraryId does not match library id!")
    ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get).foreach { ktl => ktlCommander.syncWithLibrary(ktl, library) }
    keepRepo.save(keep.withLibrary(library))
  }
  def changeOwner(keep: Keep, newOwnerId: Id[User])(implicit session: RWSession): Keep = {
    ktuCommander.removeKeepFromUser(keep.id.get, keep.userId)
    val updatedKeep = keepRepo.save(keep.withOwner(newOwnerId))
    ktuCommander.internKeepInUser(keep, newOwnerId, addedBy = newOwnerId)
    refreshParticipantsHash(updatedKeep)
  }
  private def isKeepInLibraries(keepId: Id[Keep], targetLibraries: Set[Id[Library]])(implicit session: RSession): Boolean = {
    ktlRepo.getAllByKeepId(keepId).map(_.libraryId).toSet == targetLibraries
  }
  private def getKeepsByUriAndLibraries(uriId: Id[NormalizedURI], targetLibraries: Set[Id[Library]])(implicit session: RSession): Set[Keep] = {
    // TODO(ryan): uncomment the line below, and get rid of the require
    // keepRepo.getByUriAndLibrariesHash(uriId, LibrariesHash(targetLibraries)).filter(k => isKeepInLibraries(k.id.get, targetLibraries))
    require(targetLibraries.size == 1, s"keep is not in exactly 1 library: $targetLibraries!")
    keepRepo.getPrimaryByUriAndLibrary(uriId, targetLibraries.head).toSet
  }
  def changeUri(keep: Keep, newUri: NormalizedURI)(implicit session: RWSession): Unit = {
    if (keep.isInactive) {
      val newKeep = keepRepo.save(keep.withNormUriId(newUri.id.get))
      ktlCommander.syncKeep(newKeep)
      ktuCommander.syncKeep(newKeep)
    } else {
      val libIds = ktlRepo.getAllByKeepId(keep.id.get).map(_.libraryId).toSet
      val userIds = ktuRepo.getAllByKeepId(keep.id.get).map(_.userId).toSet
      val similarKeeps = getKeepsByUriAndLibraries(newUri.id.get, libIds)

      val mergeableKeeps = similarKeeps.filter(keep.hasStrictlyLessValuableMetadataThan)
      log.info(s"[URI-MIG] Of the similar keeps ${similarKeeps.map(_.id.get)}, these are mergeable: ${mergeableKeeps.map(_.id.get)}")
      if (mergeableKeeps.nonEmpty) {
        val soonToBeDeadKeep = keepRepo.save(keep.withNormUriId(newUri.id.get).withPrimary(false))
        ktlCommander.syncKeep(soonToBeDeadKeep)
        ktuCommander.syncKeep(soonToBeDeadKeep)

        mergeableKeeps.foreach { k =>
          collectionCommander.copyKeepTags(soonToBeDeadKeep, k)
        }
        collectionCommander.deactivateKeepTags(soonToBeDeadKeep)
        deactivateKeep(soonToBeDeadKeep)
      } else {
        val soonToBeDeadKeeps = similarKeeps.filter(_.hasStrictlyLessValuableMetadataThan(keep))
        log.info(s"[URI-MIG] Since no keeps are mergeable, we looked and found these other keeps which should die: ${soonToBeDeadKeeps.map(_.id.get)}")
        soonToBeDeadKeeps.foreach { k =>
          collectionCommander.copyKeepTags(k, keep)
          deactivateKeep(k)
        }

        val newKeep = keepRepo.save(uriHelpers.improveKeepSafely(newUri, keep.withNormUriId(newUri.id.get).withPrimary(true)))
        ktlCommander.syncKeep(newKeep)
        ktuCommander.syncKeep(newKeep)
      }
    }
  }

  def deactivateKeep(keep: Keep)(implicit session: RWSession): Unit = {
    ktlCommander.removeKeepFromAllLibraries(keep)
    ktuCommander.removeKeepFromAllUsers(keep)
    collectionCommander.deactivateKeepTags(keep)
    keepRepo.deactivate(keep)
  }

  // TODO(ryan): eventually this should basically JUST call ktlCommander.removeKeep and ktlCommander.internKeep
  def moveKeep(k: Keep, toLibrary: Library, userId: Id[User])(implicit session: RWSession): Either[LibraryError, Keep] = {
    val oldWay = keepRepo.getPrimaryByUriAndLibrary(k.uriId, toLibrary.id.get)
    val newWay = ktlRepo.getPrimaryByUriAndLibrary(k.uriId, toLibrary.id.get)
    if (newWay.map(_.keepId) != oldWay.map(_.id.get)) log.info(s"[KTL-MATCH] moveKeep(uri = ${k.uriId}, toLib = ${toLibrary.id.get}): existingKeepOpt ${newWay.map(_.keepId)} != ${oldWay.map(_.id.get)}")

    val existingKeepOpt = oldWay

    existingKeepOpt match {
      case None =>
        val movedKeep = keepRepo.save(k.withLibrary(toLibrary))
        ktlCommander.removeKeepFromLibrary(k.id.get, k.libraryId.get)
        ktlCommander.internKeepInLibrary(k, toLibrary, userId)
        Right(refreshLibrariesHash(movedKeep))
      case Some(existingKeep) if existingKeep.isInactive =>
        combineTags(k.id.get, existingKeep.id.get)

        keepRepo.deactivate(k) // TODO(ryan): don't deactivate keeps here
        ktlCommander.removeKeepFromLibrary(k.id.get, k.libraryId.get)

        val movedKeep = keepRepo.save(k.withLibrary(toLibrary).withId(existingKeep.id.get)) // overwrite the old keep
        ktlCommander.internKeepInLibrary(movedKeep, toLibrary, userId)

        Right(refreshLibrariesHash(movedKeep))
      case Some(existingKeep) =>
        if (toLibrary.id.get == k.libraryId.get) {
          Left(LibraryError.AlreadyExistsInDest)
        } else {
          ktlCommander.removeKeepFromLibrary(k.id.get, k.libraryId.get)
          refreshLibrariesHash(k)
          keepRepo.deactivate(k)
          combineTags(k.id.get, existingKeep.id.get)
          Left(LibraryError.AlreadyExistsInDest)
        }
    }
  }
  def copyKeep(k: Keep, toLibrary: Library, userId: Id[User], withSource: Option[KeepSource] = None)(implicit session: RWSession): Either[LibraryError, Keep] = {
    val currentKeepOpt = keepRepo.getPrimaryByUriAndLibrary(k.uriId, toLibrary.id.get)
    val newKeep = k.copy(
      id = None,
      externalId = ExternalId(),
      userId = userId,
      libraryId = Some(toLibrary.id.get),
      visibility = toLibrary.visibility,
      organizationId = toLibrary.organizationId,
      keptAt = currentDateTime,
      source = withSource.getOrElse(k.source),
      originalKeeperId = k.originalKeeperId.orElse(Some(userId))
    )

    currentKeepOpt match {
      case Some(existingKeep) if existingKeep.isActive =>
        combineTags(k.id.get, existingKeep.id.get)
        Left(LibraryError.AlreadyExistsInDest)

      case inactiveKeepOpt =>
        val persistedKeep = persistKeep(newKeep.copy(id = inactiveKeepOpt.flatMap(_.id)), Set(userId), Set(toLibrary.id.get))
        combineTags(k.id.get, persistedKeep.id.get)
        Right(persistedKeep)
    }
  }

  // combine tag info on both keeps & saves difference on the new Keep
  private def combineTags(oldKeepId: Id[Keep], newKeepId: Id[Keep])(implicit s: RWSession) = {
    val oldSet = keepToCollectionRepo.getCollectionsForKeep(oldKeepId).toSet
    val existingSet = keepToCollectionRepo.getCollectionsForKeep(newKeepId).toSet
    val tagsToAdd = oldSet.diff(existingSet)
    tagsToAdd.foreach { tagId =>
      val newKtc = KeepToCollection(keepId = newKeepId, collectionId = tagId)
      val ktcOpt = keepToCollectionRepo.getOpt(newKeepId, tagId)
      if (!ktcOpt.exists(_.isActive)) {
        // either overwrite (if the dead one exists) or create a new one
        keepToCollectionRepo.save(newKtc.copy(id = ktcOpt.map(_.id.get)))
      }
    }
  }

  def getKeepStream(userId: Id[User], limit: Int, beforeExtId: Option[ExternalId[Keep]], afterExtId: Option[ExternalId[Keep]]): Future[Seq[KeepInfo]] = {
    val keeps: Seq[Keep] = db.readOnlyReplica { implicit session =>
      keepRepo.getRecentKeeps(userId, limit, beforeExtId, afterExtId)
    }.foldRight((List.empty[Keep], Set.empty[Id[NormalizedURI]])) {
      case (keep, (acc, seenUriIds)) =>
        if (seenUriIds(keep.uriId)) (acc, seenUriIds) else (keep :: acc, seenUriIds + keep.uriId)
    }._1

    keepDecorator.decorateKeepsIntoKeepInfos(Some(userId), false, keeps, ProcessedImageSize.Large.idealSize, true)
  }
}

sealed trait HelpRankSelector { val name: String }
case object HelpRankRekeeps extends HelpRankSelector { val name = "rekeep" }
case object HelpRankDiscoveries extends HelpRankSelector { val name = "discovery" }
object HelpRankSelector {
  def apply(selector: String) = {
    selector match {
      case HelpRankRekeeps.name => HelpRankRekeeps
      case _ => HelpRankDiscoveries // bad! Need to check all clients to make sure they're sending in the correct string
    }
  }

  def unapply(selector: HelpRankSelector) = selector.name
}
