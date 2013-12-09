package com.keepit.commanders

import com.google.inject.Inject

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

import com.keepit.commanders.KeepInfosWithCollection._
import com.keepit.commanders.KeepInfo._
import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URI
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer.NormalizationService
import com.keepit.social.BasicUser
import com.keepit.common.logging.Logging
import com.keepit.common.akka.SafeFuture
import com.keepit.search.SearchServiceClient
import com.keepit.heimdal._
import com.keepit.common.social.BasicUserRepo

import play.api.Play.current
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration._

case class KeepInfo(id: Option[ExternalId[Bookmark]], title: Option[String], url: String, isPrivate: Boolean)

case class FullKeepInfo(bookmark: Bookmark, users: Set[BasicUser], collections: Set[ExternalId[Collection]], others: Int)

class FullKeepInfoWriter extends Writes[FullKeepInfo] {
  def writes(info: FullKeepInfo) = Json.obj(
    "id" -> info.bookmark.externalId.id,
    "title" -> info.bookmark.title,
    "url" -> info.bookmark.url,
    "isPrivate" -> info.bookmark.isPrivate,
    "createdAt" -> info.bookmark.createdAt,
    "others" -> info.others,
    "keepers" -> info.users,
    "collections" -> info.collections.map(_.id)
  )
}

object KeepInfo {
  implicit val format = (
    (__ \ 'id).formatNullable(ExternalId.format[Bookmark]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'isPrivate).formatNullable[Boolean].inmap[Boolean](_ getOrElse false, Some(_))
  )(KeepInfo.apply _, unlift(KeepInfo.unapply))

  def fromBookmark(bookmark: Bookmark): KeepInfo = {
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

class BookmarksCommander @Inject() (
    db: Database,
    bookmarkInterner: BookmarkInterner,
    heimdal: HeimdalServiceClient,
    searchClient: SearchServiceClient,
    keepToCollectionRepo: KeepToCollectionRepo,
    basicUserRepo: BasicUserRepo,
    uriRepo: NormalizedURIRepo,
    bookmarkRepo: BookmarkRepo,
    collectionRepo: CollectionRepo
 ) extends Logging {

  def allKeeps(before: Option[ExternalId[Bookmark]], after: Option[ExternalId[Bookmark]], collectionId: Option[ExternalId[Collection]], count: Int, userId: Id[User]): Future[(Option[BasicCollection], Seq[FullKeepInfo])] = {
    val (keeps, collectionOpt) = db.readOnly { implicit s =>
      val collectionOpt = (collectionId map { id => collectionRepo.getByUserAndExternalId(userId, id)}).flatten
      val keeps = bookmarkRepo.getByUser(userId, before, after, collectionOpt map (_.id.get), count)
      (keeps, collectionOpt)
    }
    searchClient.sharingUserInfo(userId, keeps.map(_.uriId)) map { infos =>
      log.info(s"got sharingUserInfo: $infos")
      db.readOnly { implicit s =>
        val idToBasicUser = infos.flatMap(_.sharingUserIds).distinct.map(id => id -> basicUserRepo.load(id)).toMap
        val collIdToExternalId = collectionRepo.getByUser(userId).map(c => c.id.get -> c.externalId).toMap
        (keeps zip infos).map { case (keep, info) =>
          val collIds =
            keepToCollectionRepo.getCollectionsForBookmark(keep.id.get).flatMap(collIdToExternalId.get).toSet
          val others = info.keepersEdgeSetSize - info.sharingUserIds.size - (if (keep.isPrivate) 0 else 1)
          FullKeepInfo(keep, info.sharingUserIds map idToBasicUser, collIds, others)
        }
      }
    } map { keepsInfo =>
      (collectionOpt.map(BasicCollection fromCollection _), keepsInfo)
    }
  }

  def keepMultiple(keepInfosWithCollection: KeepInfosWithCollection, user: User, experiments: Set[ExperimentType], contextBuilder: HeimdalContextBuilder, source: BookmarkSource):
                  (Seq[KeepInfo], Option[Int]) = {
    val tStart = currentDateTime
    val KeepInfosWithCollection(collection, keepInfos) = keepInfosWithCollection
    val keeps = bookmarkInterner.internBookmarks(Json.toJson(keepInfos), user, experiments, source).map(KeepInfo.fromBookmark)

    //Analytics
    SafeFuture{
      keeps.foreach { bookmark =>
        contextBuilder += ("source", source.value)
        contextBuilder += ("isPrivate", bookmark.isPrivate)
        contextBuilder += ("url", bookmark.url)
        contextBuilder += ("hasTitle", bookmark.title.isDefined)

        heimdal.trackEvent(UserEvent(user.id.get.id, contextBuilder.build, UserEventTypes.KEEP, tStart))
      }
      val kept = keeps.length
      val keptPrivate = keeps.count(_.isPrivate)
      val keptPublic = kept - keptPrivate
      heimdal.incrementUserProperties(user.id.get, "keeps" -> kept, "privateKeeps" -> keptPrivate, "publicKeeps" -> keptPublic)
    }

    val addedToCollection = collection flatMap {
      case Left(collectionId) => db.readOnly { implicit s => collectionRepo.getOpt(collectionId) }
      case Right(name) => db.readWrite { implicit s =>
        Some(collectionRepo.getByUserAndName(user.id.get, name, excludeState = None) match {
          case Some(c) if c.isActive => c
          case Some(c) => collectionRepo.save(c.copy(state = CollectionStates.ACTIVE))
          case None => collectionRepo.save(Collection(userId = user.id.get, name = name))
        })
      }
    } map { coll =>
      addToCollection(keeps.map(_.id.get).toSet, coll)._1.size
    }
    SafeFuture{
      searchClient.updateURIGraph()
    }
    (keeps, addedToCollection)
  }

  def unkeepMultiple(keepInfos: Seq[KeepInfo], userId: Id[User]): Seq[KeepInfo] = {
    val deactivatedKeepInfos = db.readWrite { implicit s =>
      keepInfos.map { ki =>
        val url = ki.url
        db.readWrite { implicit s =>
          uriRepo.getByUri(url).flatMap { uri =>
            bookmarkRepo.getByUriAndUser(uri.id.get, userId).map { b =>
              bookmarkRepo.save(b withActive false)
            }
          }
        }
      }
    }.flatten.map(KeepInfo.fromBookmark(_))
    SafeFuture {
      val unkept = keepInfos.length
      val unkeptPrivate = keepInfos.count(_.isPrivate)
      val unkeptPublic = unkept - unkeptPrivate
      heimdal.incrementUserProperties(userId, "keeps" -> - unkept, "privateKeeps" -> - unkeptPrivate, "publicKeeps" -> - unkeptPublic)
    }
    searchClient.updateURIGraph()
    deactivatedKeepInfos
  }

  private def addToCollection(keepExtIds: Set[ExternalId[Bookmark]], collection: Collection,
      removeOthers: Boolean = false): (Set[KeepToCollection], Set[KeepToCollection]) = {
    db.readWrite(attempts = 2) { implicit s =>
      val keepIds = keepExtIds.flatMap(bookmarkRepo.getOpt(_).map(_.id.get))
      val existing = keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None).toSet
      val created = (keepIds -- existing.map(_.bookmarkId)) map { bid =>
        keepToCollectionRepo.save(KeepToCollection(bookmarkId = bid, collectionId = collection.id.get))
      }
      val activated = existing collect {
        case ktc if ktc.state == KeepToCollectionStates.INACTIVE && keepIds.contains(ktc.bookmarkId) =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
      }
      val removed = removeOthers match {
        case true => existing.collect {
          case ktc if ktc.state == KeepToCollectionStates.ACTIVE && !keepIds.contains(ktc.bookmarkId) =>
            keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
        }
        case false => Seq()
      }
      ((activated ++ created).toSet, removed.toSet)
    }
  }

  def addTagToUrl(user: User, experiments: Set[ExperimentType],
      url: String, tagId: Id[Collection])(implicit s: RWSession): KeepToCollection = {
    log.debug(s"adding tag $tagId to url $url")
    val uriOpt = uriRepo.getByUri(url)
    val bookmarkIdOpt: Option[Id[Bookmark]] = uriOpt map { uri =>
      log.debug(s"found uri $uri for url $url")
      val bookmarkOpt = bookmarkRepo.getByUriAndUser(uri.id.get, user.id.get)
      bookmarkOpt map { b =>
        log.debug(s"found bookmark $b for uri ${uri.id.get}")
        b.id.get
      }
    } flatten

    val bookmarkId = bookmarkIdOpt getOrElse {
      log.debug(s"did not found bookmark, creating a new one for url $url")
      val newBookmark = bookmarkInterner.internBookmarks(
        Json.obj("url" -> url), user, experiments, BookmarkSource.hover
      ).head
      log.debug(s"created new bookmark $newBookmark for url $url")
      newBookmark.id.get
    }
    keepToCollectionRepo.getOpt(bookmarkId, tagId) match {
      case Some(ktc) if ktc.state == KeepToCollectionStates.ACTIVE =>
        ktc
      case Some(ktc) =>
        keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
      case None =>
        keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmarkId, collectionId = tagId))
    }
  }

  def getOrCreateTag(userId: Id[User], name: String): Collection = {
    val normalizedName = name.trim.replaceAll("""\s+""", " ").take(Collection.MaxNameLength)
    val collection = db.readOnly { implicit s =>
      collectionRepo.getByUserAndName(userId, normalizedName, excludeState = None)
    }
    collection match {
      case Some(t) if t.isActive => t
      case Some(t) => db.readWrite { implicit s => collectionRepo.save(t.copy(state = CollectionStates.ACTIVE)) }
      case None => db.readWrite { implicit s => collectionRepo.save(Collection(userId = userId, name = normalizedName)) }
    }
  }

  def removeTag(id: ExternalId[Collection], url: String, userId: Id[User]): Unit = {
    db.readWrite { implicit s =>
      for {
        uri <- uriRepo.getByUri(url)
        bookmark <- bookmarkRepo.getByUriAndUser(uri.id.get, userId)
        collection <- collectionRepo.getOpt(id)
      } {
        keepToCollectionRepo.remove(bookmarkId = bookmark.id.get, collectionId = collection.id.get)
      }
    }
  }

}
