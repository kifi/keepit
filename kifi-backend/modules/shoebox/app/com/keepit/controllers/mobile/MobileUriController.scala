package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, ShoeboxServiceController, UserActions }
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.model.{ Restriction, NormalizedURIRepo }
import com.keepit.normalizer.NormalizedURIInterner
import play.api.libs.json.Json

class MobileUriController @Inject() (
    db: Database,
    normalizedUriRepo: NormalizedURIRepo,
    normalizedUriInterner: NormalizedURIInterner,
    clock: Clock,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController {

  def flagContent() = UserAction(parse.tolerantJson) { request =>
    val jsonBody = request.body
    val reason = (jsonBody \ "reason").as[String]
    val url = (jsonBody \ "url").as[String]

    db.readWrite { implicit s =>
      normalizedUriInterner.getByUri(url).map { uri =>
        reason match {
          case "adult" =>
            normalizedUriRepo.updateURIRestriction(uri.id.get, Some(Restriction.ADULT))
            NoContent
          case _ =>
            BadRequest(Json.obj("error" -> "reason_not_found"))
        }
      }
    }.getOrElse(BadRequest(Json.obj("error" -> "uri_not_found")))
  }
}
