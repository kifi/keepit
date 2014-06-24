package com.keepit.model

/**
 * Created by aaronhsu on 6/23/14.
 */

import scala.concurrent.duration._
import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.logging.{Logging, AccessLog}
import com.keepit.common.db._
import com.keepit.common.strings.StringWithNoLineBreaks
import com.keepit.common.time._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.heimdal.SanitizedKifiHit


case class KeepExport (
  created_at: DateTime,
  title: Option[String] = None,
  url: String, // denormalized for efficiency
  tags: Option[String] = None
)
