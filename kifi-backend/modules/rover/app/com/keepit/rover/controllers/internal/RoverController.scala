package com.keepit.rover.controllers.internal

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.RoverServiceController
import com.keepit.common.db.{ State, Id, SequenceNumber }
import com.keepit.common.json.TupleFormat
import com.keepit.common.logging.Logging
import com.keepit.model.{ NormalizedURI, IndexableUri }
import com.keepit.rover.RoverCommander
import com.keepit.rover.article.{ ArticleKind, ArticleCommander, Article }
import com.keepit.rover.model.{ BasicImage, RoverArticleSummary, ArticleInfo }
import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext }

class RoverController @Inject() (roverCommander: RoverCommander, articleCommander: ArticleCommander, implicit val executionContext: ExecutionContext) extends RoverServiceController with Logging {

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int) = Action.async { request =>
    roverCommander.getShoeboxUpdates(seq, limit).map { updates =>
      val json = Json.toJson(updates)
      Ok(json)
    }
  }

  def fetchAsap() = Action.async(parse.json) { request =>
    val uriId = (request.body \ "uriId").asOpt[Id[NormalizedURI]] getOrElse (request.body \ "id").as[Id[NormalizedURI]]
    val url = (request.body \ "url").as[String]
    val state = (request.body \ "state").as[State[NormalizedURI]]
    articleCommander.fetchAsap(uriId, url, state).map(_ => Ok)
  }

  def getBestArticlesByUris() = Action.async(parse.json) { request =>
    val uriIds = request.body.as[Set[Id[NormalizedURI]]]
    articleCommander.getBestArticlesByUris(uriIds).map { articlesByUris =>
      implicit val writes = TupleFormat.tuple2Writes[Id[NormalizedURI], Set[Article]]
      val json = Json.toJson(articlesByUris.toSeq)
      Ok(json)
    }
  }

  def getArticleInfosByUris() = Action(parse.json) { request =>
    val uriIds = request.body.as[Set[Id[NormalizedURI]]]
    val articleInfosByUris = articleCommander.getArticleInfosByUris(uriIds)
    implicit val writes = TupleFormat.tuple2Writes[Id[NormalizedURI], Set[ArticleInfo]]
    val json = Json.toJson(articleInfosByUris.toSeq)
    Ok(json)
  }

  def getBestArticleSummaryByUris() = Action.async(parse.json) { request =>
    val uriIds = (request.body \ "uriIds").as[Set[Id[NormalizedURI]]]
    val kind = (request.body \ "kind").as[ArticleKind[_ <: Article]]
    roverCommander.getBestArticleSummaryByUris(uriIds)(kind).imap { articleSummaryByUri =>
      implicit val writes = TupleFormat.tuple2Writes[Id[NormalizedURI], RoverArticleSummary]
      val json = Json.toJson(articleSummaryByUri.toSeq)
      Ok(json)
    }
  }

  def getImagesByUris() = Action.async(parse.json) { request =>
    val uriIds = (request.body \ "uriIds").as[Set[Id[NormalizedURI]]]
    val kind = (request.body \ "kind").as[ArticleKind[_ <: Article]]
    SafeFuture { roverCommander.getImagesByUris(uriIds)(kind) } imap { imagesByUris =>
      implicit val writes = TupleFormat.tuple2Writes[Id[NormalizedURI], Set[BasicImage]]
      val json = Json.toJson(imagesByUris.toSeq)
      Ok(json)
    }
  }

  def getOrElseFetchArticleSummaryAndImages = Action.async(parse.json) { request =>
    val uriId = (request.body \ "uriId").asOpt[Id[NormalizedURI]] getOrElse (request.body \ "id").as[Id[NormalizedURI]]
    val url = (request.body \ "url").as[String]
    val kind = (request.body \ "kind").as[ArticleKind[_ <: Article]]
    roverCommander.getOrElseFetchArticleSummaryAndImages(uriId, url)(kind).map { articleSummaryAndImagesOption =>
      implicit val writes = TupleFormat.tuple2Writes[RoverArticleSummary, Set[BasicImage]]
      val json = Json.toJson(articleSummaryAndImagesOption)
      Ok(json)
    }
  }
}
