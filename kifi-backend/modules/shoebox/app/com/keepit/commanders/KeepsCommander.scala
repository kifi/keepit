package com.keepit.commanders

import com.google.inject.Inject

import com.keepit.common.KestrelCombinator
import com.keepit.common.crypto.PublicId
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.URISanitizer
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser

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
      (if (sanitize) URISanitizer.sanitize(info.bookmark.url) else info.bookmark.url),
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
      None // todo(andrew): Add library external id
    )
  }

  def fromBookmark(bookmark: Keep): KeepInfo = {
    KeepInfo(Some(bookmark.externalId), bookmark.title, bookmark.url, bookmark.isPrivate, libraryId = None) // todo(andrew): Add library external id
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

case class KeepInfosWithCollection(
  collection: Option[Either[ExternalId[Collection], String]], keeps: Seq[KeepInfo])

object KeepInfosWithCollection {
  implicit val reads = (
    (__ \ 'collectionId).read(ExternalId.format[Collection])
    .map[Option[Either[ExternalId[Collection], String]]](c => Some(Left(c)))
    orElse (__ \ 'collectionName).readNullable[String]
    .map(_.map[Either[ExternalId[Collection], String]](Right(_))) and
    (__ \ 'keeps).read[Seq[KeepInfo]]
  )(KeepInfosWithCollection.apply _)
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

class KeepsCommander @Inject() (
    db: Database,
    keepInterner: KeepInterner,
    searchClient: SearchServiceClient,
    keepToCollectionRepo: KeepToCollectionRepo,
    basicUserRepo: BasicUserRepo,
    uriRepo: NormalizedURIRepo,
    keepRepo: KeepRepo,
    collectionRepo: CollectionRepo,
    userRepo: UserRepo,
    userBookmarkClicksRepo: UserBookmarkClicksRepo,
    keepDiscoveriesRepo: KeepDiscoveryRepo,
    rekeepRepo: ReKeepRepo,
    kifiHitCache: KifiHitCache,
    keptAnalytics: KeepingAnalytics,
    rawBookmarkFactory: RawBookmarkFactory,
    scheduler: Scheduler,
    eliza: ElizaServiceClient,
    localUserExperimentCommander: LocalUserExperimentCommander,
    airbrake: AirbrakeNotifier,
    uriSummaryCommander: URISummaryCommander,
    collectionCommander: CollectionCommander,
    normalizedURIInterner: NormalizedURIInterner,
    clock: Clock) extends Logging {

  private def getKeeps(
    beforeOpt: Option[ExternalId[Keep]],
    afterOpt: Option[ExternalId[Keep]],
    collectionId: Option[ExternalId[Collection]],
    helprankOpt: Option[String],
    count: Int,
    userId: Id[User]): (Seq[Keep], Option[Collection], Map[Id[Keep], Int], Map[Id[Keep], Int]) = {

    @inline def filter(counts: Map[Id[NormalizedURI], Int])(implicit r: RSession): Seq[Id[NormalizedURI]] = {
      val uriIds = counts.toSeq.sortBy(_._2)(Ordering[Int].reverse).map(_._1)
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

    db.readOnlyReplica { implicit ro =>
      val collectionOpt = (collectionId map { id => collectionRepo.getByUserAndExternalId(userId, id) }).flatten
      val keeps = collectionOpt match {
        case Some(collection) =>
          keepRepo.getByUserAndCollection(userId, collection.id.get, beforeOpt, afterOpt, count)
        case None =>
          helprankOpt match {
            case Some(selector) =>
              val keepIds = selector.trim match {
                case "rekeep" => filter(rekeepRepo.getUriReKeepCountsByKeeper(userId))
                case _ => filter(keepDiscoveriesRepo.getUriDiscoveryCountsByKeeper(userId)) // click
              }
              val km = keepRepo.bulkGetByUserAndUriIds(userId, keepIds.toSet)
              log.info(s"[getKeeps($beforeOpt,$afterOpt,${helprankOpt.get},$count,$userId)] keeps=$km")
              km.valuesIterator.toList
            case _ => keepRepo.getByUser(userId, beforeOpt, afterOpt, count)
          }
      }
      val (clkCount, rkCount) = helprankOpt match {
        case None => (Map.empty[Id[Keep], Int], Map.empty[Id[Keep], Int])
        case Some(s) => (keepDiscoveriesRepo.getDiscoveryCountsByKeepIds(userId, keeps.map(_.id.get).toSet), rekeepRepo.getReKeepCountsByKeepIds(userId, keeps.map(_.id.get).toSet))
      }
      (keeps, collectionOpt, clkCount, rkCount)
    }
  }

  private def getKeepSummary(keep: Keep, waiting: Boolean = false): Future[URISummary] = {
    uriSummaryCommander.getDefaultURISummary(keep.uriId, waiting)
  }

  def allKeeps(
    before: Option[ExternalId[Keep]],
    after: Option[ExternalId[Keep]],
    collectionId: Option[ExternalId[Collection]],
    helprankOpt: Option[String],
    count: Int,
    userId: Id[User],
    withPageInfo: Boolean): Future[(Option[BasicCollection], Seq[FullKeepInfo])] = {
    val (keeps, collectionOpt, clkCounts, rkCounts) = getKeeps(before, after, collectionId, helprankOpt, count, userId)
    val sharingInfosFuture = searchClient.sharingUserInfo(userId, keeps.map(_.uriId))
    val pageInfosFuture = Future.sequence(keeps.map { keep =>
      if (withPageInfo) getKeepSummary(keep).map(Some(_)) else Future.successful(None)
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
        case ((keep, colls), sharingInfos, pageInfos) =>
          val others = sharingInfos.keepersEdgeSetSize - sharingInfos.sharingUserIds.size - (if (keep.isPrivate) 0 else 1)
          FullKeepInfo(keep, sharingInfos.sharingUserIds map idToBasicUser, colls.map(_.id.get).toSet, colls.toSet, others, DomainToNameMapper.getNameFromUrl(keep.url), pageInfos, clkCounts.get(keep.id.get), rkCounts.get(keep.id.get))
      }
      (collectionOpt.map { c => BasicCollection.fromCollection(c.summary) }, keepsInfo)
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

  def keepOne(keepJson: JsObject, userId: Id[User], installationId: Option[ExternalId[KifiInstallation]], source: KeepSource)(implicit context: HeimdalContext): KeepInfo = {
    log.info(s"[keep] $keepJson")
    val rawBookmark = rawBookmarkFactory.toRawBookmark(keepJson)
    keepInterner.internRawBookmark(rawBookmark, userId, source, mutatePrivacy = true, installationId) match {
      case Failure(e) =>
        throw e
      case Success(keep) =>
        SafeFuture {
          searchClient.updateURIGraph()
        }
        KeepInfo.fromBookmark(keep)
    }
  }

  def keepMultiple(keepInfosWithCollection: KeepInfosWithCollection, userId: Id[User], source: KeepSource, separateExisting: Boolean = false)(implicit context: HeimdalContext): (Seq[KeepInfo], Option[Int], Seq[String], Option[Seq[KeepInfo]]) = {
    val KeepInfosWithCollection(collection, keepInfos) = keepInfosWithCollection
    val (newKeeps, existingKeeps, failures) = keepInterner.internRawBookmarksWithStatus(rawBookmarkFactory.toRawBookmark(keepInfos), userId, source, mutatePrivacy = true)
    val keeps = newKeeps ++ existingKeeps
    log.info(s"[keepMulti] keeps(len=${keeps.length}):${keeps.mkString(",")}")
    val addedToCollection = collection flatMap {
      case Left(collectionId) => db.readOnlyReplica { implicit s => collectionRepo.getOpt(collectionId) }
      case Right(name) => Some(getOrCreateTag(userId, name))
    } map { coll =>
      addToCollection(coll.id.get, keeps).size
    }
    SafeFuture {
      searchClient.updateURIGraph()
    }
    val (returnedKeeps, existingKeepsOpt) = if (separateExisting) {
      (newKeeps, Some(existingKeeps))
    } else {
      (newKeeps ++ existingKeeps, None)
    }
    (returnedKeeps.map(KeepInfo.fromBookmark), addedToCollection, failures map (_.url), existingKeepsOpt map (_.map(KeepInfo.fromBookmark)))
  }

  def unkeepMultiple(keepInfos: Seq[KeepInfo], userId: Id[User])(implicit context: HeimdalContext): Seq[KeepInfo] = {
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

    val deactivatedKeepInfos = deactivatedBookmarks map KeepInfo.fromBookmark
    keptAnalytics.unkeptPages(userId, deactivatedBookmarks, context)
    searchClient.updateURIGraph()
    deactivatedKeepInfos
  }

  def unkeepBulk(selection: BulkKeepSelection, userId: Id[User])(implicit context: HeimdalContext): Seq[KeepInfo] = {
    val keeps = db.readWrite { implicit s =>
      val keeps = getKeepsInBulkSelection(selection, userId)
      keeps.map(setKeepStateWithSession(_, KeepStates.INACTIVE, userId))
    }
    finalizeUnkeeping(keeps, userId)
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
    (finalizeUnkeeping(keeps, userId), failures)
  }

  def unkeep(extId: ExternalId[Keep], userId: Id[User])(implicit context: HeimdalContext): Option[KeepInfo] = {
    db.readWrite { implicit session =>
      keepRepo.getByExtIdAndUser(extId, userId).map(setKeepStateWithSession(_, KeepStates.INACTIVE, userId))
    } flatMap { keep =>
      finalizeUnkeeping(Seq(keep), userId).headOption
    }
  }

  private def finalizeUnkeeping(keeps: Seq[Keep], userId: Id[User])(implicit context: HeimdalContext): Seq[KeepInfo] = {
    // TODO: broadcast over any open user channels
    keptAnalytics.unkeptPages(userId, keeps, context)
    searchClient.updateURIGraph()
    keeps map KeepInfo.fromBookmark
  }

  def rekeepBulk(selection: BulkKeepSelection, userId: Id[User])(implicit context: HeimdalContext): Int = {
    val keeps = db.readWrite { implicit s =>
      val keeps = getKeepsInBulkSelection(selection, userId).filter(_.state != KeepStates.ACTIVE)
      keeps.map(setKeepStateWithSession(_, KeepStates.ACTIVE, userId))
    }
    keptAnalytics.rekeptPages(userId, keeps, context)
    searchClient.updateURIGraph()
    keeps.length
  }

  private def setKeepStateWithSession(keep: Keep, state: State[Keep], userId: Id[User])(implicit context: HeimdalContext, session: RWSession): Keep = {
    val saved = keepRepo.save(keep withActive false)
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
    searchClient.updateURIGraph()
    newKeeps.length
  }

  def updateKeep(keep: Keep, isPrivate: Option[Boolean], title: Option[String])(implicit context: HeimdalContext): Option[Keep] = {
    val shouldBeUpdated = (isPrivate.isDefined && keep.isPrivate != isPrivate.get) || (title.isDefined && keep.title != title)
    if (shouldBeUpdated) Some {
      val updatedKeep = db.readWrite { implicit s => updateKeepWithSession(keep, isPrivate, title) }
      searchClient.updateURIGraph()
      keptAnalytics.updatedKeep(keep, updatedKeep, context)
      updatedKeep
    }
    else None
  }

  private def updateKeepWithSession(keep: Keep, isPrivate: Option[Boolean], title: Option[String])(implicit context: HeimdalContext, session: RWSession): Keep = {
    val updatedPrivacy = isPrivate getOrElse keep.isPrivate
    val updatedTitle = title orElse keep.title
    keepRepo.save(keep.withPrivate(updatedPrivacy).withTitle(updatedTitle))
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

  def addToCollection(collectionId: Id[Collection], keeps: Seq[Keep], updateUriGraph: Boolean = true)(implicit context: HeimdalContext): Set[KeepToCollection] = timing(s"addToCollection($collectionId,${keeps.length})") {
    val result = db.readWrite { implicit s =>
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

      timing(s"addToCollection($collectionId,${keeps.length}) -- collection.modelChanged", 50) {
        collectionRepo.modelChanged(collection, (newK2C.size + activated.size) > 0)
      }
      val tagged = (activated ++ newK2C).toSet
      val taggingAt = currentDateTime
      tagged.foreach(ktc => keptAnalytics.taggedPage(collection, keepsById(ktc.keepId), context, taggingAt))
      tagged
    }
    if (updateUriGraph) {
      searchClient.updateURIGraph()
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
      removed.foreach(ktc => keptAnalytics.untaggedPage(collection, keepsById(ktc.keepId), context, removedAt))
      removed.toSet
    } tap { _ => searchClient.updateURIGraph() }
  }

  def tagUrl(tag: Collection, json: JsValue, userId: Id[User], source: KeepSource, kifiInstallationId: Option[ExternalId[KifiInstallation]])(implicit context: HeimdalContext) = {
    val (bookmarks, _) = keepInterner.internRawBookmarks(rawBookmarkFactory.toRawBookmarks(json), userId, source, mutatePrivacy = false, installationId = kifiInstallationId)
    addToCollection(tag.id.get, bookmarks)
  }

  def getOrCreateTag(userId: Id[User], name: String)(implicit context: HeimdalContext): Collection = {
    val normalizedName = name.trim.replaceAll("""\s+""", " ").take(Collection.MaxNameLength)
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
    db.readWrite { implicit s =>
      for {
        uri <- normalizedURIInterner.getByUri(url)
        keep <- keepRepo.getByUriAndUser(uri.id.get, userId)
        collection <- collectionRepo.getOpt(id)
      } {
        keepToCollectionRepo.remove(keepId = keep.id.get, collectionId = collection.id.get)
        collectionRepo.collectionChanged(collection.id.get)
        keptAnalytics.untaggedPage(collection, keep, context)
      }
    }
    searchClient.updateURIGraph()
  }

  def clearTags(url: String, userId: Id[User]): Unit = {
    db.readWrite { implicit s =>
      for {
        uri <- normalizedURIInterner.getByUri(url).toSeq
        keep <- keepRepo.getByUriAndUser(uri.id.get, userId).toSeq
        ktc <- keepToCollectionRepo.getByKeep(keep.id.get)
      } {
        keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
        collectionRepo.collectionChanged(ktc.collectionId)
      }
    }
    searchClient.updateURIGraph()
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

  def keepWithMultipleTags(userId: Id[User], keepsWithTags: Seq[(KeepInfo, Seq[String])], source: KeepSource)(implicit context: HeimdalContext): Map[Collection, Seq[Keep]] = {
    val (bookmarks, _) = keepInterner.internRawBookmarks(
      rawBookmarkFactory.toRawBookmark(keepsWithTags.map(_._1)),
      userId,
      mutatePrivacy = true,
      installationId = None,
      source = KeepSource.default
    )

    val keepsByUrl = bookmarks.map(keep => keep.url -> keep).toMap

    val keepsByTagName = keepsWithTags.flatMap {
      case (keepInfo, tags) =>
        tags.map(tagName => (tagName, keepsByUrl(keepInfo.url)))
    }.groupBy(_._1).mapValues(_.map(_._2))

    val keepsByTag = keepsByTagName.map {
      case (tagName, keeps) =>
        val tag = getOrCreateTag(userId, tagName)
        addToCollection(tag.id.get, keeps, updateUriGraph = false)
        tag -> keeps
    }.toMap

    searchClient.updateURIGraph()

    keepsByTag
  }

  def setFirstKeeps(userId: Id[User], keeps: Seq[Keep]): Unit = {
    db.readWrite { implicit session =>
      val origin = keepRepo.oldestKeep(userId).map(_.createdAt) getOrElse currentDateTime
      keeps.zipWithIndex.foreach {
        case (keep, i) =>
          keepRepo.save(keep.copy(createdAt = origin.minusSeconds(i + 1)))
      }
    }
  }

  def processKifiHit(discoverer: Id[User], kifiHit: SanitizedKifiHit): Unit = {
    db.readWrite { implicit rw =>
      val keepers = kifiHit.context.keepers
      if (kifiHit.context.isOwnKeep || kifiHit.context.isPrivate || keepers.isEmpty) userBookmarkClicksRepo.increaseCounts(discoverer, kifiHit.uriId, true)
      else {
        kifiHitCache.get(KifiHitKey(discoverer, kifiHit.uriId)) match { // simple throttling
          case Some(hit) =>
            log.warn(s"[kifiHit($discoverer,${kifiHit.uriId})] already recorded kifiHit ($hit) for user within threshold -- skip")
          case None =>
            kifiHitCache.set(KifiHitKey(discoverer, kifiHit.uriId), kifiHit)
            keepers.foreach { extId =>
              val keeperId: Id[User] = userRepo.get(extId).id.get
              userBookmarkClicksRepo.increaseCounts(keeperId, kifiHit.uriId, false)
              keepRepo.getByUriAndUser(kifiHit.uriId, keeperId) match {
                case None =>
                  log.warn(s"[kifiHit($discoverer,${kifiHit.uriId},${keepers.mkString(",")})] keep not found for keeperId=$keeperId")
                // move on
                case Some(keep) =>
                  val saved = keepDiscoveriesRepo.save(KeepDiscovery(hitUUID = kifiHit.uuid, numKeepers = keepers.length, keeperId = keeperId, keepId = keep.id.get, uriId = keep.uriId, origin = Some(kifiHit.origin)))
                  log.info(s"[kifiHit($discoverer, ${kifiHit.uriId}, ${keepers.mkString(",")})] saved $saved")
              }
            }
        }
      }
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
}
