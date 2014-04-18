package com.keepit.graph.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.GraphServiceController
import com.keepit.common.logging.Logging
import com.keepit.graph.manager.GraphManagerPlugin

class GraphController @Inject() (
  graphPlugin: GraphManagerPlugin
) extends GraphServiceController with Logging {

}
