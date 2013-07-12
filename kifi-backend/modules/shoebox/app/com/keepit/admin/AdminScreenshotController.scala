package com.keepit.controllers.admin

import play.api.Play.current
import com.keepit.common.db.Id
import com.keepit.common.db.LargeString._
import com.keepit.model._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton, Provider}
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.common.db.slick.Database
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class AdminScreenshotController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  s3ScreenshotStore: S3ScreenshotStore,
  db: Database,
  bookmarkRepo: BookmarkRepo,
  normUriRepo: NormalizedURIRepo)
  extends AdminController(actionAuthenticator) {

  def updateUri(uriId: Id[NormalizedURI]) = AdminHtmlAction { implicit request =>
    val normUri = db.readOnly { implicit session =>
      normUriRepo.get(uriId)
    }
    val req = s3ScreenshotStore.updatePicture(normUri)
    Async {
      req.map { result =>
        val screenshotUrl = s3ScreenshotStore.getScreenshotUrl(normUri).getOrElse("")
        Ok("Done: " + result + s"\n<br><br>\n<a href='$screenshotUrl'>link</a>")
      }
    }
  }

  def updateUser(userId: Id[User], drop: Int = 0, take: Int = 999999) = AdminHtmlAction { implicit request =>
    val uris = db.readOnly { implicit session =>
      bookmarkRepo.getByUser(userId).map(_.uriId)
    }
    uris.drop(drop).take(take).grouped(100).map { uriGroup =>
      db.readOnly { implicit session =>
        uriGroup.map { uriId =>
          val normUri = normUriRepo.get(uriId)
          s3ScreenshotStore.updatePicture(normUri)
        }
      }
    }

    Ok("Goin!")
  }

}
