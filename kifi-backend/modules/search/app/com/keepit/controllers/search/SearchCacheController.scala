package com.keepit.controllers.search

import com.google.inject.Inject
import play.api.mvc.Action

import com.keepit.common.controller.SearchServiceController
import com.keepit.search._

class SearchCacheController @Inject() (
  s3BackedResultClickTrackerBuffer: S3BackedResultClickTrackerBuffer)
    extends SearchServiceController {

  def warmResultClick() = Action{ request =>
    try {
      s3BackedResultClickTrackerBuffer.warmCache()
      Ok("Cache Warmed up successfully.")
    }
    catch {
      case e: Throwable => Ok(s"Failed to warm up cache. ($e.toString)")
    }
    
  }

}
