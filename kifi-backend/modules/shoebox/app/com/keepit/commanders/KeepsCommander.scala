package com.keepit.commanders

import java.util.concurrent.{ Callable, TimeUnit }

import com.keepit.common.cache.TransactionalCaching.Implicits._

import com.google.common.cache.{ CacheBuilder, Cache }
import com.google.inject.{ Singleton, Inject }

import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net._
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.curator.CuratorServiceClient
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ SocialId, SocialNetworks, SocialNetworkType, BasicUser }

import play.api.http.Status.{ FORBIDDEN, NOT_FOUND }
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.BodyParsers.parse

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import akka.actor.Scheduler
import com.keepit.eliza.ElizaServiceClient
import scala.util.{ Success, Failure }
import com.keepit.common.healthcheck.{ StackTrace, AirbrakeNotifier }
import com.keepit.common.performance._
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import org.joda.time.DateTime
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.typeahead.{ HashtagTypeahead, HashtagHit, TypeaheadHit }
import com.keepit.search.augmentation.{ LimitedAugmentationInfo, RestrictedKeepInfo, ItemAugmentationRequest, AugmentableItem }
import com.keepit.common.json.TupleFormat
import com.keepit.common.store.ImageSize
import com.keepit.common.CollectionHelpers

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
  )(BulkKeepSelection.apply _, unlift(BulkKeepSelection.unapply))
}

@Singleton
class KeepsCommander @Inject() (
    db: Database,
    keepInterner: KeepInterner,
    searchClient: SearchServiceClient,
    globalKeepCountCache: GlobalKeepCountCache,
    keepToCollectionRepo: KeepToCollectionRepo,
    keepRepo: KeepRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    collectionRepo: CollectionRepo,
    libraryAnalytics: LibraryAnalytics,
    heimdalClient: HeimdalServiceClient,
    airbrake: AirbrakeNotifier,
    normalizedURIInterner: NormalizedURIInterner,
    curator: CuratorServiceClient,
    clock: Clock,
    libraryCommander: LibraryCommander,
    libraryRepo: LibraryRepo,
    userRepo: UserRepo,
    userExperimentRepo: UserExperimentRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    hashtagTypeahead: HashtagTypeahead,
    hashtagCommander: HashtagCommander,
    keepDecorator: KeepDecorator,
    twitterPublishingCommander: TwitterPublishingCommander,
    facebookPublishingCommander: FacebookPublishingCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

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
        keepRepo.getByExtIdandLibraryId(keepExtId, libraryId) match {
          case Some(k) => Right(k)
          case None => Left(NOT_FOUND, "keep_not_found")
        }
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
    val (keep, isNewKeep) = keepInterner.internRawBookmark(rawBookmark, userId, library, source, installationId).get
    postSingleKeepReporting(keep, isNewKeep, library, socialShare)
    (keep, isNewKeep)
  }

  def keepMultiple(rawBookmarks: Seq[RawBookmarkRepresentation], libraryId: Id[Library], userId: Id[User], source: KeepSource, collection: Option[Either[ExternalId[Collection], String]], separateExisting: Boolean = false)(implicit context: HeimdalContext): (Seq[KeepInfo], Option[Int], Seq[String], Option[Seq[KeepInfo]]) = {
    val library = db.readOnlyReplica { implicit session => // change to readOnlyReplica when we can be 100% sure every user has libraries
      libraryRepo.get(libraryId)
    }
    val (newKeeps, existingKeeps, failures) = keepInterner.internRawBookmarksWithStatus(rawBookmarks, userId, library, source)
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

  def unkeepMultiple(keepInfos: Seq[RawBookmarkRepresentation], userId: Id[User])(implicit context: HeimdalContext): Seq[KeepInfo] = {
    val deactivatedBookmarks = db.readWrite { implicit s =>
      val bms = keepInfos.map { ki =>
        normalizedURIInterner.getByUri(ki.url).flatMap { uri =>
          val ko = keepRepo.getByUriAndUser(uri.id.get, userId).map { b =>
            val saved = keepRepo.save(b withActive false)
            log.info(s"[unkeepMulti] DEACTIVATE $saved (uri=$uri, ki=$ki)")
            saved
          }
          if (ko.isEmpty) { log.warn(s"[unkeepMulti($userId,${uri.id})] cannot find keep for ki=$ki; uri=$uri") }
          ko
        }
      }.flatten
      val collIds = bms.flatMap(bm => keepToCollectionRepo.getCollectionsForKeep(bm.id.get)).toSet
      collIds.foreach { cid => collectionRepo.collectionChanged(cid, inactivateIfEmpty = true) }
      bms
    }
    log.info(s"[unkeepMulti] deactivatedKeeps:(len=${deactivatedBookmarks.length}):${deactivatedBookmarks.mkString(",")}")

    val deactivatedKeepInfos = deactivatedBookmarks map KeepInfo.fromKeep
    finalizeUnkeeping(deactivatedBookmarks, userId)
    deactivatedKeepInfos
  }

  def unkeep(extId: ExternalId[Keep], userId: Id[User])(implicit context: HeimdalContext): Option[KeepInfo] = {
    db.readWrite { implicit session =>
      keepRepo.getByExtIdAndUser(extId, userId).map(deactivateKeepWithSession(_, userId))
    } map { keep =>
      finalizeUnkeeping(Seq(keep), userId)
      KeepInfo.fromKeep(keep)
    }
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
        var keepsToFinalize = Seq.empty[Keep]
        val (keeps, invalidKeepIds) = db.readWrite { implicit s =>
          val (keeps, invalidKeepIds) = keepIds.map { kId =>
            keepRepo.getByExtIdandLibraryId(kId, libId, excludeSet = Set.empty) match {
              case Some(k) if k.state != KeepStates.INACTIVE =>
                keepsToFinalize = k +: keepsToFinalize
                Left(deactivateKeepWithSession(k, userId))
              case Some(k) =>
                Left(k)
              case None =>
                Right(kId)
            }
          }.partition(_.isLeft)
          (keeps.map(_.left.get), invalidKeepIds.map(_.right.get))
        }
        finalizeUnkeeping(keepsToFinalize, userId)
        Right((keeps map KeepInfo.fromKeep, invalidKeepIds))
      case _ =>
        Left("permission_denied")
    }
  }

  private def finalizeUnkeeping(keeps: Seq[Keep], userId: Id[User])(implicit context: HeimdalContext): Unit = {
    // TODO: broadcast over any open user channels
    keeps.groupBy(_.libraryId).collect {
      case (Some(libId), keepsInLib) =>
        val library = db.readOnlyMaster { implicit s => libraryRepo.get(libId) }
        libraryAnalytics.unkeptPages(userId, keeps, library, context)
    }
    searchClient.updateKeepIndex()
  }

  private def deactivateKeepWithSession(keep: Keep, userId: Id[User])(implicit context: HeimdalContext, session: RWSession): Keep = {
    val saved = keepRepo.save(keep withState KeepStates.INACTIVE)
    log.info(s"[unkeep($userId)] deactivated keep=$saved")
    val library = libraryRepo.get(keep.libraryId.get)
    libraryRepo.save(library.copy(keepCount = keepRepo.getCountByLibrary(keep.libraryId.get)))
    keepToCollectionRepo.getCollectionsForKeep(saved.id.get) foreach { cid => collectionRepo.collectionChanged(cid, inactivateIfEmpty = true) }
    saved
  }

  def updateKeep(keep: Keep, isPrivate: Option[Boolean], title: Option[String])(implicit context: HeimdalContext): Option[Keep] = {
    val shouldBeUpdated = (isPrivate.isDefined && keep.isPrivate != isPrivate.get) || (title.isDefined && keep.title != title)
    if (shouldBeUpdated) Some {
      val updatedKeep = db.readWrite { implicit s => updateKeepWithSession(keep, isPrivate, title) }
      searchClient.updateKeepIndex()
      libraryAnalytics.updatedKeep(keep, updatedKeep, context)
      updatedKeep
    }
    else None
  }

  private def updateKeepWithSession(keep: Keep, isPrivate: Option[Boolean], title: Option[String])(implicit context: HeimdalContext, session: RWSession): Keep = {
    val updatedPrivacy = isPrivate getOrElse keep.isPrivate
    val updatedTitle = title orElse keep.title

    val (mainLib, secretLib) = libraryCommander.getMainAndSecretLibrariesForUser(keep.userId)
    def getLibFromPrivacy(isPrivate: Boolean) = {
      if (isPrivate) Some(secretLib.id.get) else Some(mainLib.id.get)
    }
    if (isPrivate.isDefined && isPrivate.get != keep.isPrivate) {
      keepRepo.save(keep.copy(visibility = Keep.isPrivateToVisibility(isPrivate.get), libraryId = getLibFromPrivacy(isPrivate.get)).withTitle(updatedTitle))
    } else {
      keepRepo.save(keep.withTitle(updatedTitle))
    }
  }

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
            case ktc if ktc.state == KeepToCollectionStates.INACTIVE && keepsById.contains(ktc.keepId) =>
              keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE, createdAt = clock.now()))
          }

          val updatedCollection = timing(s"addToCollection($collectionId,${keeps.length}) -- collection.modelChanged", 50) {
            collectionRepo.collectionChanged(collectionId, (newK2C.size + activated.size) > 0, inactivateIfEmpty = false)
          }
          val tagged: Set[KeepToCollection] = (activated ++ newK2C).toSet
          val taggingAt = currentDateTime
          tagged.foreach { ktc =>
            val targetKeep = keepsById(ktc.keepId)
            val editedNote = targetKeep.note.map { noteStr =>
              val existingTags = hashtagCommander.findAllHashtagNames(noteStr)
              if (existingTags.contains(collection.name.tag)) {
                noteStr
              } else {
                hashtagCommander.appendHashtagsToString(noteStr, Seq(collection.name))
              }
            } filterNot (_.isEmpty)
            keepRepo.save(targetKeep.copy(note = editedNote)) // notify keep index
            libraryAnalytics.taggedPage(updatedCollection, keepsById(ktc.keepId), context, taggingAt)
          }
          tagged
        }
      } catch {
        case t: Throwable =>
          airbrake.notify(s"error attaching collection id $collectionId to a batch of ${keeps.length} keeps (out of ${allKeeps.length})})", t)
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
          hashtagCommander.removeHashtagsFromString(noteStr, Set(collection.name))
        } filterNot (_.isEmpty)
        keepRepo.save(keepsById(ktc.keepId).copy(note = editedNote)) // notify keep index
        libraryAnalytics.untaggedPage(collection, keepsById(ktc.keepId), context, removedAt)
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
    val (bookmarks, _) = keepInterner.internRawBookmarks(rawBookmark, userId, library, source, installationId = kifiInstallationId)
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
    keepInterner.internRawBookmark(rawBookmark, userId, library, source, installationId = None) match {
      case Failure(e) => Left(e.getMessage)
      case Success((keep, isNewKeep)) =>
        val tags = db.readWrite { implicit s =>
          persistHashtags(userId, keep, selectedTagNames)(s, context)
          keepToCollectionRepo.getCollectionsForKeep(keep.id.get).map { id => collectionRepo.get(id) }
        }
        postSingleKeepReporting(keep, isNewKeep, library, socialShare)
        Right((keep, tags))
    }
  }

  // Changes a keep's notes based on the hashtags to persist!
  private def persistHashtags(userId: Id[User], keep: Keep, selectedTagNames: Seq[String])(implicit session: RWSession, context: HeimdalContext) = {

    val selectedCollections = selectedTagNames.map { getOrCreateTag(userId, _) }
    val selectedTagIds = selectedCollections.map(_.id.get).toSet
    val activeTagIds = keepToCollectionRepo.getCollectionsForKeep(keep.id.get).toSet

    val tagIdsToAdd = selectedTagIds.filterNot(activeTagIds.contains(_))
    val tagIdsToRemove = activeTagIds.filterNot(selectedTagIds.contains(_))
    val hashtagsToRemove = selectedCollections.filter(c => tagIdsToRemove.contains(c.id.get)).map(_.name.tag)
    val hashtagsToPersist = selectedCollections.filter(c => !tagIdsToRemove.contains(c.id.get) || activeTagIds.contains(c.id.get)).map(_.name.tag)

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

    val origNote = keep.note getOrElse ""
    val editedNote = keepDecorator.escapeMarkupNotes(origNote)
    val noteWithHashtagsRemoved = hashtagCommander.removeHashtagNamesFromString(editedNote, hashtagsToRemove.toSet)
    val noteWithHashtagsAppended = hashtagCommander.appendHashtagNamesToString(noteWithHashtagsRemoved, hashtagsToPersist)
    val finalNote = Some(noteWithHashtagsAppended.trim).filterNot(_.isEmpty)
    keepRepo.save(keep.copy(note = finalNote)) // notify keep index
  }

  def persistHashtagsForKeep(userId: Id[User], keep: Keep, selectedTags: Seq[String])(implicit context: HeimdalContext) = {
    db.readWrite { implicit s =>
      persistHashtags(userId, keep, selectedTags)(s, context)
    }
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

  private def searchTagsForKeep(userId: Id[User], keepId: ExternalId[Keep], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    val futureHits = searchTags(userId, query, None)
    val existingTags = db.readOnlyMaster { implicit session =>
      val keep = keepRepo.get(keepId)
      collectionRepo.getHashtagsByKeepId(keep.id.get)
    }
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

  def suggestTags(userId: Id[User], keepId: ExternalId[Keep], query: Option[String], limit: Int): Future[Seq[(Hashtag, Seq[(Int, Int)])]] = {
    query.map(_.trim).filter(_.nonEmpty) match {
      case Some(validQuery) => searchTagsForKeep(userId, keepId, validQuery, Some(limit)).map(_.map(hit => (hit.tag, hit.matches)))
      case None => suggestTagsForKeep(userId, keepId, Some(limit)).map(_.map((_, Seq.empty[(Int, Int)])))
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
      def call() = libraryCommander.getMainAndSecretLibrariesForUser(userId)
    })
    if (isPrivate) {
      secret
    } else {
      main
    }
  }

  def numKeeps(userId: Id[User]): Int = db.readOnlyReplica { implicit s => keepRepo.getCountByUser(userId) }

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
