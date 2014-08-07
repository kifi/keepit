package com.keepit.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.NormalizedURICommander
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.NormalizedURI
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits._

@Singleton
class NormalizedURIController @Inject() (
    db: Database,
    normalizedURICommander: NormalizedURICommander) extends ShoeboxServiceController {

  def getAdultRestrictionOfURIs() = Action.async { request =>
    val body = request.body.asJson match {
      case Some(json) => json.as[Seq[Id[NormalizedURI]]]
      case None => Seq.empty
    }
    normalizedURICommander.getCandidateURIs(body).map { res =>
      Ok(Json.toJson(res))
    }
  }

}
