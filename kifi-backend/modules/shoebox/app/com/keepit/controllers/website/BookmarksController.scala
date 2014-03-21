package com.keepit.controllers.website

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject

import com.keepit.heimdal._
import com.keepit.commanders._
import com.keepit.commanders.KeepInfosWithCollection._
import com.keepit.commanders.KeepInfo._
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, WebsiteController}
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.akka.SafeFuture
import com.keepit.search.SearchServiceClient

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.store.{S3ScreenshotStore}
import play.api.mvc.{SimpleResult, Action}
import com.keepit.social.BasicUser
import scala.util.Try
import com.keepit.model.KeepToCollection
import org.joda.time.Seconds
import scala.concurrent.{Await, Future}
import com.keepit.commanders.CollectionSaveFail
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsObject
import scala.concurrent.duration.Duration

class BookmarksController @Inject() (
    db: Database,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    collectionRepo: CollectionRepo,
    uriRepo: NormalizedURIRepo,
    pageInfoRepo: PageInfoRepo,
    imageInfoRepo: ImageInfoRepo,
    actionAuthenticator: ActionAuthenticator,
    s3ScreenshotStore: S3ScreenshotStore,
    collectionCommander: CollectionCommander,
    bookmarksCommander: BookmarksCommander,
    userValueRepo: UserValueRepo,
    clock: Clock,
    heimdalContextBuilder: HeimdalContextBuilderFactory
  )
  extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  implicit val writesKeepInfo = new FullKeepInfoWriter()

  def updateCollectionOrdering() = JsonAction.authenticatedParseJson { request =>
    implicit val externalIdFormat = ExternalId.format[Collection]
    val orderedIds = request.body.as[Seq[ExternalId[Collection]]]
    val newCollectionIds = db.readWrite { implicit s => collectionCommander.setCollectionOrdering(request.userId, orderedIds) }
    Ok(Json.obj(
      "collectionIds" -> newCollectionIds.map{ id => Json.toJson(id) }
    ))
  }

  def getScreenshotUrl() = JsonAction.authenticatedParseJsonAsync { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    val urlsOpt = (request.body \ "urls").asOpt[Seq[String]]
    urlOpt.map { url =>
      db.readOnlyAsync { implicit session =>
        uriRepo.getByUri(url)
      } map { uri =>
        s3ScreenshotStore.getScreenshotUrl(uri) match {
          case Some(url) => Ok(Json.obj("url" -> url))
          case None => NotFound(JsString("0"))
        }
      }
    }.orElse {
      urlsOpt.map { urls =>
        db.readOnlyAsync { implicit session =>
          urls.map( url => url -> uriRepo.getByUri(url) )
        } map { case uris =>
          val results = uris.map { case (uri, ssOpt) =>
            uri -> (s3ScreenshotStore.getScreenshotUrl(ssOpt).map(JsString).getOrElse(JsNull): JsValue)
          }
          Ok(Json.obj("urls" -> JsObject(results)))
        }
      }
    }.getOrElse(Future.successful(BadRequest(JsString("0"))))
  }

  // todo: add uriId, sizes, colors, etc.
  private def toJsObject(url: String, uri: NormalizedURI, pageInfoOpt: Option[PageInfo]): Future[JsObject] = {
    val screenshotUrlOpt = s3ScreenshotStore.getScreenshotUrl(uri)
    s3ScreenshotStore.asyncGetImageUrl(uri, pageInfoOpt) map { imgUrlOpt =>
      (screenshotUrlOpt, imgUrlOpt) match {
        case (None, None) =>
          Json.obj("url" -> url, "uriId" -> uri.id.get)
        case (None, Some(imgUrl)) =>
          Json.obj("url" -> url, "imgUrl" -> imgUrl)
        case (Some(ssUrl), None) =>
          Json.obj("url" -> url, "screenshotUrl" -> ssUrl)
        case (Some(ssUrl), Some(imgUrl)) =>
          Json.obj("url" -> url, "imgUrl" -> imgUrl, "screenshotUrl" -> ssUrl)
      }
    }
  }

  def getImageUrl() = JsonAction.authenticatedParseJsonAsync { request => // WIP; test-only
    val urlOpt = (request.body \ "url").asOpt[String]
    log.info(s"[getImageUrl] body=${request.body} url=${urlOpt}")
    urlOpt match {
      case None => Future.successful(BadRequest(Json.obj("code" -> "illegal_argument")))
      case Some(url) => {
        val (uriOpt, pageInfoOpt) = db.readOnly{ implicit ro =>
          val uriOpt = uriRepo.getByUri(url)
          val pageInfoOpt = uriOpt flatMap { uri => pageInfoRepo.getByUri(uri.id.get) }
          (uriOpt, pageInfoOpt)
        }
        uriOpt match {
          case None => Future.successful(NotFound(Json.obj("code" -> "uri_not_found")))
          case Some(uri) => {
            toJsObject(url, uri, pageInfoOpt) map { js => Ok(js) }
          }
        }
      }
    }
  }

  def getImageUrls() = JsonAction.authenticatedParseJsonAsync { request => // WIP; test-only
    val urlsOpt = (request.body \ "urls").asOpt[Seq[String]]
    log.info(s"[getImageUrls] body=${request.body} urls=${urlsOpt}")
    urlsOpt match {
      case None => Future.successful(BadRequest(Json.obj("code" -> "illegal_arguments")))
      case Some(urls) => {
        val tuples = db.readOnly { implicit ro =>
          urls.map { s =>
            s -> uriRepo.getByUri(s)
          }
        }
        val tuplesF = tuples map { case (url, uriOpt) =>
          val (uriOpt, pageInfoOpt) = db.readOnly{ implicit ro =>
            val uriOpt = uriRepo.getByUri(url)
            val pageInfoOpt = uriOpt flatMap { uri => pageInfoRepo.getByUri(uri.id.get) }
            (uriOpt, pageInfoOpt)
          }
          uriOpt match {
            case None => Future.successful(Json.obj("url" -> url, "code" -> "uri_not_found"))
            case Some(uri) => {
              toJsObject(url, uri, pageInfoOpt) // todo: batch
            }
          }
        }
        Future.sequence(tuplesF) map { res =>
          Ok(Json.toJson(res))
        }
      }
    }
  }

  def keepMultiple() = JsonAction.authenticated { request =>
    try {
      request.body.asJson.flatMap(Json.fromJson[KeepInfosWithCollection](_).asOpt) map { fromJson =>
        val source = BookmarkSource.site
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
        val (keeps, addedToCollection) = bookmarksCommander.keepMultiple(fromJson, request.userId, source)
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

  def unkeepMultiple() = JsonAction.authenticated { request =>
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

  def getKeepInfo(id: ExternalId[Bookmark]) = JsonAction.authenticated { request =>
    db.readOnly { implicit s => keepRepo.getOpt(id) } filter { _.isActive } map { b =>
      Ok(Json.toJson(KeepInfo.fromBookmark(b)))
    } getOrElse {
      NotFound(Json.obj("error" -> "Keep not found"))
    }
  }

  def updateKeepInfo(id: ExternalId[Bookmark]) = JsonAction.authenticated { request =>
    val toBeUpdated = request.body.asJson map { json =>
      val isPrivate = (json \ "isPrivate").asOpt[Boolean]
      val title = (json \ "title").asOpt[String]
      (isPrivate, title)
    }

    toBeUpdated match {
      case None | Some((None, None)) => BadRequest(Json.obj("error" -> "Could not parse JSON keep info from body"))
      case Some((isPrivate, title)) => db.readOnly { implicit s => keepRepo.getOpt(id) } map { bookmark =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.site).build
        bookmarksCommander.updateKeep(bookmark, isPrivate, title) getOrElse bookmark
      } match {
        case None => NotFound(Json.obj("error" -> "Keep not found"))
        case Some(keep) => Ok(Json.obj("keep" -> KeepInfo.fromBookmark(keep)))
      }
    }
  }

  def unkeep(id: ExternalId[Bookmark]) = JsonAction.authenticated { request =>
    db.readOnly { implicit s => keepRepo.getOpt(id) } map { bookmark =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.site).build
      val deactivatedKeepInfo = bookmarksCommander.unkeepMultiple(Seq(KeepInfo.fromBookmark(bookmark)), request.userId).head
      Ok(Json.obj("removedKeep" -> deactivatedKeepInfo))
    } getOrElse {
      NotFound(Json.obj("error" -> "Keep not found"))
    }
  }

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], count: Int) = JsonAction.authenticatedAsync { request =>
    bookmarksCommander.allKeeps(before map ExternalId[Bookmark], after map ExternalId[Bookmark], collectionOpt map ExternalId[Collection], count, request.userId) map { res =>
      Ok(Json.obj(
        "collection" -> res._1,
        "before" -> before,
        "after" -> after,
        "keeps" -> res._2
      ))
    }
  }

  def allCollections(sort: String) = JsonAction.authenticatedAsync { request =>
    val numKeepsFuture = SafeFuture { db.readOnly { implicit s => keepRepo.getCountByUser(request.userId) } }
    val collectionsFuture = SafeFuture { collectionCommander.allCollections(sort, request.userId) }
    for {
      numKeeps <- numKeepsFuture
      collections <- collectionsFuture
    } yield {
      Ok(Json.obj(
        "keeps" -> numKeeps,
        "collections" -> collections
      ))
    }
  }

  def saveCollection(id: String) = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.site).build
    collectionCommander.saveCollection(id, request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

  def deleteCollection(id: ExternalId[Collection]) = JsonAction.authenticated { request =>
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.site).build
      collectionCommander.deleteCollection(coll)
      Ok(Json.obj("deleted" -> coll.name))
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def removeKeepsFromCollection(id: ExternalId[Collection]) = JsonAction.authenticated { request =>
    implicit val externalIdFormat = ExternalId.format[Bookmark]
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Seq[ExternalId[Bookmark]]](_).asOpt) map { keepExtIds =>
        val keeps = db.readOnly { implicit s => keepExtIds.flatMap(keepRepo.getOpt(_)) }
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.site).build
        val removed = bookmarksCommander.removeFromCollection(collection, keeps)
        Ok(Json.obj("removed" -> removed.size))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep ids from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def keepToCollection(id: ExternalId[Collection]) = JsonAction.authenticated { request =>
    implicit val externalIdFormat = ExternalId.format[Bookmark]
    db.readOnly { implicit s =>
      collectionRepo.getByUserAndExternalId(request.userId, id)
    } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Seq[ExternalId[Bookmark]]](_).asOpt) map { keepExtIds =>
        val keeps = db.readOnly { implicit session => keepExtIds.map(keepRepo.get) }
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.site).build
        val added = bookmarksCommander.addToCollection(collection, keeps)
        Ok(Json.obj("added" -> added.size))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep ids from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def numKeeps() = JsonAction.authenticated { request =>
    Ok(Json.obj(
      "numKeeps" -> db.readOnly { implicit s => keepRepo.getCountByUser(request.userId) }
    ))
  }

  def mutualKeeps(id: ExternalId[User]) = JsonAction.authenticated { request =>
    Ok(Json.obj(
      "mutualKeeps" -> db.readOnly { implicit s => keepRepo.getNumMutual(request.userId, userRepo.get(id).id.get) }
    ))
  }

  def importStatus() = JsonAction.authenticated { request =>
    val values = db.readOnly { implicit session =>
      userValueRepo.getValues(request.user.id.get, "bookmark_import_last_start", "bookmark_import_done", "bookmark_import_total")
    }
    val lastStartOpt = values("bookmark_import_last_start")
    val done = values("bookmark_import_done")
    val total = values("bookmark_import_total")
    val lastStart = lastStartOpt.map { lastStart =>
      Seconds.secondsBetween(parseStandardTime(lastStart), clock.now).getSeconds
    }
    val doneOpt = Try(done.map(_.toInt)).toOption.flatten
    val totalOpt = Try(total.map(_.toInt)).toOption.flatten
    Ok(Json.obj("done" -> doneOpt, "total" -> totalOpt, "lastStart" -> lastStart))
  }
}
