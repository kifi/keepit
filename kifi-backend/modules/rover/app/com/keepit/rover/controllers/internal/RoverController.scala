package com.keepit.rover.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.RoverServiceController
import com.keepit.common.db.SequenceNumber
import com.keepit.common.logging.Logging
import com.keepit.rover.commanders.RoverCommander
import com.keepit.rover.model.{ ShoeboxArticleUpdates, ArticleInfo }
import play.api.libs.json.Json
import play.api.mvc.Action

import scala.concurrent.{ ExecutionContext, Future }

class RoverController @Inject() (roverCommander: RoverCommander, private implicit val executionContext: ExecutionContext) extends RoverServiceController with Logging {

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int) = Action.async { request =>
    roverCommander.getShoeboxUpdates(seq, limit).map { updates =>
      val json = Json.toJson(updates)
      Ok(json)
    }
  }

}
