package com.keepit.controllers.search

import com.keepit.common.db.SequenceNumber
import play.api.libs.json._

case class IndexInfo(
  name: String,
  sequenceNumber: Option[SequenceNumber],
  numDocs: Int,
  committedAt: Option[String]
)

object IndexInfoJson {
  implicit val indexInfoFormat = Json.format[IndexInfo]
}