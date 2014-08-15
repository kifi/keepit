package com.keepit.graph.model

import com.keepit.common.db.Id
import com.keepit.model.{ NormalizedURI, Keep }
import play.api.libs.json._

case class GraphFeedExplanation(keepScores: Map[Id[Keep], Int], uriScores: Map[Id[NormalizedURI], Int])

object GraphFeedExplanation {
  import com.keepit.graph.wander.Collisions.idMapFormat
  implicit val format = Json.format[GraphFeedExplanation]
}

