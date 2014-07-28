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

  def getAdultRestrictionOfURIs(uris: Seq[Id[NormalizedURI]]) = Action.async { request =>
    normalizedURICommander.getAdultRestrictionOfURIs(uris).map { res =>
      Ok(Json.toJson(res))
    }
  }

}
