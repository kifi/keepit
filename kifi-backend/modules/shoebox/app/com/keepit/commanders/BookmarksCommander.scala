package com.keepit.commanders

import com.google.inject.Inject

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
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import com.keepit.common.logging.Logging

import play.api.Play.current
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration._

class BookmarksCommander @Inject() (
    db: Database,
 ) extends Logging {

  def keepMultiple(keepsWithCollections: Seq[KeepInfosWithCollection]): (List[Bookmark], (Set[KeepToCollection], Set[KeepToCollection])) = {
    val tStart = currentDateTime
    keepsWithCollections map { kwc =>
      val KeepInfosWithCollection(collection, keepInfos) = kwc
      val keeps = bookmarkInterner.internBookmarks(
        Json.toJson(keepInfos), request.user, request.experiments, "SITE").map(KeepInfo.fromBookmark)

      //Analytics
      SafeFuture{ keeps.foreach { bookmark =>
        val contextBuilder = userEventContextBuilder(Some(request))

        contextBuilder += ("isPrivate", bookmark.isPrivate)
        contextBuilder += ("url", bookmark.url)
        contextBuilder += ("source", "SITE")
        contextBuilder += ("hasTitle", bookmark.title.isDefined)

        heimdal.trackEvent(UserEvent(request.userId.id, contextBuilder.build, EventType("keep"), tStart))
      }}

      val addedToCollection = collection flatMap {
        case Left(collectionId) => db.readOnly { implicit s => collectionRepo.getOpt(collectionId) }
        case Right(name) => db.readWrite { implicit s =>
          Some(collectionRepo.getByUserAndName(request.userId, name, excludeState = None) match {
            case Some(c) if c.isActive => c
            case Some(c) => collectionRepo.save(c.copy(state = CollectionStates.ACTIVE))
            case None => collectionRepo.save(Collection(userId = request.userId, name = name))
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
  }

  def unkeepMultiple() = AuthenticatedJsonAction { request =>
    request.body.asJson.flatMap(Json.fromJson[Seq[KeepInfo]](_).asOpt) map { keepInfos =>
      val deactivatedKeepInfos = db.readWrite { implicit s =>
        keepInfos.map { ki =>
          val url = ki.url
          db.readWrite { implicit s =>
            uriRepo.getByUri(url).flatMap { uri =>
              bookmarkRepo.getByUriAndUser(uri.id.get, request.userId).map { b =>
                bookmarkRepo.save(b withActive false)
              }
            }
          }
        }
      }.flatten.map(KeepInfo.fromBookmark(_))
      searchClient.updateURIGraph()
      Ok(Json.obj(
        "removedKeeps" -> deactivatedKeepInfos
      ))
    } getOrElse {
      BadRequest(Json.obj("error" -> "Could not parse JSON array of keep with url from request body"))
    }
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
