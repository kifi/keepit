package com.keepit.commanders

import com.google.inject.Inject

import com.keepit.common.KestrelCombinator
import com.keepit.common.db._
import com.keepit.common.strings._
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
import com.keepit.common.store.{S3ImageStore, ImageSize}
import scala.util.{Success, Failure}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.performance._

case class KeepInfo(id: Option[ExternalId[Keep]] = None, title: Option[String], url: String, isPrivate: Boolean)

case class FullKeepInfo(bookmark: Keep, users: Set[BasicUser], collections: Set[ExternalId[Collection]], others: Int, clickCount:Int = 0)

class FullKeepInfoWriter(sanitize: Boolean = false) extends Writes[FullKeepInfo] {
  def writes(info: FullKeepInfo) = Json.obj(
    "id" -> info.bookmark.externalId.id,
    "title" -> info.bookmark.title,
    "url" -> (if(sanitize) URISanitizer.sanitize(info.bookmark.url) else info.bookmark.url),
    "isPrivate" -> info.bookmark.isPrivate,
    "createdAt" -> info.bookmark.createdAt,
    "others" -> info.others,
    "keepers" -> info.users,
    "clickCount" -> info.clickCount,
    "collections" -> info.collections.map(_.id)
  )
}

object KeepInfo {
  implicit val format = (
    (__ \ 'id).formatNullable(ExternalId.format[Keep]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'isPrivate).formatNullable[Boolean].inmap[Boolean](_ getOrElse true, Some(_))
  )(KeepInfo.apply _, unlift(KeepInfo.unapply))

  def fromBookmark(bookmark: Keep): KeepInfo = {
    KeepInfo(Some(bookmark.externalId), bookmark.title, bookmark.url, bookmark.isPrivate)
  }
}

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
    keepClicksRepo: KeepClickRepo,
    rekeepRepo: ReKeepRepo,
    kifiHitCache: KifiHitCache,
    keptAnalytics: KeepingAnalytics,
    rawBookmarkFactory: RawBookmarkFactory,
    scheduler: Scheduler,
    eliza: ElizaServiceClient,
    localUserExperimentCommander: LocalUserExperimentCommander,
    imageRepo: ImageInfoRepo,
    imageStore: S3ImageStore,
    airbrake: AirbrakeNotifier
 ) extends Logging {

  def allKeeps(before: Option[ExternalId[Keep]], after: Option[ExternalId[Keep]], collectionId: Option[ExternalId[Collection]], count: Int, userId: Id[User]): Future[(Option[BasicCollection], Seq[FullKeepInfo])] = {
    val (keeps, collectionOpt, clickCounts) = db.readOnly { implicit s =>
      val collectionOpt = (collectionId map { id => collectionRepo.getByUserAndExternalId(userId, id)}).flatten
      val keeps = collectionOpt match {
        case Some(collection) =>
          keepRepo.getByUserAndCollection(userId, collection.id.get, before, after, count)
        case None =>
          keepRepo.getByUser(userId, before, after, count)
      }
      val clickCounts = keepClicksRepo.getClickCountsByKeepIds(userId, keeps.map(_.id.get).toSet)
      (keeps, collectionOpt, clickCounts)
    }
    val infosFuture = searchClient.sharingUserInfo(userId, keeps.map(_.uriId))

    val keepsWithCollIds = db.readOnly { implicit s =>
      val collIdToExternalId = collectionRepo.getByUser(userId).map(c => c.id.get -> c.externalId).toMap
      keeps.map{ keep =>
        val collIds = keepToCollectionRepo.getCollectionsForKeep(keep.id.get).flatMap(collIdToExternalId.get).toSet
        (keep, collIds)
      }
    }

    infosFuture.map { infos =>
      val idToBasicUser = db.readOnly { implicit s =>
        basicUserRepo.loadAll(infos.flatMap(_.sharingUserIds).toSet)
      }
      val keepsInfo = (keepsWithCollIds zip infos).map { case ((keep, collIds), info) =>
        val others = info.keepersEdgeSetSize - info.sharingUserIds.size - (if (keep.isPrivate) 0 else 1)
        FullKeepInfo(keep, info.sharingUserIds map idToBasicUser, collIds, others, clickCounts.getOrElse(keep.id.get, 0))
      }
      (collectionOpt.map(BasicCollection fromCollection _), keepsInfo)
    }
  }

  def keepOne(keepJson: JsObject, userId: Id[User], installationId: Option[ExternalId[KifiInstallation]], source: KeepSource)(implicit context: HeimdalContext): KeepInfo = {
    log.info(s"[keep] $keepJson")
    val rawBookmark = rawBookmarkFactory.toRawBookmark(keepJson)
    keepInterner.internRawBookmark(rawBookmark, userId, source, mutatePrivacy = true, installationId) match {
      case Failure(e) =>
        throw e
      case Success(keep) =>
        SafeFuture{
          searchClient.updateURIGraph()
          notifyWhoKeptMyKeeps(userId, Seq(keep))
        }
        KeepInfo.fromBookmark(keep)
    }
  }

  def keepMultiple(keepInfosWithCollection: KeepInfosWithCollection, userId: Id[User], source: KeepSource)(implicit context: HeimdalContext):
      (Seq[KeepInfo], Option[Int]) = {
    val KeepInfosWithCollection(collection, keepInfos) = keepInfosWithCollection
    val (keeps, _) = keepInterner.internRawBookmarks(rawBookmarkFactory.toRawBookmark(keepInfos), userId, source, mutatePrivacy = true)
    log.info(s"[keepMulti] keeps(len=${keeps.length}):${keeps.mkString(",")}")
    val addedToCollection = collection flatMap {
      case Left(collectionId) => db.readOnly { implicit s => collectionRepo.getOpt(collectionId) }
      case Right(name) => Some(getOrCreateTag(userId, name))
    } map { coll =>
      addToCollection(coll.id.get, keeps).size
    }
    SafeFuture{
      searchClient.updateURIGraph()
      notifyWhoKeptMyKeeps(userId, keeps)
    }
    (keeps.map(KeepInfo.fromBookmark), addedToCollection)
  }

  /**
   * This is a very experimental test, needs to be tested and verified with product as there are few concepts here that are
   * not part of the "regular" flow of kifi, like having unconnected people know about each public keeps etc.
   * To be explicit, this is an internal only experiment and likely to be removed soon.
   */
  private def notifyWhoKeptMyKeeps(userId: Id[User], keeps: Seq[Keep]): Unit = keeps.filterNot(_.isPrivate).filter(_.id.isDefined) foreach { keep =>
    notifyWhoKeptMyKeep(userId, keep.id.get)
  }

  private def notifyWhoKeptMyKeep(userId: Id[User], keepId: Id[Keep]): Unit = {
    import scala.concurrent.duration._
    // Give the user 5 minutes to change the keep privacy settings or un-keep it and let the scraper and porn detector do their thing
    scheduler.scheduleOnce(5 minutes) {
      db.readOnly { implicit s =>
        try {
          val keep = keepRepo.get(keepId)
          //deal only with good standing, fully public keeps and uris
          if (keep.isPrivate || keep.state != KeepStates.ACTIVE) return
          val uri = uriRepo.get(keep.uriId)
          if (uri.restriction.isDefined || uri.state != NormalizedURIStates.SCRAPED || uri.title.isEmpty || uri.title.get.isEmpty) return
          val countPublicActiveByUri = keepRepo.countPublicActiveByUri(uri.id.get)
          //don't mess with keeps that are even a bit popular, has more then four keepers (with the latest keeper). we don't want to create noise, be extra careful
          val maxKeepers = 5
          if (countPublicActiveByUri <= 1 || countPublicActiveByUri > maxKeepers) return
          val otherKeeps = keepRepo.getByUri(keep.uriId).filterNot(_.isPrivate).filter(_.state == KeepStates.ACTIVE).filterNot(_.userId == userId)
          if (otherKeeps.length > (maxKeepers - 1)) return // how did that happen???
          val keeper = userRepo.get(keep.userId)
          val otherKeepers = otherKeeps.map(_.userId).toSet.filter { id =>
            localUserExperimentCommander.userHasExperiment(id, ExperimentType.WHO_KEPT_MY_KEEP)
          }
          val title = s"${keeper.fullName} also kept your keep"
          log.info(s"""[WKMK] "$title" for $keeper to $otherKeepers""")
          val userImageSize = Some(53)
          val picName = keeper.pictureName.getOrElse("0")
          imageStore.getPictureUrl(userImageSize, keeper, picName) map { userImage =>
            log.info(s"""[WKMK] $keeper using image $userImage""")
            val pageImageSize = 42
            val pageImage: String = imageRepo.getByUriWithSize(uri.id.get, ImageSize(pageImageSize, pageImageSize)).headOption.map(_.url).flatten.getOrElse("")
            val bodyHtml = s"""<img src="$pageImage" style="float:left" width="$pageImageSize" height="$pageImageSize"/><b>${keeper.fullName}</b> also kept "${uri.title.getOrElse(uri.url)}"."""
            val category = NotificationCategory.User.WHO_KEPT_MY_KEEP
            val title = uri.title.get.abbreviate(80)
            eliza.sendGlobalNotification(otherKeepers, title, bodyHtml, title, uri.url, userImage, sticky = false, category) onComplete {
              case Success(id) => log.info(s"""[WKMK] sent [$id] "$title" or $keeper to $otherKeepers""")
              case Failure(ex) => log.error(s"""[WKMK] Error sending "$title" for $keeper to $otherKeepers""", ex)
            }
          } onFailure {
            case ex: Throwable => log.error(s"""[WKMK] Error sending "$title" for $keeper with picName $picName to $otherKeepers""", ex)
          }
        } catch {
          case e: Exception => airbrake.notify(s"[WKMK] Error sending for user $userId and keep $keepId", e)
        }
      }
    }
  }

  def unkeepMultiple(keepInfos: Seq[KeepInfo], userId: Id[User])(implicit context: HeimdalContext): Seq[KeepInfo] = {
    val deactivatedBookmarks = db.readWrite { implicit s =>
      val bms = keepInfos.map { ki =>
        uriRepo.getByUri(ki.url).flatMap { uri =>
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
      collIds.foreach{ cid => collectionRepo.collectionChanged(cid) }
      bms
    }
    log.info(s"[unkeepMulti] deactivatedKeeps:(len=${deactivatedBookmarks.length}):${deactivatedBookmarks.mkString(",")}")

    val deactivatedKeepInfos = deactivatedBookmarks map KeepInfo.fromBookmark
    keptAnalytics.unkeptPages(userId, deactivatedBookmarks, context)
    searchClient.updateURIGraph()
    deactivatedKeepInfos
  }

  def unkeepBatch(ids: Seq[ExternalId[Keep]], userId: Id[User])(implicit context: HeimdalContext): Seq[(ExternalId[Keep], Option[KeepInfo])] = {
    ids map { id =>
      id -> unkeep(id, userId)
    } // todo: optimize
  }

  def unkeep(extId: ExternalId[Keep], userId: Id[User])(implicit context: HeimdalContext): Option[KeepInfo] = {
    db.readWrite { implicit s =>
      keepRepo.getByExtIdAndUser(extId, userId) map { keep =>
        val saved = keepRepo.save(keep withActive false)
        log.info(s"[unkeep($userId)] deactivated keep=$saved")
        keepToCollectionRepo.getCollectionsForKeep(saved.id.get) foreach { cid => collectionRepo.collectionChanged(cid) }
        saved
      }
    } map { saved =>
      // TODO: broadcast over any open user channels
      keptAnalytics.unkeptPages(userId, Seq(saved), context)
      searchClient.updateURIGraph()
      KeepInfo.fromBookmark(saved)
    }
  }

  def updateKeep(keep: Keep, isPrivate: Option[Boolean], title: Option[String])(implicit context: HeimdalContext): Option[Keep] = {
    val shouldBeUpdated = (isPrivate.isDefined && keep.isPrivate != isPrivate.get) || (title.isDefined && keep.title != title)
    if (shouldBeUpdated) Some {
      val updatedPrivacy = isPrivate getOrElse keep.isPrivate
      val updatedTitle = title orElse keep.title
      val updatedKeep = db.readWrite { implicit s => keepRepo.save(keep.withPrivate(updatedPrivacy).withTitle(updatedTitle)) }
      searchClient.updateURIGraph()
      keptAnalytics.updatedKeep(keep, updatedKeep, context)
      updatedKeep
    } else None
  }

  def addToCollection(collectionId: Id[Collection], keeps: Seq[Keep])(implicit context: HeimdalContext): Set[KeepToCollection] = timing(s"addToCollection($collectionId,${keeps.length})") {
    db.readWrite { implicit s =>
      val keepsById = keeps.map(keep => keep.id.get -> keep).toMap
      val collection = collectionRepo.get(collectionId)
      val existing = keepToCollectionRepo.getByCollection(collectionId, excludeState = None).toSet
      val newKeepIds = keepsById.keySet -- existing.map(_.keepId)
      val newK2C  = newKeepIds map { kId => KeepToCollection(keepId = kId, collectionId = collectionId) }
      timing(s"addToCollection($collectionId,${keeps.length}) -- keepToCollection.insertAll", 100) {
        keepToCollectionRepo.insertAll(newK2C.toSeq)
      }
      val activated = existing collect {
        case ktc if ktc.state == KeepToCollectionStates.INACTIVE && keepsById.contains(ktc.keepId) =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
      }

      timing(s"addToCollection($collectionId,${keeps.length}) -- collection.modelChanged", 100) {
        collectionRepo.modelChanged(collection, (newK2C.size + activated.size) > 0)
      }
      val tagged = (activated ++ newK2C).toSet
      val taggingAt = currentDateTime
      tagged.foreach(ktc => keptAnalytics.taggedPage(collection, keepsById(ktc.keepId), context, taggingAt))
      tagged
    } tap { _ => searchClient.updateURIGraph() }
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
    val collection = db.readOnly { implicit s =>
      collectionRepo.getByUserAndName(userId, normalizedName, excludeState = None)
    }
    collection match {
      case Some(t) if t.isActive => t
      case Some(t) => db.readWrite { implicit s => collectionRepo.save(t.copy(state = CollectionStates.ACTIVE)) } tap(keptAnalytics.createdTag(_, context))
      case None => db.readWrite { implicit s => collectionRepo.save(Collection(userId = userId, name = normalizedName)) } tap(keptAnalytics.createdTag(_, context))
    }
  }

  def removeTag(id: ExternalId[Collection], url: String, userId: Id[User])(implicit context: HeimdalContext): Unit = {
    db.readWrite { implicit s =>
      for {
        uri <- uriRepo.getByUri(url)
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
        uri <- uriRepo.getByUri(url).toSeq
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
    db.readOnly { implicit s =>
      for {
        uri <- uriRepo.getByUri(url).toSeq
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


    val keepsByTagName = keepsWithTags.flatMap { case (keepInfo, tags) =>
      tags.map(tagName => (tagName, keepsByUrl(keepInfo.url)))
    }.groupBy(_._1).mapValues(_.map(_._2))

    val keepsByTag = keepsByTagName.map { case (tagName, keeps) =>
      val tag = getOrCreateTag(userId, tagName)
      addToCollection(tag.id.get, keeps)
      tag -> keeps
    }.toMap

    keepsByTag
  }

  def setFirstKeeps(userId: Id[User], keeps: Seq[Keep]): Unit = {
    db.readWrite { implicit session =>
      val origin = keepRepo.oldestKeep(userId).map(_.createdAt) getOrElse currentDateTime
      keeps.zipWithIndex.foreach { case (keep, i) =>
        keepRepo.save(keep.copy(createdAt = origin.minusSeconds(i + 1)))
      }
    }
  }

  def processKifiHit(clicker:Id[User], kifiHit:SanitizedKifiHit):Unit = {
    db.readWrite { implicit rw =>
      val keepers = kifiHit.context.keepers
      if (keepers.isEmpty) userBookmarkClicksRepo.increaseCounts(clicker, kifiHit.uriId, true)
      else {
        kifiHitCache.get(KifiHitKey(clicker, kifiHit.uriId)) match { // simple throttling
          case Some(hit) =>
            log.warn(s"[kifiHit($clicker,${kifiHit.uriId})] already recorded kifiHit ($hit) for user within threshold -- skip")
          case None =>
            kifiHitCache.set(KifiHitKey(clicker, kifiHit.uriId), kifiHit)
            keepers.foreach { extId =>
              val keeperId: Id[User] = userRepo.get(extId).id.get
              userBookmarkClicksRepo.increaseCounts(keeperId, kifiHit.uriId, false)
              keepRepo.getByUriAndUser(kifiHit.uriId, keeperId) match {
                case None =>
                  log.warn(s"[kifiHit($clicker,${kifiHit.uriId},${keepers.mkString(",")})] keep not found for keeperId=$keeperId")
                  // move on
                case Some(keep) =>
                  val saved = keepClicksRepo.save(KeepClick(hitUUID = kifiHit.uuid, numKeepers = keepers.length, keeperId = keeperId, keepId = keep.id.get, uriId = keep.uriId, origin = Some(kifiHit.origin)))
                  log.info(s"[kifiHit($clicker, ${kifiHit.uriId}, ${keepers.mkString(",")})] saved $saved")
              }
            }
        }
      }
    }
  }

}
