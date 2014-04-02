package com.keepit.commanders

import com.google.inject.Inject

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.common.logging.Logging
import com.keepit.common.akka.SafeFuture
import com.keepit.search.SearchServiceClient
import com.keepit.heimdal._
import com.keepit.common.social.BasicUserRepo

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.{net, KestrelCombinator}
import com.keepit.common.net.URISanitizer

case class KeepInfo(id: Option[ExternalId[Keep]] = None, title: Option[String], url: String, isPrivate: Boolean)

case class FullKeepInfo(bookmark: Keep, users: Set[BasicUser], collections: Set[ExternalId[Collection]], others: Int)

class FullKeepInfoWriter(sanitize: Boolean = false) extends Writes[FullKeepInfo] {
  def writes(info: FullKeepInfo) = Json.obj(
    "id" -> info.bookmark.externalId.id,
    "title" -> info.bookmark.title,
    "url" -> (if(sanitize) URISanitizer.sanitize(info.bookmark.url) else info.bookmark.url),
    "isPrivate" -> info.bookmark.isPrivate,
    "createdAt" -> info.bookmark.createdAt,
    "others" -> info.others,
    "keepers" -> info.users,
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
    keptAnalytics: KeepingAnalytics,
    rawBookmarkFactory: RawBookmarkFactory
 ) extends Logging {

  def allKeeps(before: Option[ExternalId[Keep]], after: Option[ExternalId[Keep]], collectionId: Option[ExternalId[Collection]], count: Int, userId: Id[User]): Future[(Option[BasicCollection], Seq[FullKeepInfo])] = {
    val (keeps, collectionOpt) = db.readOnly { implicit s =>
      val collectionOpt = (collectionId map { id => collectionRepo.getByUserAndExternalId(userId, id)}).flatten
      val keeps = collectionOpt match {
        case Some(collection) =>
          keepRepo.getByUserAndCollection(userId, collection.id.get, before, after, count)
        case None =>
          keepRepo.getByUser(userId, before, after, count)
      }
      (keeps, collectionOpt)
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
        FullKeepInfo(keep, info.sharingUserIds map idToBasicUser, collIds, others)
      }
      (collectionOpt.map(BasicCollection fromCollection _), keepsInfo)
    }
  }

  def keepMultiple(keepInfosWithCollection: KeepInfosWithCollection, userId: Id[User], source: KeepSource)(implicit context: HeimdalContext):
                  (Seq[KeepInfo], Option[Int]) = {
    val KeepInfosWithCollection(collection, keepInfos) = keepInfosWithCollection
    val (keeps, _) = keepInterner.internRawBookmarks(rawBookmarkFactory.toRawBookmark(keepInfos), userId, source, true)

    val addedToCollection = collection flatMap {
      case Left(collectionId) => db.readOnly { implicit s => collectionRepo.getOpt(collectionId) }
      case Right(name) => Some(getOrCreateTag(userId, name))
    } map { coll =>
      addToCollection(coll, keeps).size
    }
    SafeFuture{
      searchClient.updateURIGraph()
    }
    (keeps.map(KeepInfo.fromBookmark), addedToCollection)
  }

  def unkeepMultiple(keepInfos: Seq[KeepInfo], userId: Id[User])(implicit context: HeimdalContext): Seq[KeepInfo] = {

    val deactivatedBookmarks = db.readWrite { implicit s =>
      val bms = keepInfos.map { ki =>
        val url = ki.url
        uriRepo.getByUri(ki.url).flatMap { uri =>
          keepRepo.getByUriAndUser(uri.id.get, userId).map { b =>
            keepRepo.save(b withActive false)
          }
        }
      }.flatten
      val collIds = bms.flatMap(bm => keepToCollectionRepo.getCollectionsForKeep(bm.id.get)).toSet
      collIds.foreach{ cid => collectionRepo.collectionChanged(cid) }
      bms
    }

    val deactivatedKeepInfos = deactivatedBookmarks.map(KeepInfo.fromBookmark(_))
    keptAnalytics.unkeptPages(userId, deactivatedBookmarks, context)
    searchClient.updateURIGraph()
    deactivatedKeepInfos
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

  def addToCollection(collection: Collection, keeps: Seq[Keep])(implicit context: HeimdalContext): Set[KeepToCollection] = {
    db.readWrite(attempts = 2) { implicit s =>
      val keepsById = keeps.map(keep => keep.id.get -> keep).toMap
      val existing = keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None).toSet
      val created = (keepsById.keySet -- existing.map(_.keepId)) map { bid =>
        keepToCollectionRepo.save(KeepToCollection(keepId = bid, collectionId = collection.id.get))
      }
      val activated = existing collect {
        case ktc if ktc.state == KeepToCollectionStates.INACTIVE && keepsById.contains(ktc.keepId) =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
      }

      collectionRepo.collectionChanged(collection.id.get, (created.size + activated.size) > 0)

      val tagged = (activated ++ created).toSet
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
    val (bookmarks, _) = keepInterner.internRawBookmarks(rawBookmarkFactory.toRawBookmark(json), userId, source, mutatePrivacy = false, installationId = kifiInstallationId)
    addToCollection(tag, bookmarks)
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
      addToCollection(tag, keeps)
      tag -> keeps
    }.toMap

    keepsByTag
  }

  def setFirstKeeps(userId: Id[User], keeps: Seq[Keep]): Unit = {
    db.readWrite { implicit session =>
      val origin = keepRepo.oldestBookmark(userId).map(_.createdAt) getOrElse currentDateTime
      keeps.zipWithIndex.foreach { case (keep, i) =>
        keepRepo.save(keep.copy(createdAt = origin.minusSeconds(i + 1)))
      }
    }
  }
}
