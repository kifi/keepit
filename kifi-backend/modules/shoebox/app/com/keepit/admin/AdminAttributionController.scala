package com.keepit.controllers.admin

import com.keepit.common.db.Id
import com.keepit.model._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.data._
import views.html

class AdminAttributionController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  keepClickRepo: KeepClickRepo,
  rekeepRepo: ReKeepRepo,
  uriRepo: NormalizedURIRepo,
  pageInfoRepo: PageInfoRepo,
  imageInfoRepo: ImageInfoRepo
) extends AdminController(actionAuthenticator) {

  def keepClicksView(page:Int, size:Int, showImage:Boolean) = AdminHtmlAction.authenticated { request =>
    val (t, count) = db.readOnly { implicit ro =>
      val t = keepClickRepo.page(page, size).map { c =>
        val rc = RichKeepClick(c.id, c.createdAt, c.updatedAt, c.state, c.hitUUID, c.numKeepers, userRepo.get(c.keeperId), keepRepo.get(c.keepId), uriRepo.get(c.uriId), c.origin)
        val pageInfoOpt = pageInfoRepo.getByUri(c.uriId)
        val imgOpt = if (!showImage) None else
          for {
            pageInfo <- pageInfoOpt
            imgId <- pageInfo.imageInfoId
          } yield imageInfoRepo.get(imgId)
        (rc, pageInfoOpt, imgOpt)
      }
      (t, keepClickRepo.count)
    }
    Ok(html.admin.keepClicks(t, showImage, page, count, size))
  }

  def rekeepsView(page:Int, size:Int, showImage:Boolean) = AdminHtmlAction.authenticated { request =>
    val (t, count) = db.readOnly { implicit ro =>
      val t = rekeepRepo.page(page, size).map { k =>
        val rk = RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, userRepo.get(k.keeperId), keepRepo.get(k.keepId), uriRepo.get(k.uriId), userRepo.get(k.srcUserId), keepRepo.get(k.srcKeepId), k.attributionFactor)
        val pageInfoOpt = pageInfoRepo.getByUri(k.uriId)
        val imgOpt = if (!showImage) None else
          for {
            pageInfo <- pageInfoOpt
            imgId <- pageInfo.imageInfoId
          } yield imageInfoRepo.get(imgId)
        (rk, pageInfoOpt, imgOpt)
      }
      (t, rekeepRepo.count)
    }
    Ok(html.admin.rekeeps(t, showImage, page, count, size))
  }

  private def getKeepInfos(userId:Id[User]):(User, Seq[RichKeepClick], Seq[RichReKeep], Seq[RichReKeep]) = {
    db.readOnly { implicit ro =>
      val u = userRepo.get(userId)
      val rc = keepClickRepo.getClicksByKeeper(userId) map { c =>
        RichKeepClick(c.id, c.createdAt, c.updatedAt, c.state, c.hitUUID, c.numKeepers, u, keepRepo.get(c.keepId), uriRepo.get(c.uriId), c.origin)
      }
      val rk = rekeepRepo.getReKeepsByKeeper(userId) map { k =>
        RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, u, keepRepo.get(k.keepId), uriRepo.get(k.uriId), userRepo.get(k.srcUserId), keepRepo.get(k.srcKeepId), k.attributionFactor)
      }
      val rkr = rekeepRepo.getReKeepsByReKeeper(userId) map { k =>
        RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, userRepo.get(k.keeperId), keepRepo.get(k.keepId), uriRepo.get(k.uriId), u, keepRepo.get(k.srcKeepId), k.attributionFactor)
      }
      (u, rc, rk, rkr)
    }
  }

  def keepInfos(userId:Id[User]) = AdminHtmlAction.authenticated { request =>
    val (u, clicks, rekeeps, rekepts) = getKeepInfos(userId)
    Ok(html.admin.myKeepInfos(u, clicks, rekeeps, rekepts))
  }

  def myKeepInfos() = AdminHtmlAction.authenticated { request =>
    val (u, clicks, rekeeps, rekepts) = getKeepInfos(request.userId)
    Ok(html.admin.myKeepInfos(u, clicks, rekeeps, rekepts))
  }

}
