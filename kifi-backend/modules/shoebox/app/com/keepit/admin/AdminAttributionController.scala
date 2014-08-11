package com.keepit.controllers.admin

import com.keepit.common.db.Id
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model._
import views.html
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.data._
import views.html
import com.keepit.common.db.slick.DBSession.{ ROSession, RWSession }
import scala.collection.mutable
import com.keepit.commanders.AttributionCommander
import com.keepit.common.db.slick.Database.{ DBMasterReplica, Replica }
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.concurrent.duration._
import com.keepit.common.service.RequestConsolidator
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ExecutionContext.fj
import play.api.libs.json.Json

class AdminAttributionController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    heimdalClient: HeimdalServiceClient,
    attributionCmdr: AttributionCommander,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    keepDiscoveryRepo: KeepDiscoveryRepo,
    rekeepRepo: ReKeepRepo,
    uriRepo: NormalizedURIRepo,
    pageInfoRepo: PageInfoRepo,
    imageInfoRepo: ImageInfoRepo,
    userBookmarkClicksRepo: UserBookmarkClicksRepo) extends AdminController(actionAuthenticator) {

  implicit val execCtx = fj

  def keepDiscoveriesViewNew(page: Int, size: Int, showImage: Boolean) = AdminHtmlAction.authenticatedAsync { request =>
    heimdalClient.getPagedKeepDiscoveries(page, size) map { paged =>
      db.readOnlyMaster { implicit session =>
        paged map { c =>
          val rc = RichKeepDiscovery(c.id, c.createdAt, c.updatedAt, c.state, c.hitUUID, c.numKeepers, userRepo.get(c.keeperId), keepRepo.get(c.keepId), uriRepo.get(c.uriId), c.origin)
          val pageInfoOpt = pageInfoRepo.getByUri(c.uriId)
          val imgOpt = if (!showImage) None
          else
            for {
              pageInfo <- pageInfoOpt
              imgId <- pageInfo.imageInfoId
            } yield imageInfoRepo.get(imgId)
          (rc, pageInfoOpt, imgOpt)
        }
      }
    } map { t =>
      Ok(html.admin.keepDiscoveries(t, false, page, 42, size))
    }
  }

  def keepDiscoveriesView(page: Int, size: Int, showImage: Boolean) = AdminHtmlAction.authenticated { request =>
    val (t, count) = db.readOnlyMaster { implicit ro =>
      val t = keepDiscoveryRepo.page(page, size, Set(KeepDiscoveryStates.INACTIVE)).map { c =>
        val rc = RichKeepDiscovery(c.id, c.createdAt, c.updatedAt, c.state, c.hitUUID, c.numKeepers, userRepo.get(c.keeperId), keepRepo.get(c.keepId), uriRepo.get(c.uriId), c.origin)
        val pageInfoOpt = pageInfoRepo.getByUri(c.uriId)
        val imgOpt = if (!showImage) None else
          for {
            pageInfo <- pageInfoOpt
            imgId <- pageInfo.imageInfoId
          } yield imageInfoRepo.get(imgId)
        (rc, pageInfoOpt, imgOpt)
      }
      (t, keepDiscoveryRepo.count)
    }
    Ok(html.admin.keepDiscoveries(t, showImage, page, count, size))
  }

  def rekeepsViewNew(page: Int, size: Int, showImage: Boolean) = AdminHtmlAction.authenticatedAsync { request =>
    heimdalClient.getPagedReKeeps(page, size) map { paged =>
      db.readOnlyMaster { implicit session =>
        val t = paged map { k =>
          val rk = RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, userRepo.get(k.keeperId), keepRepo.get(k.keepId), uriRepo.get(k.uriId), userRepo.get(k.srcUserId), keepRepo.get(k.srcKeepId), k.attributionFactor)
          val pageInfoOpt = pageInfoRepo.getByUri(k.uriId)
          val imgOpt = if (!showImage) None else
            for {
              pageInfo <- pageInfoOpt
              imgId <- pageInfo.imageInfoId
            } yield imageInfoRepo.get(imgId)
          (rk, pageInfoOpt, imgOpt)
        }
        Ok(html.admin.rekeeps(t, showImage, page, 42, size))
      }
    }
  }

  def rekeepsView(page: Int, size: Int, showImage: Boolean) = AdminHtmlAction.authenticated { request =>
    val (t, count) = db.readOnlyReplica { implicit ro =>
      val t = rekeepRepo.page(page, size, Set(ReKeepStates.INACTIVE)).map { k =>
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

  private def getKeepInfos(userId: Id[User]): (User, Seq[RichKeepDiscovery], Seq[RichReKeep], Seq[RichReKeep]) = {
    db.readOnlyMaster { implicit ro =>
      val u = userRepo.get(userId)
      val rc = keepDiscoveryRepo.getDiscoveriesByKeeper(userId).take(10) map { c =>
        RichKeepDiscovery(c.id, c.createdAt, c.updatedAt, c.state, c.hitUUID, c.numKeepers, u, keepRepo.get(c.keepId), uriRepo.get(c.uriId), c.origin)
      }
      val rekeeps = rekeepRepo.getAllReKeepsByKeeper(userId).sortBy(_.createdAt)(Ordering[DateTime].reverse).take(10)
      val rk = rekeeps map { k =>
        RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, u, keepRepo.get(k.keepId), uriRepo.get(k.uriId), userRepo.get(k.srcUserId), keepRepo.get(k.srcKeepId), k.attributionFactor)
      }
      val rkr = rekeepRepo.getAllReKeepsByReKeeper(userId).take(10) map { k =>
        RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, userRepo.get(k.keeperId), keepRepo.get(k.keepId), uriRepo.get(k.uriId), u, keepRepo.get(k.srcKeepId), k.attributionFactor)
      }
      (u, rc, rk, rkr)
    }
  }

  def keepInfos(userId: Id[User]) = AdminHtmlAction.authenticated { request =>
    val (u, clicks, rekeeps, rekepts) = getKeepInfos(userId)
    Ok(html.admin.myKeepInfos(u, clicks, rekeeps, rekepts))
  }

  val userReKeepsReqConsolidator = new RequestConsolidator[(Id[User], Int), (User, Int, Seq[ReKeep], Seq[(Keep, Seq[Set[User]])], Seq[(Int, Int)])](ttl = 5 seconds)
  def getReKeepInfos(userId: Id[User], n: Int = 4) = userReKeepsReqConsolidator(userId, n) {
    case (userId, n) =>
      SafeFuture {
        val u = db.readOnlyMaster { implicit ro => userRepo.get(userId) }
        val rekeeps = db.readOnlyReplica { implicit ro =>
          rekeepRepo.getAllReKeepsByKeeper(userId)
        }
        val grouped = rekeeps.groupBy(_.keepId)
        val sorted = grouped.toSeq.sortBy(_._2.length)(Ordering[Int].reverse).take(20)
        val users = sorted.map(_._1).map { keepId =>
          val userIds: Seq[Set[Id[User]]] = attributionCmdr.getReKeepsByDegree(userId, keepId, 4).map(_._1)
          val users = db.readOnlyReplica { implicit ro => userRepo.getUsers(userIds.foldLeft(Seq.empty[Id[User]]) { (a, c) => a ++ c }) }
          db.readOnlyReplica { implicit ro => keepRepo.get(keepId) } -> userIds.map(_.map(uId => users(uId)))
        }
        val counts = db.readOnlyReplica { implicit ro =>
          users.map {
            case (keep, _) =>
              userBookmarkClicksRepo.getByUserUri(userId, keep.uriId) map { bc =>
                (bc.rekeepCount, bc.rekeepTotalCount)
              } getOrElse (-1, -1)
          }
        }
        (u, n, rekeeps, users, counts)
      }
  }

  def reKeepInfos(userId: Id[User]) = AdminHtmlAction.authenticatedAsync { request =>
    getReKeepInfos(userId) map {
      case (u, n, rekeeps, users, counts) =>
        Ok(html.admin.myReKeeps(u, n, users zip counts map { case ((keep, users), counts) => (keep, counts._1, counts._2, users) })) // sanity check
    }
  }

  def myReKeeps() = AdminHtmlAction.authenticatedAsync { request =>
    getReKeepInfos(request.userId) map {
      case (u, n, rekeeps, users, counts) =>
        Ok(html.admin.myReKeeps(u, n, users map { case (keep, users) => (keep, users(1).size, users.flatten.length - 1, users) }))
    }
  }

  def myKeepInfos() = AdminHtmlAction.authenticated { request =>
    val (u, clicks, rekeeps, rekepts) = getKeepInfos(request.userId)
    Ok(html.admin.myKeepInfos(u, clicks, rekeeps, rekepts))
  }

  val topReKeepsReqConsolidator = new RequestConsolidator[Int, Seq[(NormalizedURI, Seq[(Keep, Seq[Set[User]])])]](ttl = 5 seconds)
  def getTopReKeeps(degree: Int) = topReKeepsReqConsolidator(degree) { degree =>
    SafeFuture {
      val rkMap = db.readOnlyReplica { implicit ro =>
        rekeepRepo.getAllDirectReKeepCountsByKeep()
      }
      val filtered = rkMap.toSeq.sortBy(_._2)(Ordering[Int].reverse).take(10).toMap
      val byDeg = attributionCmdr.getUserReKeepsByDegree(filtered.map(_._1).toSet, degree)
      val sorted = byDeg.toSeq.sortBy { case (keepId, usersByDeg) => usersByDeg.flatten.length }(Ordering[Int].reverse)
      val userIds = sorted.map(_._2).flatten.foldLeft(Set.empty[Id[User]]) { (a, c) => a ++ c }
      val users = db.readOnlyReplica { implicit ro => userRepo.getUsers(userIds.toSeq) }
      val richByDeg = sorted.map {
        case (kId, userIdsByDeg) =>
          db.readOnlyReplica { implicit ro => keepRepo.get(kId) -> userIdsByDeg.map(_.map(uId => users(uId))) }
      }
      val grouped = richByDeg.groupBy(_._1.uriId).map {
        case (uriId, keepsAndUsers) =>
          db.readOnlyReplica { implicit ro => uriRepo.get(uriId) -> keepsAndUsers }
      }.toSeq.sortBy { case (uri, keepsAndUsers) => keepsAndUsers.map { _._2.flatten }.length }(Ordering[Int].reverse)
      log.info(s"getTopReKeeps($degree)=$grouped")
      grouped
    }
  }

  def topReKeeps(degree: Int) = AdminHtmlAction.authenticatedAsync { request =>
    getTopReKeeps(degree) map { grouped =>
      Ok(html.admin.topReKeeps(degree, grouped))
    }
  }

  def updateReKeepStats() = AdminHtmlAction.authenticatedAsync { request =>
    heimdalClient.updateUserReKeepStats(request.userId) map { _ =>
      Ok(s"Update request sent for userId=${request.userId}")
    }
  }

  def updateUserReKeepStats() = AdminHtmlAction.authenticatedParseJsonAsync { request =>
    Json.fromJson[Id[User]](request.body).asOpt map { userId =>
      heimdalClient.updateUserReKeepStats(userId) map { _ =>
        Ok(s"Update request sent for userId=${userId}")
      }
    } getOrElse Future.successful(BadRequest(s"Illegal argument"))
  }

  def updateUsersReKeepStats() = AdminHtmlAction.authenticatedParseJsonAsync { request =>
    Json.fromJson[Seq[Id[User]]](request.body).asOpt map { userIds =>
      heimdalClient.updateUsersReKeepStats(userIds) map { _ =>
        Ok(s"Update request sent for ${userIds.length} users (${userIds.take(5).mkString(",")} ... )")
      }
    } getOrElse Future.successful(BadRequest(s"Illegal argument"))
  }

  def updateAllReKeepStats() = AdminHtmlAction.authenticatedAsync { request =>
    heimdalClient.updateAllReKeepStats() map { _ =>
      Ok(s"Update request sent for all users")
    }
  }

}
