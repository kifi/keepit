package com.keepit.controllers.admin

import com.keepit.common.db.Id
import com.keepit.model._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.data._
import views.html
import com.keepit.common.db.slick.DBSession.{ROSession, RWSession}
import scala.collection.mutable
import com.keepit.commanders.AttributionCommander
import com.keepit.common.db.slick.Database.Slave
import org.joda.time.DateTime
import com.keepit.common.time._

class AdminAttributionController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  attributionCmdr: AttributionCommander,
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

  private def getKeepInfos(userId:Id[User]):(User, Seq[RichKeepClick], Seq[(Id[Keep],Int)], Seq[RichReKeep], Seq[RichReKeep], Seq[(Id[Keep],Int)]) = {
    db.readOnly { implicit ro =>
      val u = userRepo.get(userId)
      val rc = keepClickRepo.getClicksByKeeper(userId) map { c =>
        RichKeepClick(c.id, c.createdAt, c.updatedAt, c.state, c.hitUUID, c.numKeepers, u, keepRepo.get(c.keepId), uriRepo.get(c.uriId), c.origin)
      }
      val counts = keepClickRepo.getClickCountsByKeeper(userId).toSeq
      val rekeeps = rekeepRepo.getAllReKeepsByKeeper(userId)
      val rk = rekeeps map { k =>
        RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, u, keepRepo.get(k.keepId), uriRepo.get(k.uriId), userRepo.get(k.srcUserId), keepRepo.get(k.srcKeepId), k.attributionFactor)
      }
      val rkr = rekeepRepo.getAllReKeepsByReKeeper(userId) map { k =>
        RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, userRepo.get(k.keeperId), keepRepo.get(k.keepId), uriRepo.get(k.uriId), u, keepRepo.get(k.srcKeepId), k.attributionFactor)
      }
      val rkCounts = rekeepRepo.getReKeepCountsByKeeper(userId).toSeq
      (u, rc, counts, rk, rkr, rkCounts)
    }
  }

  def keepInfos(userId:Id[User]) = AdminHtmlAction.authenticated { request =>
    val (u, clicks, clickCounts, rekeeps, rekepts, rkCounts) = getKeepInfos(userId)
    Ok(html.admin.myKeepInfos(u, clicks, clickCounts, rekeeps, rekepts, rkCounts))
  }

  def getReKeepInfos(userId:Id[User], n:Int = 4) = {
    val u = db.readOnly(dbMasterSlave = Slave) { implicit ro => userRepo.get(userId) }
    val rekeeps = db.readOnly(dbMasterSlave = Slave) { implicit ro =>
      rekeepRepo.getAllReKeepsByKeeper(userId)
    }
    val users = rekeeps.map { rk =>
      val userIds: Seq[Set[Id[User]]] = attributionCmdr.getReKeepsByDegree(userId, rk.keepId, 4).map(_._1)
      val users = db.readOnly(dbMasterSlave = Slave) { implicit ro => userRepo.getUsers(userIds.foldLeft(Seq.empty[Id[User]]) { (a,c) => a ++ c }) }
      db.readOnly(dbMasterSlave = Slave) { implicit ro => keepRepo.get(rk.keepId) } -> userIds.map( _.map(uId => users(uId)) )
    }.toMap

    (u, n, rekeeps, users)
  }

  def reKeepInfos(userId:Id[User]) = AdminHtmlAction.authenticated { request =>
    val (u, n, rekeeps, users) = getReKeepInfos(userId)
    Ok(html.admin.myReKeeps(u, n, rekeeps, users))
  }

  def myReKeeps() = AdminHtmlAction.authenticated { request =>
    val (u, n, rekeeps, users) = getReKeepInfos(request.userId)
    Ok(html.admin.myReKeeps(u, n, rekeeps, users))
  }

  def myKeepInfos() = AdminHtmlAction.authenticated { request =>
    val (u, clicks, clickCounts, rekeeps, rekepts, rkCounts) = getKeepInfos(request.userId)
    Ok(html.admin.myKeepInfos(u, clicks, clickCounts, rekeeps, rekepts, rkCounts))
  }

  def keepAttribution(degree:Int) = AdminHtmlAction.authenticated { request =>
    val rkMap = db.readOnly(dbMasterSlave = Slave) { implicit ro =>
      rekeepRepo.getAllDirectReKeepCountsByKeep()
    }
    val filtered = rkMap.toSeq.sortBy(_._2)(Ordering[Int].reverse).take(20).toMap
    val byDeg = attributionCmdr.getUserReKeepsByDegree(filtered.map(_._1).toSet)
    val sorted = byDeg.toSeq.sortBy{ case (keepId, usersByDeg) => usersByDeg.flatten.length }(Ordering[Int].reverse)
    val userIds = sorted.map(_._2).flatten.foldLeft(Set.empty[Id[User]]) {(a,c) => a ++ c}
    val users = db.readOnly(dbMasterSlave = Slave) { implicit ro => userRepo.getUsers(userIds.toSeq) }
    val richByDeg = sorted.map{ case(kId, userIdsByDeg) =>
      db.readOnly(dbMasterSlave = Slave) { implicit ro => keepRepo.get(kId) -> userIdsByDeg.map(_.map(uId => users(uId))) }
    }
    val grouped = richByDeg.groupBy(_._1.uriId).map { case (uriId, keepsAndUsers) =>
      db.readOnly(dbMasterSlave = Slave) { implicit ro => uriRepo.get(uriId) -> keepsAndUsers }
    }.toSeq.sortBy{ case (uri, keepsAndUsers) => keepsAndUsers.map{_._2.flatten}.length }(Ordering[Int].reverse)
    Ok(html.admin.keepAttribution(degree, grouped))
  }

}
