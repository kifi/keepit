package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.commanders.URISummaryCommander
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.model.{ ImageType, NormalizedURIRepo, URISummaryRequest, NormalizedURI }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.store.ImageSize
import scala.concurrent.Future

class URISummaryController @Inject() (
    val userActionsHelper: UserActionsHelper,
    uriSummaryCommander: URISummaryCommander,
    normalizedUriRepo: NormalizedURIRepo,
    db: Database) extends ShoeboxServiceController {

  def getUriImageForUriId(id: Id[NormalizedURI]) = Action.async { request =>
    val nUri = db.readOnlyMaster { implicit session => normalizedUriRepo.get(id) }
    val urlFut = uriSummaryCommander.getURIImage(nUri)
    urlFut map { urlOpt => Ok(Json.toJson(urlOpt)) }
  }
}
