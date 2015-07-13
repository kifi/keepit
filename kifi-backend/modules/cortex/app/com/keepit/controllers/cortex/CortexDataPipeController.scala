package com.keepit.controllers.cortex

import com.google.inject.Inject
import com.keepit.cortex.article.BasicCortexArticle
import com.keepit.rover.RoverServiceClient
import com.keepit.search.Lang
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.model.NormalizedURI
import com.keepit.common.commanders.FeatureRetrievalCommander
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex.ModelVersions
import play.api.mvc.Action
import com.keepit.cortex.core.ModelVersion

import play.api.libs.concurrent.Execution.Implicits._

class CortexDataPipeController @Inject() (
    rover: RoverServiceClient,
    featureCommander: FeatureRetrievalCommander) extends CortexServiceController {

  def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action { request =>

    val publishedLDAVersion = ModelVersions.defaultLDAVersion
    require(modelVersion <= publishedLDAVersion, s"Version $modelVersion of LDA has not been published yet.")
    val lowUriSeq = if (modelVersion < publishedLDAVersion) SequenceNumber.ZERO[NormalizedURI] else seqNum

    val sparseFeatures = featureCommander.getSparseLDAFeaturesChanged(lowUriSeq, fetchSize, publishedLDAVersion)

    val json = Json.obj(
      "modelVersion" -> publishedLDAVersion,
      "features" -> sparseFeatures
    )
    Ok(json)
  }

  // build a training corpus from here ... call it with a script which sends uriIds in mini-batches.
  def dumpPlainTxts = Action.async(parse.json) { request =>
    val uriIds = request.body.as[Set[Id[NormalizedURI]]]
    rover.getBestArticlesByUris(uriIds).map { articleByIds =>
      val ret: Map[Id[NormalizedURI], String] = {
        articleByIds.collect { case (uid, articles) if articles.nonEmpty => (uid, BasicCortexArticle.fromRoverArticles(articles)) }
          .filter(_._2.contentLang == Some(Lang("en")))
          .mapValues(_.content)
      }

      Ok(Json.toJson(ret))
    }
  }
}
