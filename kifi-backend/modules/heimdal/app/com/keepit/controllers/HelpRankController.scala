package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.commander.{ AttributionCommander, HelpRankCommander }
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.SearchHitReport
import com.keepit.model._
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class HelpRankController @Inject() (
    airbrake: AirbrakeNotifier,
    helprankCommander: HelpRankCommander,
    attributionCommander: AttributionCommander) extends HeimdalServiceController {

  def processSearchHitAttribution() = Action.async(parse.tolerantJson) { request =>
    val hit = request.body.as[SearchHitReport]
    helprankCommander.processSearchHitAttribution(hit) map { r =>
      Ok
    }
  }

  def processKeepAttribution() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    val userId = (json \ "userId").as(Id.format[User])
    val keeps = (json \ "keeps").as[Seq[Keep]]
    helprankCommander.processKeepAttribution(userId, keeps) map { r =>
      Ok
    }
  }

  def getHelpRankInfo() = Action.async(parse.tolerantJson) { request =>
    val uriIds = Json.fromJson[Seq[Id[NormalizedURI]]](request.body).get
    helprankCommander.getHelpRankInfo(uriIds) map { infos =>
      if (uriIds.length != infos.length) // debug
        log.warn(s"[getHelpRankInfo] (mismatch) uriIds(len=${uriIds.length}):${uriIds.mkString(",")} res(len=${infos.length}):${infos.mkString(",")}")
      Ok(Json.toJson(infos))
    }
  }

  def getKeepAttributionInfo(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(helprankCommander.getKeepAttributionInfo(userId)))
  }

  def getUserReKeepsByDegree() = Action(parse.tolerantJson) { request =>
    val keepIds = request.body.as[Seq[KeepIdInfo]]
    val res = attributionCommander.getUserReKeepsByDegree(keepIds).toSeq.map {
      case (keepId, userIds) => UserReKeepsAcc(keepId, userIds)
    }
    Ok(Json.toJson(res))
  }

  def getReKeepsByDegree(keeperId: Id[User], keepId: Id[Keep]) = Action { request =>
    val res = attributionCommander.getReKeepsByDegree(keeperId, keepId) map { t => ReKeepsPerDeg(keepId, t._1.toSeq, t._2.toSeq) }
    Ok(Json.toJson(res))
  }

  def updateUserReKeepStats() = Action.async(parse.tolerantJson) { request =>
    val userId = request.body.as[Id[User]]
    attributionCommander.updateUserReKeepStats(userId) map { _ =>
      Ok
    }
  }

  def updateUsersReKeepStats() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[Seq[Id[User]]]
    attributionCommander.updateUsersReKeepStats(userIds) // tell
    Ok
  }

  def updateAllReKeepStats() = Action { request =>
    attributionCommander.updateAllReKeepStats()
    Ok
  }

}
