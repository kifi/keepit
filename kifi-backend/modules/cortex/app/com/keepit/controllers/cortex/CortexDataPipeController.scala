package com.keepit.controllers.cortex

import com.google.inject.Inject
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.model.NormalizedURI
import com.keepit.common.commanders.FeatureRetrievalCommander
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex.ModelVersions
import play.api.mvc.Action
import com.keepit.cortex.core.ModelVersion

class CortexDataPipeController @Inject() (
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
}
