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
import com.keepit.common.social.{BasicUserRepo}
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
    basicUserRepo: BasicUserRepo,
    uriRepo: NormalizedURIRepo,
    actionAuthenticator: ActionAuthenticator,
    s3ScreenshotStore: S3ScreenshotStore,
    collectionCommander: CollectionCommander,
    bookmarksCommander: BookmarksCommander,
    searchClient: SearchServiceClient,
    heimdalContextBuilder: HeimdalContextBuilderFactory
  )
  extends WebsiteController(actionAuthenticator) {

  implicit val writesKeepInfo = new Writes[(Bookmark, Set[BasicUser], Set[ExternalId[Collection]], Int)] {
    def writes(info: (Bookmark, Set[BasicUser], Set[ExternalId[Collection]], Int)) = Json.obj(
      "id" -> info._1.externalId.id,
      "title" -> info._1.title,
      "url" -> info._1.url,
      "isPrivate" -> info._1.isPrivate,
      "createdAt" -> info._1.createdAt,
      "others" -> info._4,
      "keepers" -> info._2,
      "collections" -> info._3.map(_.id)
    )
  }

  private def getBookmarkExternalId(id: String): Option[ExternalId[Bookmark]] = {
    db.readOnly { implicit s => ExternalId.asOpt[Bookmark](id).flatMap(bookmarkRepo.getOpt) } map (_.externalId)
  }

  private def getCollectionByExternalId(userId: Id[User], id: String): Option[Collection] = {
    db.readOnly { implicit s =>
      ExternalId.asOpt[Collection](id).flatMap(collectionRepo.getByUserAndExternalId(userId, _))
    }
  }

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
        val contextBuilder = heimdalContextBuilder()
        contextBuilder.addRequestInfo(request)
        val source = "SITE"
        contextBuilder += ("source", source)
        val (keeps, addedToCollection) = bookmarksCommander.keepMultiple(fromJson, request.user, request.experiments, contextBuilder, source)
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
    db.readOnly { implicit s => bookmarkRepo.getOpt(id) } map { b =>
      db.readWrite { implicit s => bookmarkRepo.save(b withActive false) }
      searchClient.updateURIGraph()
      Ok(Json.obj())
    } getOrElse {
      NotFound(Json.obj("error" -> "Keep not found"))
    }
  }

  def allKeeps(before: Option[String], after: Option[String], collection: Option[String], count: Int) =
      AuthenticatedJsonAction { request =>
    (before map getBookmarkExternalId, after map getBookmarkExternalId,
        collection map { getCollectionByExternalId(request.userId, _) }) match {
      case (Some(None), _, _) => BadRequest(Json.obj("error" -> s"Invalid id for before: ${before.get}"))
      case (_, Some(None), _) => BadRequest(Json.obj("error" -> s"Invalid id for after: ${after.get}"))
      case (_, _, Some(None)) => BadRequest(Json.obj("error" -> s"Invalid id for collection: ${collection.get}"))
      case (beforeId, afterId, coll) => Async {
        val keeps = db.readOnly { implicit s =>
          bookmarkRepo.getByUser(request.userId, beforeId.flatten, afterId.flatten, coll.flatten.map(_.id.get), count)
        }
        searchClient.sharingUserInfo(request.userId, keeps.map(_.uriId)) map { infos =>
          db.readOnly { implicit s =>
            val idToBasicUser = infos.flatMap(_.sharingUserIds).distinct.map(id => id -> basicUserRepo.load(id)).toMap
            val collIdToExternalId = collectionRepo.getByUser(request.userId).map(c => c.id.get -> c.externalId).toMap
            (keeps zip infos).map { case (keep, info) =>
              val collIds =
                keepToCollectionRepo.getCollectionsForBookmark(keep.id.get).flatMap(collIdToExternalId.get).toSet
              val others = info.keepersEdgeSetSize - info.sharingUserIds.size - (if (keep.isPrivate) 0 else 1)
              (keep, info.sharingUserIds map idToBasicUser, collIds, others)
            }
          }
        } map { keepsInfo =>
          Ok(Json.obj(
            "collection" -> coll.flatten.map(BasicCollection fromCollection _),
            "before" -> before,
            "after" -> after,
            "keeps" -> keepsInfo
          ))
        }
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
    collectionCommander.saveCollection(id, request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

  def deleteCollection(id: ExternalId[Collection]) = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { coll =>
      db.readWrite { implicit s =>
        collectionRepo.save(coll.copy(state = CollectionStates.INACTIVE))
        collectionCommander.updateCollectionOrdering(request.userId)
      }
      searchClient.updateURIGraph()
      Ok(Json.obj())
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
