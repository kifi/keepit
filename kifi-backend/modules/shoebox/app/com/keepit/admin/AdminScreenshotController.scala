package com.keepit.controllers.admin

import play.api.Play.current
import com.keepit.common.db.Id
import com.keepit.common.db.LargeString._
import com.keepit.model._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.common.db.slick.Database
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import play.api.libs.json.{JsValue, JsArray, Json}
import play.api.data._
import play.api.data.Forms._
import views.html

class AdminScreenshotController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  s3ScreenshotStore: S3ScreenshotStore,
  db: Database,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo)
  extends AdminController(actionAuthenticator) {

  def updateUri(uriId: Id[NormalizedURI]) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val normUri = db.readOnly { implicit session =>
      uriRepo.get(uriId)
    }
    val req = s3ScreenshotStore.updatePicture(normUri)
    req.map { result =>
      val screenshotUrl = s3ScreenshotStore.getScreenshotUrl(normUri).getOrElse("")
      Ok("Done: " + result + s"\n<br><br>\n<a href='$screenshotUrl'>link</a>")
    }
}

  def updateUser(userId: Id[User], drop: Int = 0, take: Int = 999999) = AdminHtmlAction.authenticated { implicit request =>
    val uris = db.readOnly { implicit session =>
      bookmarkRepo.getByUser(userId).map(_.uriId)
    }
    uris.drop(drop).take(take).grouped(100).foreach { uriGroup =>
      db.readOnly { implicit session =>
        uriGroup.map { uriId =>
          val normUri = uriRepo.get(uriId)
          s3ScreenshotStore.updatePicture(normUri)
        }
      }
    }

    Ok("Goin!")
  }

  def images() = AdminHtmlAction.authenticated { request =>
    Ok(html.admin.images())
  }

  def imageInfos(uriId:Id[NormalizedURI]) = AdminHtmlAction.authenticatedAsync { request =>
    val uri = db.readOnly { implicit ro => uriRepo.get(uriId) }
    s3ScreenshotStore.getImageInfos(uri) map { infos =>
      Ok(html.admin.imageInfos(uri, s3ScreenshotStore.getScreenshotUrl(uri), infos))
    }
  }

  val compareForm = Form("uriIds" -> text)
  def imagesCompare() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val uriIds = compareForm.bindFromRequest.get.split(',').map(s => Id[NormalizedURI](s.toLong)).toSeq
    val tuplesF = uriIds map { uriId =>
      val uri = db.readOnly { implicit ro => uriRepo.get(uriId) }
      val screenshotUrl = s3ScreenshotStore.getScreenshotUrl(uri)
      s3ScreenshotStore.asyncGetImageUrl(uri) map { imgUrl =>
        (uri, screenshotUrl, imgUrl)
      }
    }
    Future.sequence(tuplesF) map { tuples =>
      Ok(html.admin.imagesCompare(tuples))
    }
  }

  def getImageInfo() = AdminJsonAction.authenticatedParseJsonAsync { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    log.info(s"[getImageInfo] body=${request.body} url=${urlOpt}")
    val resOpt = urlOpt map { url =>
      val images = db.readOnly { implicit ro => uriRepo.getByUri(url) } match {
        case Some(uri) =>
          s3ScreenshotStore.getImageInfos(uri).map { infos =>
            infos.map { Json.toJson(_) }
          }
        case None => Future.successful(Seq.empty[JsValue])
      }
      images.map{ js => Ok(JsArray(js)) }
    }
    resOpt.getOrElse(Future.successful(NotFound(Json.obj("code" -> "not_found"))))
  }

  def getImageInfos() = AdminJsonAction.authenticatedParseJsonAsync { request =>
    val urlsOpt = (request.body \ "urls").asOpt[Seq[String]]
    log.info(s"[getImageInfos] body=${request.body} urls=${urlsOpt}")
    urlsOpt match {
      case Some(urls) =>
        val uris = db.readOnly { implicit ro =>
          urls.map(url => url -> uriRepo.getByUri(url))
        }
        val imgRes = uris map { case (url, uriOpt) =>
          uriOpt match {
            case Some(uri) =>
            val jsF = s3ScreenshotStore.getImageInfos(uri) map { infos =>
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
