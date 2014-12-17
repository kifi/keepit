package com.keepit.search.index

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.User
import play.api.libs.json._
case class IndexInfo(
  name: String,
  sequenceNumber: Long,
  numDocs: Int,
  committedAt: Option[String],
  indexSize: Option[Long])

case class ReadableIndexInfo(
  name: String,
  sequenceNumber: Long,
  numDocs: Int,
  committedAt: Option[String],
  indexSize: Option[String])

object IndexInfo {
  implicit def indexInfoFormat = Json.format[IndexInfo]

  def toReadableSize(n: Long): String = {
    (n / 1e3, n / 1e6, n / 1e9) match {
      case (_, _, gb) if gb.toInt > 0 => "%.2f".format(gb.toFloat) + "GB"
      case (_, mb, _) if mb.toInt > 0 => "%.2f".format(mb.toFloat) + "MB"
      case (kb, _, _) => "%.2f".format(kb.toFloat) + "KB"
    }
  }

  def toReadableIndexInfo(info: IndexInfo): ReadableIndexInfo = {
    val indexSize = info.indexSize.map { toReadableSize(_) }
    ReadableIndexInfo(info.name, info.sequenceNumber, info.numDocs, info.committedAt, indexSize)
  }
}
