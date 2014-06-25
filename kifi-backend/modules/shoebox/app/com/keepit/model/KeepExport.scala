package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.logging.{Logging, AccessLog}

case class KeepExport (
  created_at: DateTime,
  title: Option[String] = None,
  url: String, // denormalized for efficiency
  tags: Option[String] = None
)
