package com.keepit.commanders

import java.util.concurrent.{ Callable, TimeUnit }

import com.keepit.common.cache.TransactionalCaching.Implicits._

import com.google.common.cache.{ CacheBuilder, Cache }
import com.google.inject.{ Singleton, Inject }

import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.URISanitizer
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.curator.CuratorServiceClient
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search.{ SharingUserInfo, SearchServiceClient }
import com.keepit.social.BasicUser

import play.api.http.Status.{ FORBIDDEN, NOT_FOUND }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Future
import akka.actor.Scheduler
import com.keepit.eliza.ElizaServiceClient
import scala.util.{ Success, Failure }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.performance._
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import org.joda.time.DateTime
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.typeahead.{ UserHashtagTypeaheadCommander, LibraryHashtagTypeaheadCommander, HashtagHit, HashtagHitWithKeepCount, TypeaheadHit }

case class KeepInfo(
  id: Option[ExternalId[Keep]] = None,
  title: Option[String],
  url: String,
  isPrivate: Boolean,
  createdAt: Option[DateTime] = None,
  others: Option[Int] = None,
  keepers: Option[Set[BasicUser]] = None,
  collections: Option[Set[String]] = None,
  tags: Option[Set[BasicCollection]] = None,
  uriSummary: Option[URISummary] = None,
  siteName: Option[String] = None,
  clickCount: Option[Int] = None,
  rekeepCount: Option[Int] = None,
  libraryId: Option[PublicId[Library]] = None)

object KeepInfo {

  implicit val format = (
    (__ \ 'id).formatNullable(ExternalId.format[Keep]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'isPrivate).formatNullable[Boolean].inmap[Boolean](_ getOrElse true, Some(_)) and
    (__ \ 'createdAt).formatNullable[DateTime] and
    (__ \ 'others).formatNullable[Int] and
    (__ \ 'keepers).formatNullable[Set[BasicUser]] and
    (__ \ 'collections).formatNullable[Set[String]] and
    (__ \ 'tags).formatNullable[Set[BasicCollection]] and
    (__ \ 'summary).formatNullable[URISummary] and
    (__ \ 'siteName).formatNullable[String] and
    (__ \ 'clickCount).formatNullable[Int] and
    (__ \ 'rekeepCount).formatNullable[Int] and
    (__ \ 'libraryId).formatNullable(PublicId.format[Library])
  )(KeepInfo.apply _, unlift(KeepInfo.unapply))

  def fromFullKeepInfo(info: FullKeepInfo, sanitize: Boolean = false) = {
    KeepInfo(
      Some(info.bookmark.externalId),
      info.bookmark.title,
      if (sanitize) URISanitizer.sanitize(info.bookmark.url) else info.bookmark.url,
      info.bookmark.isPrivate,
      Some(info.bookmark.createdAt),
      Some(info.others),
      Some(info.users),
      Some(info.collections.map(_.id)),
      Some(info.tags),
      info.uriSummary,
      info.siteName,
      info.clickCount,
      info.rekeepCount,
      info.libraryId
    )
  }

  // Are you looking for a decorated keep (with tags, rekeepers, etc)?
  // Use KeepsCommander#decorateKeepsIntoKeepInfos(userId, keeps)
  def fromKeep(bookmark: Keep)(implicit publicIdConfig: PublicIdConfiguration): KeepInfo = {
    KeepInfo(Some(bookmark.externalId), bookmark.title, bookmark.url, bookmark.isPrivate, libraryId = bookmark.libraryId.map(Library.publicId))
  }
}

case class FullKeepInfo(
  bookmark: Keep,
  users: Set[BasicUser],
  collections: Set[ExternalId[Collection]], //deprecated, will be removed in favor off tags once site transition is complete
  tags: Set[BasicCollection],
  others: Int,
  siteName: Option[String] = None,
  uriSummary: Option[URISummary] = None,
  clickCount: Option[Int] = None,
  rekeepCount: Option[Int] = None,
  libraryId: Option[PublicId[Library]] = None)

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
    basicUserRepo: BasicUserRepo,
    uriRepo: NormalizedURIRepo,
    keepRepo: KeepRepo,
    collectionRepo: CollectionRepo,
    userRepo: UserRepo,
    keptAnalytics: KeepingAnalytics,
    rawBookmarkFactory: RawBookmarkFactory,
    scheduler: Scheduler,
    heimdalClient: HeimdalServiceClient,
    eliza: ElizaServiceClient,
    localUserExperimentCommander: LocalUserExperimentCommander,
    airbrake: AirbrakeNotifier,
    uriSummaryCommander: URISummaryCommander,
    collectionCommander: CollectionCommander,
    normalizedURIInterner: NormalizedURIInterner,
    curator: CuratorServiceClient,
    clock: Clock,
    libraryCommander: LibraryCommander,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    keepImageCommander: KeepImageCommander,
    libraryHashtagTypeahead: LibraryHashtagTypeaheadCommander,
    searchServiceClient: SearchServiceClient,
    userHashtagTypeahead: UserHashtagTypeaheadCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  def getKeepsCountFuture(): Future[Int] = {
    globalKeepCountCache.getOrElseFuture(GlobalKeepCountKey()) {
      Future.sequence(searchServiceClient.indexInfoList()).map { results =>
        var countMap = Map.empty[String, Int]
        results.flatMap(_._2).foreach { info =>
          if (info.name.startsWith("BookmarkStore")) {
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
      if (count > 0) after.take(count) else after
    }

    val keepIdsF = selector match {
      case HelpRankRekeeps => heimdalClient.getUriReKeepsWithCountsByKeeper(userId) map { counts => counts.map(c => c.uriId -> c.count) }
      case HelpRankDiscoveries => heimdalClient.getUriDiscoveriesWithCountsByKeeper(userId) map { counts => counts.map(c => c.uriId -> c.count) }
    }
    keepIdsF.flatMap { counts =>
      val keeps = db.readOnlyReplica { implicit session =>
        val keepUriIds = filter(counts)
        val km = keepRepo.bulkGetByUserAndUriIds(userId, keepUriIds.toSet)
        km.valuesIterator.toList.sortBy(_.id).reverse
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

  def decorateKeepsIntoKeepInfos(perspectiveUserIdOpt: Option[Id[User]], keeps: Seq[Keep]): Future[Seq[KeepInfo]] = {
    val sharingInfosFuture = perspectiveUserIdOpt match {
      case Some(userId) =>
        searchClient.sharingUserInfo(userId, keeps.map(_.uriId))
      case None =>
        Future.successful(Seq.fill(keeps.length)(SharingUserInfo(Set.empty, 0)))
    }
    val pageInfosFuture = Future.sequence(keeps.map { keep =>
      getKeepSummary(keep)
    })

    val colls = db.readOnlyMaster { implicit s =>
      keeps.map { keep =>
        keepToCollectionRepo.getCollectionsForKeep(keep.id.get)
      }
    }.map(collectionCommander.getBasicCollections)

    for {
      sharingInfos <- sharingInfosFuture
      pageInfos <- pageInfosFuture
    } yield {
      val idToBasicUser = db.readOnlyMaster { implicit s =>
        basicUserRepo.loadAll(sharingInfos.flatMap(_.sharingUserIds).toSet)
      }
      val keepsInfo = (keeps zip colls, sharingInfos, pageInfos).zipped.map {
        case ((keep, collsForKeep), sharingInfoForKeep, pageInfoForKeep) =>
          val others = sharingInfoForKeep.keepersEdgeSetSize - sharingInfoForKeep.sharingUserIds.size - (if (keep.isPrivate) 0 else 1)
          KeepInfo(
            id = Some(keep.externalId),
            title = keep.title,
            url = keep.url,
            isPrivate = keep.isPrivate,
            createdAt = Some(keep.createdAt),
            others = Some(others),
            keepers = Some(sharingInfoForKeep.sharingUserIds.map(idToBasicUser)),
            collections = Some(collsForKeep.map(_.id.get.id).toSet), // Is this still used?
            tags = Some(collsForKeep.toSet),
            uriSummary = Some(pageInfoForKeep),
            siteName = DomainToNameMapper.getNameFromUrl(keep.url),
            clickCount = None,
            rekeepCount = None,
            libraryId = keep.libraryId.map(l => Library.publicId(l))
          )
      }
      keepsInfo
    }
  }

  private def getKeepSummary(keep: Keep, waiting: Boolean = false): Future[URISummary] = {
    uriSummaryCommander.getDefaultURISummary(keep.uriId, waiting)
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

        decorateKeepsIntoKeepInfos(Some(userId), keeps).map { keepInfos =>
          (keepInfos, clickCounts, rkCounts).zipped.map {
            case (keepInfo, clickCount, rkCount) =>
              keepInfo.copy(clickCount = clickCount, rekeepCount = rkCount)
          }
        }
    }
  }

  /**
   * This function currently does not return help rank info (can be added if necessary)
   * Waiting is enabled for URISummary fetching
   */
  def getFullKeepInfo(keepId: ExternalId[Keep], userId: Id[User], withPageInfo: Boolean): Option[Future[FullKeepInfo]] = {
    // might be called right after a keep is created (e.g. via Add a Keep on website)
    db.readOnlyMaster { implicit s => keepRepo.getOpt(keepId) } filter { _.isActive } map { keep =>
      val sharingInfoFuture = searchClient.sharingUserInfo(userId, keep.uriId)
      val pageInfoFuture = if (withPageInfo) getKeepSummary(keep, true).map(Some(_)) else Future.successful(None)
      for {
        sharingInfo <- sharingInfoFuture
        pageInfo <- pageInfoFuture
      } yield {
        val (idToBasicUser, colls) = db.readOnlyMaster { implicit s =>
          val idToBasicUser = basicUserRepo.loadAll(sharingInfo.sharingUserIds)
          val collIds: Seq[Id[Collection]] = keepToCollectionRepo.getCollectionsForKeep(keep.id.get)
          val colls: Seq[BasicCollection] = collectionCommander.getBasicCollections(collIds)
          (idToBasicUser, colls)
        }
        val others = sharingInfo.keepersEdgeSetSize - sharingInfo.sharingUserIds.size - (if (keep.isPrivate) 0 else 1)
        FullKeepInfo(keep, sharingInfo.sharingUserIds map idToBasicUser, colls.map(_.id.get).toSet, colls.toSet, others, DomainToNameMapper.getNameFromUrl(keep.url), pageInfo)
      }
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
  // TODO: Can this return a Keep object?
  def keepOne(rawBookmark: RawBookmarkRepresentation, userId: Id[User], libraryId: Id[Library], installationId: Option[ExternalId[KifiInstallation]], source: KeepSource)(implicit context: HeimdalContext): KeepInfo = {
    log.info(s"[keep] $rawBookmark")
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    keepInterner.internRawBookmark(rawBookmark, userId, library, source, installationId) match {
      case Failure(e) =>
        throw e
      case Success(keep) =>
        SafeFuture {
          searchClient.updateKeepIndex()
          curator.updateUriRecommendationFeedback(userId, keep.uriId, UriRecommendationFeedback(kept = Some(true)))
        }
        KeepInfo.fromKeep(keep)
    }
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
      libraryHashtagTypeahead.refresh(libraryId)
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
      collIds.foreach { cid => collectionRepo.collectionChanged(cid) }
      bms
    }
    log.info(s"[unkeepMulti] deactivatedKeeps:(len=${deactivatedBookmarks.length}):${deactivatedBookmarks.mkString(",")}")

    val deactivatedKeepInfos = deactivatedBookmarks map KeepInfo.fromKeep
    keptAnalytics.unkeptPages(userId, deactivatedBookmarks, context)
    searchClient.updateKeepIndex()
    libraryHashtagTypeahead.refreshByIds(deactivatedBookmarks.map(_.libraryId).flatten.distinct)
    deactivatedKeepInfos
  }

  def unkeepBulk(selection: BulkKeepSelection, userId: Id[User])(implicit context: HeimdalContext): Seq[KeepInfo] = {
    val keeps = db.readWrite { implicit s =>
      val keeps = getKeepsInBulkSelection(selection, userId)
      keeps.map(setKeepStateWithSession(_, KeepStates.INACTIVE, userId))
    }
    finalizeUnkeeping(keeps, userId)
    keeps map KeepInfo.fromKeep
  }

  def unkeepBatch(ids: Seq[ExternalId[Keep]], userId: Id[User])(implicit context: HeimdalContext): (Seq[KeepInfo], Seq[ExternalId[Keep]]) = {
    val (keeps, failures) = db.readWrite { implicit s =>
      val keepMap = ids map { id =>
        id -> keepRepo.getByExtIdAndUser(id, userId)
      }
      val (successes, failures) = keepMap.partition(_._2.nonEmpty)
      val keeps = successes.map(_._2).flatten.map(setKeepStateWithSession(_, KeepStates.INACTIVE, userId))
      (keeps, failures.map(_._1))
    }
    finalizeUnkeeping(keeps, userId)
    (keeps map KeepInfo.fromKeep, failures)
  }

  def unkeep(extId: ExternalId[Keep], userId: Id[User])(implicit context: HeimdalContext): Option[KeepInfo] = {
    db.readWrite { implicit session =>
      keepRepo.getByExtIdAndUser(extId, userId).map(setKeepStateWithSession(_, KeepStates.INACTIVE, userId))
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
      case Some(mem) if mem.hasWriteAccess =>
        var keepsToFinalize = Seq.empty[Keep]
        val (keeps, invalidKeepIds) = db.readWrite { implicit s =>
          val (keeps, invalidKeepIds) = keepIds.map { kId =>
            keepRepo.getByExtIdandLibraryId(kId, libId, excludeState = None) match {
              case Some(k) if (k.isActive) =>
                keepsToFinalize = k +: keepsToFinalize
                Left(setKeepStateWithSession(k, KeepStates.INACTIVE, userId))
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
    keptAnalytics.unkeptPages(userId, keeps, context)
    searchClient.updateKeepIndex()
    libraryHashtagTypeahead.refreshByIds(keeps.map(_.libraryId).flatten.distinct)
  }

  def rekeepBulk(selection: BulkKeepSelection, userId: Id[User])(implicit context: HeimdalContext): Int = {
    val keeps = db.readWrite { implicit s =>
      val keeps = getKeepsInBulkSelection(selection, userId).filter(_.state != KeepStates.ACTIVE)
      keeps.map(setKeepStateWithSession(_, KeepStates.ACTIVE, userId))
    }
    keptAnalytics.rekeptPages(userId, keeps, context)
    searchClient.updateKeepIndex()
    libraryHashtagTypeahead.refreshByIds(keeps.map(_.libraryId).flatten.distinct)
    keeps.length
  }

  private def setKeepStateWithSession(keep: Keep, state: State[Keep], userId: Id[User])(implicit context: HeimdalContext, session: RWSession): Keep = {
    val saved = keepRepo.save(keep withState state)
    log.info(s"[unkeep($userId)] deactivated keep=$saved")
    keepToCollectionRepo.getCollectionsForKeep(saved.id.get) foreach { cid => collectionRepo.collectionChanged(cid) }
    saved
  }

  def setKeepPrivacyBulk(selection: BulkKeepSelection, userId: Id[User], isPrivate: Boolean)(implicit context: HeimdalContext): Int = {
    val (oldKeeps, newKeeps) = db.readWrite { implicit s =>
      val keeps = getKeepsInBulkSelection(selection, userId)
      val oldKeeps = keeps.filter(_.isPrivate != isPrivate)
      val newKeeps = oldKeeps.map(updateKeepWithSession(_, Some(isPrivate), None))
      (oldKeeps, newKeeps)
    }
    (oldKeeps zip newKeeps) map { case (oldKeep, newKeep) => keptAnalytics.updatedKeep(oldKeep, newKeep, context) }
    searchClient.updateKeepIndex()
    newKeeps.length
  }

  def updateKeep(keep: Keep, isPrivate: Option[Boolean], title: Option[String])(implicit context: HeimdalContext): Option[Keep] = {
    val shouldBeUpdated = (isPrivate.isDefined && keep.isPrivate != isPrivate.get) || (title.isDefined && keep.title != title)
    if (shouldBeUpdated) Some {
      val updatedKeep = db.readWrite { implicit s => updateKeepWithSession(keep, isPrivate, title) }
      searchClient.updateKeepIndex()
      keptAnalytics.updatedKeep(keep, updatedKeep, context)
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
      case Some(mem) if mem.hasWriteAccess =>
        val normTitle = title.map(_.trim).filter(_.nonEmpty)
        db.readWrite { implicit s =>
          keepRepo.getByExtIdandLibraryId(keepId, libId) match {
            case Some(keep) if normTitle.isDefined && normTitle != keep.title =>
              val keep2 = keepRepo.save(keep.withTitle(normTitle))
              searchClient.updateKeepIndex()
              keptAnalytics.updatedKeep(keep, keep2, context)
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
      (keeps, collection)
      assert(collection.id.nonEmpty, s"Collection id is undefined: $collection")
      if (isAdd) addToCollection(collection.id.get, keeps) else removeFromCollection(collection, keeps)
      keeps.length
    } getOrElse 0
  }

  def addToCollection(collectionId: Id[Collection], keeps: Seq[Keep], updateIndex: Boolean = true)(implicit context: HeimdalContext): Set[KeepToCollection] = timing(s"addToCollection($collectionId,${keeps.length})") {
    val result = db.readWrite { implicit s =>
      val keepsById = keeps.map(keep => keep.id.get -> keep).toMap
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
        collectionRepo.collectionChanged(collectionId, (newK2C.size + activated.size) > 0)
      }
      val tagged = (activated ++ newK2C).toSet
      val taggingAt = currentDateTime
      tagged.foreach { ktc =>
        keepRepo.save(keepsById(ktc.keepId)) // notify keep index
        keptAnalytics.taggedPage(updatedCollection, keepsById(ktc.keepId), context, taggingAt)
      }
      tagged
    }
    if (updateIndex) {
      searchClient.updateKeepIndex()
      libraryHashtagTypeahead.refreshByIds(keeps.map(_.libraryId).flatten.distinct)
    }
    result
  }

  def removeFromCollection(collection: Collection, keeps: Seq[Keep])(implicit context: HeimdalContext): Set[KeepToCollection] = {
    db.readWrite(attempts = 2) { implicit s =>
      val keepsById = keeps.map(keep => keep.id.get -> keep).toMap
      val removed = keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None) collect {
        case ktc if ktc.state != KeepToCollectionStates.INACTIVE && keepsById.contains(ktc.keepId) =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
      }
      collectionRepo.collectionChanged(collection.id.get)

      val removedAt = currentDateTime
      removed.foreach { ktc =>
        keepRepo.save(keepsById(ktc.keepId)) // notify keep index
        keptAnalytics.untaggedPage(collection, keepsById(ktc.keepId), context, removedAt)
      }
      removed.toSet
    } tap { _ =>
      searchClient.updateKeepIndex()
      libraryHashtagTypeahead.refreshByIds(keeps.map(_.libraryId).flatten.distinct)
    }
  }

  def tagUrl(tag: Collection, rawBookmark: Seq[RawBookmarkRepresentation], userId: Id[User], libraryId: Id[Library], source: KeepSource, kifiInstallationId: Option[ExternalId[KifiInstallation]])(implicit context: HeimdalContext) = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    val (bookmarks, _) = keepInterner.internRawBookmarks(rawBookmark, userId, library, source, installationId = kifiInstallationId)
    addToCollection(tag.id.get, bookmarks) // why doesn't this update search?
  }

  def getOrCreateTag(userId: Id[User], name: String)(implicit context: HeimdalContext): Collection = {
    val normalizedName = Hashtag(name.trim.replaceAll("""\s+""", " ").take(Collection.MaxNameLength))
    val collection = db.readOnlyReplica { implicit s =>
      collectionRepo.getByUserAndName(userId, normalizedName, excludeState = None)
    }
    collection match {
      case Some(t) if t.isActive => t
      case Some(t) => db.readWrite { implicit s => collectionRepo.save(t.copy(state = CollectionStates.ACTIVE, createdAt = clock.now())) } tap (keptAnalytics.createdTag(_, context))
      case None => db.readWrite { implicit s => collectionRepo.save(Collection(userId = userId, name = normalizedName)) } tap (keptAnalytics.createdTag(_, context))
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
        keptAnalytics.untaggedPage(collection, keep, context)
        keep
      }
    }
    keep.foreach(_.libraryId.foreach(libraryHashtagTypeahead.refresh))
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
    libraryHashtagTypeahead.refreshByIds(keeps.map(_.libraryId).flatten.distinct)
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
  def keepWithSelectedTags(userId: Id[User], rawBookmark: RawBookmarkRepresentation, libraryId: Id[Library], source: KeepSource, selectedTagNames: Seq[String])(implicit context: HeimdalContext): Either[String, (KeepInfo, Seq[Collection])] = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    val keepsWithTags = keepInterner.internRawBookmark(rawBookmark, userId, library, source, installationId = None) match {
      case Failure(e) => Left(e.getMessage)
      case Success(keep) =>
        val tags = db.readWrite { implicit s =>
          val selectedTagIds = selectedTagNames.map { getOrCreateTag(userId, _).id.get }
          val activeTagIds = keepToCollectionRepo.getCollectionsForKeep(keep.id.get)
          val tagsToAdd = selectedTagIds.filterNot(activeTagIds.contains(_))
          val tagsToRemove = activeTagIds.filterNot(selectedTagIds.contains(_))

          tagsToAdd.map { tagId =>
            keepToCollectionRepo.getOpt(keep.id.get, tagId) match {
              case None => keepToCollectionRepo.save(KeepToCollection(keepId = keep.id.get, collectionId = tagId))
              case Some(k2c) => keepToCollectionRepo.save(k2c.copy(state = KeepToCollectionStates.ACTIVE))
            }
            collectionRepo.collectionChanged(tagId, true)
          }
          tagsToRemove.map { tagId =>
            keepToCollectionRepo.remove(keep.id.get, tagId)
            collectionRepo.collectionChanged(tagId, false)
          }
          keepRepo.save(keep) // notify keep index
          keepToCollectionRepo.getCollectionsForKeep(keep.id.get).map { id => collectionRepo.get(id) }
        }
        Right((KeepInfo.fromKeep(keep), tags))
    }
    libraryHashtagTypeahead.refresh(libraryId)
    keepsWithTags
  }

  def searchLibraryTags(libraryId: Id[Library], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    implicit val hitOrdering = TypeaheadHit.defaultOrdering[Hashtag]
    libraryHashtagTypeahead.topN(libraryId, query, limit).map(_.map(_.info)).map(HashtagHit.highlight(query, _))
  }

  def searchUserTags(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[HashtagHitWithKeepCount]] = {
    implicit val hitOrdering = TypeaheadHit.defaultOrdering[(Hashtag, Int)]
    userHashtagTypeahead.topN(userId, query, limit).map(_.map(_.info)).map(HashtagHitWithKeepCount.highlight(query, _))
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
