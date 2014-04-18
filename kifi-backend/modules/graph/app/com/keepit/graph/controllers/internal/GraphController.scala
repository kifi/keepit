package com.keepit.graph.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.GraphServiceController
import com.keepit.common.logging.Logging
import com.keepit.graph.manager.GraphManager

class GraphController @Inject() (
  graphManager: GraphManager
) extends GraphServiceController with Logging {

}
