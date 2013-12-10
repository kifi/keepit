package com.keepit.controllers.website

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject

import com.keepit.heimdal._
import com.keepit.commanders._
import com.keepit.commanders.KeepInfosWithCollection._
import com.keepit.commanders.KeepInfo._
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.akka.SafeFuture
import com.keepit.search.SearchServiceClient

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.store.S3ScreenshotStore
import play.api.mvc.Action
import com.keepit.social.BasicUser
import scala.Some
import com.keepit.model.KeepToCollection
import play.api.libs.json.JsObject

class BookmarksController @Inject() (
    db: Database,
    userRepo: UserRepo,
    bookmarkRepo: BookmarkRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    uriRepo: NormalizedURIRepo,
    actionAuthenticator: ActionAuthenticator,
    s3ScreenshotStore: S3ScreenshotStore,
    collectionCommander: CollectionCommander,
    bookmarksCommander: BookmarksCommander,
    val searchClient: SearchServiceClient,
    heimdalContextBuilder: HeimdalContextBuilderFactory
  )
  extends WebsiteController(actionAuthenticator) {

  implicit val writesKeepInfo = new FullKeepInfoWriter()

  def updateCollectionOrdering() = AuthenticatedAction(parse.tolerantJson) { request =>
    implicit val externalIdFormat = ExternalId.format[Collection]
    val orderedIds = request.body.as[Seq[ExternalId[Collection]]]
    val newCollectionIds = db.readWrite { implicit s => collectionCommander.setCollectionOrdering(request.userId, orderedIds) }
    Ok(Json.obj(
      "collectionIds" -> newCollectionIds.map{ id => Json.toJson(id) }
    ))
  }

  def getScreenshotUrl() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    Async {
      db.readOnlyAsync { implicit session =>
        uriRepo.getByUri(url)
      } map { uri =>
        s3ScreenshotStore.getScreenshotUrl(uri) match {
          case Some(url) => Ok(Json.obj("url" -> url))
          case None => NotFound
        }
      }
    }
  }

  def keepMultiple() = AuthenticatedJsonAction { request =>
    try {
      request.body.asJson.flatMap(Json.fromJson[KeepInfosWithCollection](_).asOpt) map { fromJson =>
        val source = BookmarkSource.site
        implicit val context = heimdalContextBuilder.withRequestInfo(request).build
        val (keeps, addedToCollection) = bookmarksCommander.keepMultiple(fromJson, request.user, request.experiments, source)
        log.info(s"kept ${keeps.size} new keeps")
        Ok(Json.obj(
          "keeps" -> keeps,
          "addedToCollection" -> addedToCollection
        ))
      } getOrElse {
        log.error(s"can't parse object from request ${request.body} for user ${request.user}")
        BadRequest(Json.obj("error" -> "Could not parse object from request body"))
      }
    } catch {
      case e: Throwable =>
      log.error(s"error keeping ${request.body}", e)
      throw e
    }
  }

  def unkeepMultiple() = AuthenticatedJsonAction { request =>
    request.body.asJson.flatMap(Json.fromJson[Seq[KeepInfo]](_).asOpt) map { keepInfos =>
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      val deactivatedKeepInfos = bookmarksCommander.unkeepMultiple(keepInfos, request.userId)
      Ok(Json.obj(
        "removedKeeps" -> deactivatedKeepInfos
      ))
    } getOrElse {
      BadRequest(Json.obj("error" -> "Could not parse JSON array of keep with url from request body"))
    }
  }

  def getKeepInfo(id: ExternalId[Bookmark]) = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s => bookmarkRepo.getOpt(id) } filter { _.isActive } map { b =>
      Ok(Json.toJson(KeepInfo.fromBookmark(b)))
    } getOrElse {
      NotFound(Json.obj("error" -> "Keep not found"))
    }
  }

  def updateKeepInfo(id: ExternalId[Bookmark]) = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s => bookmarkRepo.getOpt(id) } map { b =>
      request.body.asJson.flatMap { newJson =>
        val oldJson = Json.toJson(KeepInfo.fromBookmark(b))
        Json.fromJson[KeepInfo](oldJson.as[JsObject] deepMerge newJson.as[JsObject]).asOpt
      } map { keepInfo =>
        val newKeepInfo = KeepInfo.fromBookmark(db.readWrite { implicit s => bookmarkRepo.save(b.withTitle(keepInfo.title).withPrivate(keepInfo.isPrivate)) })
        searchClient.updateURIGraph()
        Ok(Json.obj(
          "keep" -> newKeepInfo
        ))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep info from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> "Keep not found"))
    }
  }

  def unkeep(id: ExternalId[Bookmark]) = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s => bookmarkRepo.getOpt(id) } map { bookmark =>
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      val deactivatedKeepInfo = bookmarksCommander.unkeepMultiple(Seq(KeepInfo.fromBookmark(bookmark)), request.userId).head
      Ok(Json.obj("removedKeep" -> deactivatedKeepInfo))
    } getOrElse {
      NotFound(Json.obj("error" -> "Keep not found"))
    }
  }

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], count: Int) = AuthenticatedJsonAction { request =>
    Async {
      bookmarksCommander.allKeeps(before map ExternalId[Bookmark], after map ExternalId[Bookmark], collectionOpt map ExternalId[Collection], count, request.userId) map { res =>
        Ok(Json.obj(
          "collection" -> res._1,
          "before" -> before,
          "after" -> after,
          "keeps" -> res._2
        ))
      }
    }
  }

  def allCollections(sort: String) = AuthenticatedJsonAction { request =>
    Async {
      for {
        numKeeps <- SafeFuture { db.readOnly { implicit s => bookmarkRepo.getCountByUser(request.userId) } }
        collections <- SafeFuture { collectionCommander.allCollections(sort, request.userId) }
      } yield {
        Ok(Json.obj(
          "keeps" -> numKeeps,
          "collections" -> collections
        ))
      }
    }
  }

  def saveCollection(id: String) = AuthenticatedJsonAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    collectionCommander.saveCollection(id, request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

  def deleteCollection(id: ExternalId[Collection]) = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      collectionCommander.deleteCollection(coll)
      Ok(Json.obj("deleted" -> coll.name))
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def removeKeepsFromCollection(id: ExternalId[Collection]) = AuthenticatedJsonAction { request =>
    implicit val externalIdFormat = ExternalId.format[Bookmark]
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Set[ExternalId[Bookmark]]](_).asOpt) map { keepExtIds =>
        val removed = db.readWrite { implicit s =>
          val keepIds = keepExtIds.flatMap(bookmarkRepo.getOpt(_).map(_.id.get))
          keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None) collect {
            case ktc if ktc.state != KeepToCollectionStates.INACTIVE && keepIds.contains(ktc.bookmarkId) =>
              keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
          }
        }
        searchClient.updateURIGraph()
        Ok(Json.obj("removed" -> removed.size))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep ids from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def keepToCollection(id: ExternalId[Collection], removeOthers: Boolean = false) =AuthenticatedJsonAction { request =>
    implicit val externalIdFormat = ExternalId.format[Bookmark]
    db.readOnly { implicit s =>
      collectionRepo.getByUserAndExternalId(request.userId, id)
    } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Set[ExternalId[Bookmark]]](_).asOpt) map { keepExtIds =>
        val (added, removed) = addToCollection(keepExtIds, collection, removeOthers)
        searchClient.updateURIGraph()
        Ok(Json.obj("added" -> added.size, "removed" -> removed.size))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep ids from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def numKeeps() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "numKeeps" -> db.readOnly { implicit s => bookmarkRepo.getCountByUser(request.userId) }
    ))
  }

  def mutualKeeps(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "mutualKeeps" -> db.readOnly { implicit s => bookmarkRepo.getNumMutual(request.userId, userRepo.get(id).id.get) }
    ))
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
