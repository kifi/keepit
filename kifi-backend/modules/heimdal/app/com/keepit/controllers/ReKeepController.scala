package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.model.helprank.ReKeepRepo
import play.api.libs.json.Json
import play.api.mvc.Action

class ReKeepController @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    rekeepRepo: ReKeepRepo) extends HeimdalServiceController {

  def count() = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      rekeepRepo.count
    }
    Ok(Json.toJson(res))
  }

  def page(page: Int, size: Int) = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      rekeepRepo.page(page, size)
    }
    Ok(Json.toJson(res))
  }

  def getUriReKeepsWithCountsByKeeper(userId: Id[User]) = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      rekeepRepo.getUriReKeepsWithCountsByKeeper(userId)
    }
    val counts = res map { case (uriId, _, _, count) => URIReKeepCount(uriId, count) }
    Ok(Json.toJson(counts))
  }

  def getReKeepCountsByURIs() = Action(parse.tolerantJson) { request =>
    val uriIds = request.body.as[Seq[Id[NormalizedURI]]]
    val res = db.readOnlyMaster { implicit session =>
      rekeepRepo.getReKeepCountsByURIs(uriIds.toSet)
    }
    val counts = res.toSeq map { case (uriId, count) => URIReKeepCount(uriId, count) }
    Ok(Json.toJson(counts))
  }

  def getReKeepCountsByKeepIds() = Action(parse.tolerantJson) { request =>
    val json = request.body
    val userId = (json \ "userId").as[Id[User]]
    val keepIds = (json \ "keepIds").as[Seq[Id[Keep]]]
    val res = db.readOnlyMaster { implicit session =>
      rekeepRepo.getReKeepCountsByKeepIds(userId, keepIds.toSet)
    }
    val counts = res.toSeq map { case (keepId, count) => KeepReKeptCount(keepId, count) }
    Ok(Json.toJson(counts))
  }

}
