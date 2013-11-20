package com.keepit.commanders

import com.google.inject.Inject

import play.api.libs.concurrent.Execution.Implicits.defaultContext
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
import com.keepit.controllers.core.BookmarkInterner
import com.keepit.social.BasicUser
import com.keepit.common.logging.Logging
import com.keepit.common.akka.SafeFuture
import com.keepit.search.SearchServiceClient
import com.keepit.heimdal._

import play.api.Play.current
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration._

case class KeepInfo(id: Option[ExternalId[Bookmark]], title: Option[String], url: String, isPrivate: Boolean)

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
    uriRepo: NormalizedURIRepo,
    bookmarkRepo: BookmarkRepo,
    collectionRepo: CollectionRepo
 ) extends Logging {

  def keepMultiple(keepInfosWithCollection: KeepInfosWithCollection, user: User, experiments: Set[ExperimentType], contextBuilder: EventContextBuilder, source: String):
                  (List[KeepInfo], Option[Int]) = {
    val tStart = currentDateTime
    val KeepInfosWithCollection(collection, keepInfos) = keepInfosWithCollection
    val keeps = bookmarkInterner.internBookmarks(Json.toJson(keepInfos), user, experiments, source).map(KeepInfo.fromBookmark)

    //Analytics
    SafeFuture{ keeps.foreach { bookmark =>
      contextBuilder += ("isPrivate", bookmark.isPrivate)
      contextBuilder += ("url", bookmark.url)
      contextBuilder += ("hasTitle", bookmark.title.isDefined)

      heimdal.trackEvent(UserEvent(user.id.get.id, contextBuilder.build, EventType("keep"), tStart))
    }}

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
}
