package com.keepit.serializer

import play.api.libs.json._
import com.keepit.search.SearchConfigExperiment


class SearchConfigExperimentSerializer extends Format[SearchConfigExperiment]{
  import com.keepit.search.SearchConfigExperiment._
  def writes(experiment: SearchConfigExperiment): JsValue = Json.toJson(experiment)
  def reads(json: JsValue): JsResult[SearchConfigExperiment] = Json.fromJson[SearchConfigExperiment](json)
}

object SearchConfigExperimentSerializer {
  implicit val serializer = new SearchConfigExperimentSerializer
}