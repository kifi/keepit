package com.keepit.rover.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.RoverServiceController
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.json.TupleFormat
import com.keepit.common.logging.Logging
import com.keepit.model.{ NormalizedURI, IndexableUri }
import com.keepit.rover.article.Article
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

  def fetchAsap() = Action.async(parse.json) { request =>
    val uri = request.body.as[IndexableUri]
    roverCommander.doMeAFavor(uri).map(_ => Ok)
  }

  def getArticlesByUris() = Action.async(parse.json) { request =>
    val uriIds = request.body.as[Set[Id[NormalizedURI]]]
    roverCommander.getArticlesByUris(uriIds).map { articlesByUris =>
      implicit val writes = TupleFormat.tuple2Writes[Id[NormalizedURI], Set[Article]]
      val json = Json.toJson(articlesByUris.toSeq)
      Ok(json)
    }
  }

}
