package com.keepit.controllers.admin

import com.keepit.common.db.Id
import com.keepit.common.db.LargeString._
import com.keepit.model._
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import play.api.libs.json.{ JsValue, JsArray, Json }
import play.api.data._
import play.api.data.Forms._
import views.html
import scala.util.{ Failure, Success, Try }
import com.keepit.commanders.URISummaryCommander
import com.keepit.scraper.ScraperServiceClient
import com.keepit.normalizer.NormalizedURIInterner

class AdminScreenshotController @Inject() (
  val userActionsHelper: UserActionsHelper,
  uriSummaryCommander: URISummaryCommander,
  uriImageCommander: URISummaryCommander,
  scraper: ScraperServiceClient,
  db: Database,
  keepRepo: KeepRepo,
  pageInfoRepo: PageInfoRepo,
  imageInfoRepo: ImageInfoRepo,
  normalizedURIInterner: NormalizedURIInterner,
  uriRepo: NormalizedURIRepo)
    extends AdminUserActions {

  def images() = AdminUserPage { implicit request =>
    Ok(html.admin.images())
  }

  def imageInfos() = AdminUserPage { implicit request =>
    val imageInfos = db.readOnlyMaster { implicit ro =>
      imageInfoRepo.page(page = 0, size = 50).sortBy(_.id.get.id)
    }
    // add pagination
    Ok(html.admin.imageInfos(imageInfos))
  }

  def imagesForUri(uriId: Id[NormalizedURI]) = AdminUserPage.async { implicit request =>
    Try {
      db.readOnlyReplica { implicit ro =>
        uriRepo.get(uriId)
      }
    } match {
      case Success(uri) =>
        scraper.adminOnlyGetEmbedlyImageInfos(uri.id.get, uri.url) map { infos =>
          Ok(html.admin.imagesForUri(uri, None, infos))
        }
      case Failure(t) =>
        Future.successful(BadRequest(s"uriId($uriId) not found"))
    }
  }

  val compareForm = Form("uriIds" -> text)
  def imagesCompare() = AdminUserPage.async { implicit request =>
    try {
      val uriIds = compareForm.bindFromRequest.get.split(',').map(s => Id[NormalizedURI](s.toLong)).toSeq
      val tuplesF = uriIds map { uriId =>
        val (uri, pageInfoOpt) = db.readOnlyMaster { implicit ro =>
          val uri = uriRepo.get(uriId)
          val pageInfoOpt = pageInfoRepo.getByUri(uriId)
          (uri, pageInfoOpt)
        }
        uriSummaryCommander.getURIImage(uri) map { imageUrlOpt =>
          (uri, None, imageUrlOpt)
        }
      }
      Future.sequence(tuplesF) map { tuples =>
        Ok(html.admin.imagesCompare(tuples))
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace
        Future.successful(BadRequest("Invalid Arguments"))
    }
  }

  def getImageInfo() = AdminUserAction.async(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    log.info(s"[getImageInfo] body=${request.body} url=${urlOpt}")
    val resOpt = urlOpt map { url =>
      val images = db.readOnlyMaster { implicit ro => normalizedURIInterner.getByUri(url) } match {
        case Some(uri) =>
          scraper.adminOnlyGetEmbedlyImageInfos(uri.id.get, uri.url) map { infos =>
            infos.map { Json.toJson(_) }
          }
        case None => Future.successful(Seq.empty[JsValue])
      }
      images.map { js => Ok(JsArray(js)) }
    }
    resOpt.getOrElse(Future.successful(NotFound(Json.obj("code" -> "not_found"))))
  }

  def getImageInfos() = AdminUserAction.async(parse.tolerantJson) { request =>
    val urlsOpt = (request.body \ "urls").asOpt[Seq[String]]
    log.info(s"[getImageInfos] body=${request.body} urls=${urlsOpt}")
    urlsOpt match {
      case Some(urls) =>
        val uris = db.readOnlyMaster { implicit ro =>
          urls.map(url => url -> normalizedURIInterner.getByUri(url))
        }
        val imgRes = uris map {
          case (url, uriOpt) =>
            uriOpt match {
              case Some(uri) =>
                val jsF = scraper.adminOnlyGetEmbedlyImageInfos(uri.id.get, uri.url) map { infos =>
                  Json.obj("url" -> url, "images" -> Json.toJson(infos))
                }
                jsF
              case None => Future.successful(Json.obj("url" -> url, "images" -> JsArray(Seq.empty[JsValue])))
            }
        }
        Future.sequence(imgRes) map { imgRes =>
          Ok(Json.obj("urls" -> JsArray(imgRes)))
        }
      case None => Future.successful(BadRequest(Json.obj("code" -> s"illegal_arguments (${request.body})")))
    }
  }

}
