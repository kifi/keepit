package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.logging.{ Logging, AccessLog }

case class KeepExport(
  createdAt: DateTime,
  title: Option[String] = None,
  url: String,
  tags: Option[String] = None)
