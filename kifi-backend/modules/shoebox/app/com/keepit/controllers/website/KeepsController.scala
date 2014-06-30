package com.keepit.controllers.website

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject

import com.keepit.heimdal._
import com.keepit.commanders._
import com.keepit.commanders.KeepInfosWithCollection._
import com.keepit.commanders.KeepInfo._
import com.keepit.common.controller.{AuthenticatedRequest, ShoeboxServiceController, ActionAuthenticator, WebsiteController}
import com.keepit.common.db.slick.Database
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.akka.SafeFuture

import play.api.libs.json._
import scala.util.Try
import org.joda.time.Seconds
import scala.concurrent.Future
import com.keepit.commanders.CollectionSaveFail
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsObject
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.normalizer.NormalizedURIInterner

class KeepsController @Inject() (
    db: Database,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    collectionRepo: CollectionRepo,
    uriRepo: NormalizedURIRepo,
    pageInfoRepo: PageInfoRepo,
    actionAuthenticator: ActionAuthenticator,
    uriSummaryCommander: URISummaryCommander,
    shoebox: ShoeboxServiceClient,
    collectionCommander: CollectionCommander,
    bookmarksCommander: KeepsCommander,
    userValueRepo: UserValueRepo,
    clock: Clock,
    normalizedURIInterner: NormalizedURIInterner,
    heimdalContextBuilder: HeimdalContextBuilderFactory
  )
  extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

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
        normalizedURIInterner.getByUri(url)
      } map { uriOpt =>
        uriOpt flatMap { uriSummaryCommander.getScreenshotURL(_) } match {
          case Some(url) => Ok(Json.obj("url" -> url))
          case None => NotFound(JsString("0"))
        }
      }
    }.orElse {
      urlsOpt.map { urls =>
        db.readOnlyAsync { implicit session =>
          urls.map( url => url -> normalizedURIInterner.getByUri(url) )
        } map { case uris =>
          val results = uris.map { case (uri, ssOpt) =>
            uri -> (ssOpt.flatMap{uriSummaryCommander.getScreenshotURL(_)}.map(JsString).getOrElse(JsNull): JsValue)
          }
          Ok(Json.obj("urls" -> JsObject(results)))
        }
      }
    }.getOrElse(Future.successful(BadRequest(JsString("0"))))
  }

  // todo: add uriId, sizes, colors, etc.
  private def toJsObject(url: String, uri: NormalizedURI, pageInfoOpt: Option[PageInfo]): Future[JsObject] = {
    val screenshotUrlOpt = uriSummaryCommander.getScreenshotURL(uri)
    uriSummaryCommander.getURIImage(uri) map { imgUrlOpt =>
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
          val uriOpt = normalizedURIInterner.getByUri(url)
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
            s -> normalizedURIInterner.getByUri(s)
          }
        }
        val tuplesF = tuples map { case (url, uriOpt) =>
          val (uriOpt, pageInfoOpt) = db.readOnly{ implicit ro =>
            val uriOpt = normalizedURIInterner.getByUri(url)
            val pageInfoOpt = uriOpt flatMap { uri => pageInfoRepo.getByUri(uri.id.get) }
            (uriOpt, pageInfoOpt)
          }
          uriOpt match {
            case None => Future.successful(Json.obj("url" -> url, "code" -> "uri_not_found"))
            case Some(uri) =>
              toJsObject(url, uri, pageInfoOpt) // todo: batch
          }
        }
        Future.sequence(tuplesF) map { res =>
          Ok(Json.toJson(res))
        }
      }
    }
  }

  def exportKeeps() = AnyAction.authenticated { request =>
    val exports : Seq[KeepExport] = db.readOnly { implicit ro =>
      keepRepo.getKeepExports(request.userId)
    }

    Ok(KeepsController.assembleKeepExport(exports))
      .withHeaders("Content-Disposition" -> "attachment; filename=keepExports.html")
      .as("text/html")
  }

  def keepMultiple(separateExisting: Boolean = false) = JsonAction.authenticated { request =>
    try {
      request.body.asJson.flatMap(Json.fromJson[KeepInfosWithCollection](_).asOpt) map { fromJson =>
        val source = KeepSource.site
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
        val (keeps, addedToCollection, failures, alreadyKeptOpt) = bookmarksCommander.keepMultiple(fromJson, request.userId, source, separateExisting)
        log.info(s"kept ${keeps.size} keeps")
        Ok(Json.obj(
          "keeps" -> keeps,
          "failures" -> failures,
          "addedToCollection" -> addedToCollection
        ) ++ (alreadyKeptOpt map (keeps => Json.obj("alreadyKept" -> keeps)) getOrElse Json.obj()))
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

  def unkeepBatch() = JsonAction.authenticatedParseJson { request =>
    implicit val keepFormat = ExternalId.format[Keep]
    val idsOpt = (request.body \ "ids").asOpt[Seq[ExternalId[Keep]]]
    idsOpt map { ids =>
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      val (successes, failures) = bookmarksCommander.unkeepBatch(ids, request.userId)
      Ok(Json.obj(
        "removedKeeps" -> successes,
        "errors" -> failures.map(id => Json.obj("id" -> id, "error" -> "not_found"))
      ))
    } getOrElse {
      BadRequest(Json.obj("error" -> "parse_error"))
    }
  }

  def unkeepSet() = JsonAction.authenticatedParseJson { request =>
    Json.fromJson[KeepSet](request.body).asOpt map { keepSet =>
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      val deactivatedKeepInfos = bookmarksCommander.unkeepSet(keepSet, request.userId)
      Ok(Json.obj(
        "removedKeeps" -> deactivatedKeepInfos
      ))
    } getOrElse {
      BadRequest(Json.obj("error" -> "Could not parse JSON keep set from request body"))
    }
  }

  def rekeepSet() = JsonAction.authenticatedParseJson { request =>
    Json.fromJson[KeepSet](request.body).asOpt map { keepSet =>
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      val numRekept = bookmarksCommander.rekeepSet(keepSet, request.userId)
      Ok(Json.obj("numRekept" -> numRekept))
    } getOrElse {
      BadRequest(Json.obj("error" -> "Could not parse JSON keep set from request body"))
    }
  }

  def makeSetPublic() = JsonAction.authenticatedParseJson { request =>
    setKeepSetPrivacy(request, false)
  }

  def makeSetPrivate() = JsonAction.authenticatedParseJson { request =>
    setKeepSetPrivacy(request, true)
  }

  private def setKeepSetPrivacy(request: AuthenticatedRequest[JsValue], isPrivate: Boolean) = {
    Json.fromJson[KeepSet](request.body).asOpt map { keepSet =>
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      val numUpdated = bookmarksCommander.setKeepSetPrivacy(keepSet, request.userId, isPrivate)
      Ok(Json.obj("numUpdated" -> numUpdated))
    } getOrElse {
      BadRequest(Json.obj("error" -> "Could not parse JSON keep set from request body"))
    }
  }

  def tagKeepSet() = JsonAction.authenticatedParseJson(editKeepSetTag(_, true))

  def untagKeepSet() = JsonAction.authenticatedParseJson(editKeepSetTag(_, false))

  private def editKeepSetTag(request: AuthenticatedRequest[JsValue], isAdd: Boolean) = {
    val collectionId = (request.body \ "collectionId").asOpt[ExternalId[Collection]]
    val keepSet = (request.body \ "keeps").asOpt[KeepSet]
    val res = for {
      collectionId <- (request.body \ "collectionId").asOpt[ExternalId[Collection]]
      keepSet <- (request.body \ "keeps").asOpt[KeepSet]
    } yield {
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      val numEdited = bookmarksCommander.editKeepSetTag(collectionId, keepSet, request.userId, isAdd)
      Ok(Json.obj("numEdited" -> numEdited))
    }
    res getOrElse BadRequest(Json.obj("error" -> "Could not parse keep set and/or collection id from request body"))
  }

  def getKeepInfo(id: ExternalId[Keep], withFullInfo: Boolean) = JsonAction.authenticatedAsync { request =>
    val resOpt = if (withFullInfo) {
      bookmarksCommander.getFullKeepInfo(id, request.userId, true) map { infoFut =>
        infoFut map { info =>
          Ok(Json.toJson(KeepInfo.fromFullKeepInfo(info)))
        }
      }
    } else {
      db.readOnly { implicit s => keepRepo.getOpt(id) } filter { _.isActive } map { b =>
        Future.successful(Ok(Json.toJson(KeepInfo.fromBookmark(b))))
      }
    }
    resOpt.getOrElse(Future.successful(NotFound(Json.obj("error" -> "not_found"))))
  }

  def updateKeepInfo(id: ExternalId[Keep]) = JsonAction.authenticated { request =>
    val toBeUpdated = request.body.asJson map { json =>
      val isPrivate = (json \ "isPrivate").asOpt[Boolean]
      val title = (json \ "title").asOpt[String]
      (isPrivate, title)
    }

    toBeUpdated match {
      case None | Some((None, None)) => BadRequest(Json.obj("error" -> "Could not parse JSON keep info from body"))
      case Some((isPrivate, title)) => db.readOnly { implicit s => keepRepo.getOpt(id) } map { bookmark =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        bookmarksCommander.updateKeep(bookmark, isPrivate, title) getOrElse bookmark
      } match {
        case None => NotFound(Json.obj("error" -> "Keep not found"))
        case Some(keep) => Ok(Json.obj("keep" -> KeepInfo.fromBookmark(keep)))
      }
    }
  }

  def unkeep(id: ExternalId[Keep]) = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    bookmarksCommander.unkeep(id, request.userId) map { ki =>
      Ok(Json.toJson(ki))
    } getOrElse {
      NotFound(Json.obj("error" -> "not_found"))
    }
  }

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean) = JsonAction.authenticatedAsync { request =>
    bookmarksCommander.allKeeps(before map ExternalId[Keep], after map ExternalId[Keep], collectionOpt map ExternalId[Collection], helprankOpt, count, request.userId, withPageInfo) map { res =>
      val helprank = helprankOpt map (selector => Json.obj("helprank" -> selector)) getOrElse Json.obj()
      Ok(Json.obj(
        "collection" -> res._1,
        "before" -> before,
        "after" -> after,
        "keeps" -> res._2.map(KeepInfo.fromFullKeepInfo(_))
      ) ++ helprank)
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
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    collectionCommander.saveCollection(id, request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

  def deleteCollection(id: ExternalId[Collection]) = JsonAction.authenticated { request =>
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
      collectionCommander.deleteCollection(coll)
      Ok(Json.obj("deleted" -> coll.name))
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def undeleteCollection(id: ExternalId[Collection]) = JsonAction.authenticated { request =>
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id, Some(CollectionStates.ACTIVE)) } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
      collectionCommander.undeleteCollection(coll)
      Ok(Json.obj("undeleted" -> coll.name))
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def removeKeepsFromCollection(id: ExternalId[Collection]) = JsonAction.authenticated { request =>
    implicit val externalIdFormat = ExternalId.format[Keep]
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Seq[ExternalId[Keep]]](_).asOpt) map { keepExtIds =>
        val keeps = db.readOnly { implicit s => keepExtIds.flatMap(keepRepo.getOpt(_)) }
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
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
    implicit val externalIdFormat = ExternalId.format[Keep]
    db.readOnly { implicit s =>
      collectionRepo.getByUserAndExternalId(request.userId, id)
    } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Seq[ExternalId[Keep]]](_).asOpt) map { keepExtIds =>
        val keeps = db.readOnly { implicit session => keepExtIds.map(keepRepo.get) }
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        val added = bookmarksCommander.addToCollection(collection.id.get, keeps)
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

object KeepsController {

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

    def CreateExport(keep : KeepExport): String = {
      // Parse Tags
      val title = keep.title getOrElse ""
      val tagString = keep.tags map { tags =>
        s""" TAGS="${tags.replace("&","&amp;").replace("\"","")}""""
      } getOrElse ""
      val date = keep.created_at.getMillis()/1000
      val line =
        s"""<DT><A HREF="${keep.url}" ADD_DATE="${date}"${tagString}>${title.replace("&","&amp;")}</A>"""
          .stripMargin
      line
    }
    before + keepExports.map(CreateExport).mkString("\n") + after
  }
}
