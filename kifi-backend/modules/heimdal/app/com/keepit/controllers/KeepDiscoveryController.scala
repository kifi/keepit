package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.model.helprank.KeepDiscoveryRepo
import play.api.libs.json.Json
import play.api.mvc.Action

class KeepDiscoveryController @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    keepDiscoveryRepo: KeepDiscoveryRepo) extends HeimdalServiceController {

  def count() = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      keepDiscoveryRepo.count
    }
    Ok(Json.toJson(res))
  }

  def page(page: Int, size: Int) = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      keepDiscoveryRepo.page(page, size)
    }
    Ok(Json.toJson(res))
  }

  def getDiscoveryCountByKeeper(userId: Id[User]) = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      keepDiscoveryRepo.getDiscoveryCountByKeeper(userId)
    }
    Ok(Json.toJson(res))
  }

  def getDiscoveryCountsByURIs() = Action(parse.tolerantJson) { request =>
    val uriIds = Json.fromJson[Seq[Id[NormalizedURI]]](request.body).get
    val res = db.readOnlyMaster { implicit session =>
      keepDiscoveryRepo.getDiscoveryCountsByURIs(uriIds.toSet)
    }
    val counts = res.toSeq.map {
      case (uriId, count) =>
        URIDiscoveryCount(uriId, count)
    }
    Ok(Json.toJson(counts))
  }

  def getUriDiscoveriesWithCountsByKeeper(userId: Id[User]) = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      keepDiscoveryRepo.getUriDiscoveriesWithCountsByKeeper(userId)
    }
    val counts = res map {
      case (uriId, keepId, userId, discCount) =>
        URIDiscoveryCount(uriId, discCount)
    }
    Ok(Json.toJson(counts))
  }

  def getDiscoveryCountsByKeepIds() = Action(parse.tolerantJson) { request =>
    val json = request.body
    val userId = (json \ "userId").as[Id[User]]
    val keepIds = (json \ "keepIds").as[Seq[Id[Keep]]]
    val res = db.readOnlyMaster { implicit session =>
      keepDiscoveryRepo.getDiscoveryCountsByKeepIds(userId, keepIds.toSet)
    }
    val counts = res.toSeq.map {
      case (keepId, count) =>
        KeepDiscoveryCount(keepId, count)
    }
    Ok(Json.toJson(counts))
  }

}
