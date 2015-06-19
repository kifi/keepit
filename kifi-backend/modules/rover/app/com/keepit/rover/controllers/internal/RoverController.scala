package com.keepit.rover.controllers.internal

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.RoverServiceController
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.json.TupleFormat
import com.keepit.common.logging.Logging
import com.keepit.model.{ Library, NormalizedURI }
import com.keepit.rover.RoverCommander
import com.keepit.rover.article.{ ArticleKind, ArticleCommander, Article }
import com.keepit.rover.model.{ BasicImages, RoverArticleSummary, ArticleInfo }
import com.keepit.rover.tagcloud.TagCloudCommander
import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.common.core._
import com.keepit.common.time._

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext }

class RoverController @Inject() (
    roverCommander: RoverCommander,
    articleCommander: ArticleCommander,
    tagCloudCommander: TagCloudCommander,
    implicit val executionContext: ExecutionContext) extends RoverServiceController with Logging {

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int) = Action.async { request =>
    roverCommander.getShoeboxUpdates(seq, limit).map { updates =>
      val json = Json.toJson(updates)
      Ok(json)
    }
  }

  def fetchAsap() = Action.async(parse.json) { request =>
    val uriId = (request.body \ "uriId").asOpt[Id[NormalizedURI]] getOrElse (request.body \ "id").as[Id[NormalizedURI]]
    val url = (request.body \ "url").as[String]
    val refresh = (request.body \ "refresh").asOpt[Boolean] getOrElse false
    articleCommander.fetchAsap(uriId, url, refresh).map(_ => Ok)
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
      implicit val writes = TupleFormat.tuple2Writes[Id[NormalizedURI], BasicImages]
      val json = Json.toJson(imagesByUris.toSeq)
      Ok(json)
    }
  }

  def getOrElseFetchArticleSummaryAndImages = Action.async(parse.json) { request =>
    val uriId = (request.body \ "uriId").asOpt[Id[NormalizedURI]] getOrElse (request.body \ "id").as[Id[NormalizedURI]]
    val url = (request.body \ "url").as[String]
    val kind = (request.body \ "kind").as[ArticleKind[_ <: Article]]
    roverCommander.getOrElseFetchArticleSummaryAndImages(uriId, url)(kind).map { articleSummaryAndImagesOption =>
      implicit val writes = TupleFormat.tuple2Writes[RoverArticleSummary, BasicImages]
      val json = Json.toJson(articleSummaryAndImagesOption)
      Ok(json)
    }
  }

  def getOrElseFetchRecentArticle = Action.async(parse.json) { request =>
    val url = (request.body \ "url").as[String]
    val kind = (request.body \ "kind").as[ArticleKind[_ <: Article]]
    val recency = (request.body \ "recency").as[Duration]
    articleCommander.getOrElseFetchRecentArticle(url, recency)(kind).map { articleOpt =>
      implicit val format = kind.format
      val json = Json.toJson(articleOpt)
      Ok(json)
    }
  }

  def getOrElseComputeRecentContentSignature = Action.async(parse.json) { request =>
    val url = (request.body \ "url").as[String]
    val kind = (request.body \ "kind").as[ArticleKind[_ <: Article]]
    val recency = (request.body \ "recency").as[Duration]
    roverCommander.getOrElseComputeRecentContentSignature(url, recency)(kind).map { signatureOpt =>
      val json = Json.toJson(signatureOpt)
      Ok(json)
    }
  }

  def dumpTagCloud(libId: Id[Library], experiment: Boolean) = Action.async { request =>
    tagCloudCommander.generateTagCloud(libId, experiment).map { res => Ok(Json.toJson(res)) }
  }
}
