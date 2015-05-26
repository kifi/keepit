package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.{ ProcessedImageSize, ScaleImageRequest, KeepImageCommander }
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.service.RequestConsolidator
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits._
import views.html

import scala.concurrent.Future
import scala.concurrent.duration._

class AdminAttributionController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    heimdalClient: HeimdalServiceClient,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    uriRepo: NormalizedURIRepo,
    keepImageCommander: KeepImageCommander,
    rover: RoverServiceClient) extends AdminUserActions {

  def keepDiscoveriesView(page: Int, size: Int, showImage: Boolean) = AdminUserPage.async { implicit request =>
    val countF = heimdalClient.getDiscoveryCount()
    val pagedF = heimdalClient.getPagedKeepDiscoveries(page, size)
    val resF = for {
      count <- countF
      paged <- pagedF
    } yield {
      val t = db.readOnlyMaster { implicit session =>
        paged map { c =>
          val rc = RichKeepDiscovery(c.id, c.createdAt, c.updatedAt, c.state, c.hitUUID, c.numKeepers, userRepo.get(c.keeperId), keepRepo.get(c.keepId), uriRepo.get(c.uriId), c.origin)
          val imgOpt = if (!showImage) None
          else keepImageCommander.getBestImageForKeep(c.keepId, ScaleImageRequest(ProcessedImageSize.Small.idealSize)).flatten.map(keepImageCommander.getUrl)
          (rc, imgOpt)
        }
      }
      (t, count)
    }
    resF map {
      case (t, count) =>
        Ok(html.admin.keepDiscoveries(t, false, page, count, size))
    }
  }

  def rekeepsView(page: Int, size: Int, showImage: Boolean) = AdminUserPage.async { implicit request =>
    val countF = heimdalClient.getReKeepCount()
    val pagedF = heimdalClient.getPagedReKeeps(page, size)
    val resF = for {
      count <- countF
      paged <- pagedF
    } yield {
      val t = db.readOnlyMaster { implicit session =>
        paged map { k =>
          val rk = RichReKeep(k.id, k.createdAt, k.updatedAt, k.state, userRepo.get(k.keeperId), keepRepo.get(k.keepId), uriRepo.get(k.uriId), userRepo.get(k.srcUserId), keepRepo.get(k.srcKeepId), k.attributionFactor)
          val imgOpt = if (!showImage) None
          else keepImageCommander.getBestImageForKeep(k.keepId, ScaleImageRequest(ProcessedImageSize.Small.idealSize)).flatten.map(keepImageCommander.getUrl)
          (rk, imgOpt)
        }
      }
      (t, count)
    }
    resF map {
      case (t, count) =>
        Ok(html.admin.rekeeps(t, showImage, page, count, size))
    }
  }

  private def getKeepInfos(userId: Id[User]): (User, Seq[RichKeepDiscovery], Seq[RichReKeep], Seq[RichReKeep]) = {
    db.readOnlyMaster { implicit ro =>
      val u = userRepo.get(userId)
      // todo(ray): re-work admin api
      val rc = Seq.empty[RichKeepDiscovery] // keepDiscoveryRepo.getDiscoveriesByKeeper
      val rk = Seq.empty[RichReKeep] // rekeepRepo.getAllReKeepsByKeeper
      val rkr = Seq.empty[RichReKeep] // rekeepRepo.getAllReKeepsByReKeeper
      (u, rc, rk, rkr)
    }
  }

  def keepInfos(userId: Id[User]) = AdminUserPage { implicit request =>
    val (u, clicks, rekeeps, rekepts) = getKeepInfos(userId)
    Ok(html.admin.myKeepInfos(u, clicks, rekeeps, rekepts))
  }

  val userReKeepsReqConsolidator = new RequestConsolidator[(Id[User], Int), (User, Int, Seq[ReKeep], Seq[(Keep, Seq[Seq[User]])], Seq[(Int, Int)])](ttl = 5 seconds)
  def getReKeepInfos(userId: Id[User], n: Int = 4) = userReKeepsReqConsolidator(userId, n) {
    case (userId, n) => {
      val u = db.readOnlyMaster { implicit ro => userRepo.get(userId) }
      val rekeeps = Seq.empty[ReKeep] // rekeepRepo.getAllReKeepsByKeeper(userId)
      val grouped = rekeeps.groupBy(_.keepId)
      val sorted = grouped.toSeq.sortBy(_._2.length)(Ordering[Int].reverse).take(10)
      // todo(ray): batch
      val resF = sorted.map(_._1).map { keepId =>
        heimdalClient.getReKeepsByDegree(userId, keepId).map { res => res.map(_.userIds) } map { userIds =>
          val users = db.readOnlyMaster { implicit ro => userRepo.getUsers(userIds.foldLeft(Seq.empty[Id[User]]) { (a, c) => a ++ c }) }
          db.readOnlyMaster { implicit ro => keepRepo.get(keepId) } -> userIds.map(_.map(uId => users(uId)))
        }
      }
      Future.sequence(resF) flatMap { users =>
        val countsF = Future.sequence(users map {
          case (keep, _) =>
            heimdalClient.getReKeepCountsByUserUri(userId, keep.uriId)
        })
        countsF map { counts =>
          (u, n, rekeeps, users, counts)
        }
      }
    }
  }

  def reKeepInfos(userId: Id[User]) = AdminUserPage.async { implicit request =>
    getReKeepInfos(userId) map {
      case (u, n, rekeeps, users, counts) =>
        Ok(html.admin.myReKeeps(u, n, users zip counts map { case ((keep, users), counts) => (keep, counts._1, counts._2, users) })) // sanity check
    }
  }

  def myReKeeps() = AdminUserPage.async { implicit request =>
    getReKeepInfos(request.userId) map {
      case (u, n, rekeeps, users, counts) =>
        Ok(html.admin.myReKeeps(u, n, users map { case (keep, users) => (keep, users(1).size, users.flatten.length - 1, users) }))
    }
  }

  def myKeepInfos() = AdminUserPage { implicit request =>
    val (u, clicks, rekeeps, rekepts) = getKeepInfos(request.userId)
    Ok(html.admin.myKeepInfos(u, clicks, rekeeps, rekepts))
  }

  val topReKeepsReqConsolidator = new RequestConsolidator[Int, Seq[(NormalizedURI, Seq[(Keep, Seq[Seq[User]])])]](ttl = 5 seconds)
  def getTopReKeeps(degree: Int) = topReKeepsReqConsolidator(degree) { degree =>
    val rkMap = Map.empty[Id[Keep], Int] // rekeepRepo.getAllDirectReKeepCountsByKeep()
    val filtered = rkMap.toSeq.sortBy(_._2)(Ordering[Int].reverse).take(10).toMap

    val keepIds = filtered.keySet.toSeq.map { keepId =>
      val k = db.readOnlyMaster { implicit s => keepRepo.get(keepId) }
      KeepIdInfo(k.id.get, k.uriId, k.userId)
    }
    heimdalClient.getUserReKeepsByDegree(keepIds) map { userReKeepsAcc =>
      val sorted = userReKeepsAcc.sortBy(_.userIds.flatten.length)(Ordering[Int].reverse)
      val userIds = sorted.map(_.userIds).flatten.foldLeft(Set.empty[Id[User]]) { (a, c) => a ++ c }
      val users = db.readOnlyMaster { implicit ro => userRepo.getUsers(userIds.toSeq) }
      val richByDeg = sorted.map {
        case UserReKeepsAcc(kId, userIdsByDeg) =>
          db.readOnlyMaster { implicit ro => keepRepo.get(kId) -> userIdsByDeg.map(_.map(uId => users(uId))) }
      }
      val grouped = richByDeg.groupBy(_._1.uriId).map {
        case (uriId, keepsAndUsers) =>
          db.readOnlyReplica { implicit ro => uriRepo.get(uriId) -> keepsAndUsers }
      }.toSeq.sortBy {
        case (uri, keepsAndUsers) => keepsAndUsers.map {
          _._2.flatten
        }.length
      }(Ordering[Int].reverse)
      log.info(s"getTopReKeeps($degree)=$grouped")
      grouped
    }
  }

  def topReKeeps(degree: Int) = AdminUserPage.async { implicit request =>
    getTopReKeeps(degree) map { grouped =>
      Ok(html.admin.topReKeeps(degree, grouped))
    }
  }

  def updateReKeepStats() = AdminUserPage.async { request =>
    heimdalClient.updateUserReKeepStats(request.userId) map { _ =>
      Ok(s"Update request sent for userId=${request.userId}")
    }
  }

  def updateUserReKeepStats() = AdminUserPage.async(parse.tolerantJson) { request =>
    Json.fromJson[Id[User]](request.body).asOpt map { userId =>
      heimdalClient.updateUserReKeepStats(userId) map { _ =>
        Ok(s"Update request sent for userId=${userId}")
      }
    } getOrElse Future.successful(BadRequest(s"Illegal argument"))
  }

  def updateUsersReKeepStats() = AdminUserPage.async(parse.tolerantJson) { request =>
    Json.fromJson[Seq[Id[User]]](request.body).asOpt map { userIds =>
      heimdalClient.updateUsersReKeepStats(userIds) map { _ =>
        Ok(s"Update request sent for ${userIds.length} users (${userIds.take(5).mkString(",")} ... )")
      }
    } getOrElse Future.successful(BadRequest(s"Illegal argument"))
  }

  def updateAllReKeepStats() = AdminUserPage.async { request =>
    heimdalClient.updateAllReKeepStats() map { _ =>
      Ok(s"Update request sent for all users")
    }
  }

}
