package com.keepit.search

import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.User
import play.api.libs.json._
case class IndexInfo(
  name: String,
  sequenceNumber: SequenceNumber,
  numDocs: Int,
  committedAt: Option[String],
  indexSize: Option[Long]
)

case class ReadableIndexInfo(
  name: String,
  sequenceNumber: SequenceNumber,
  numDocs: Int,
  committedAt: Option[String],
  indexSize: Option[String]
)

object IndexInfo {
  implicit val indexInfoFormat = Json.format[IndexInfo]
  def toReadableIndexInfo(info: IndexInfo): ReadableIndexInfo = {
    val indexSize = info.indexSize.map{ n =>
      (n/1e3, n/1e6, n/1e9) match {
        case (_, _, gb) if gb.toInt > 0 => "%.2f".format(gb.toFloat) + "GB"
        case (_, mb, _) if mb.toInt > 0 => "%.2f".format(mb.toFloat) + "MB"
        case (kb, _, _) => "%.2f".format(kb.toFloat) + "KB"
      }
    }
    ReadableIndexInfo(info.name, info.sequenceNumber, info.numDocs, info.committedAt, indexSize)
  }
}

case class SharingUserInfo(
  sharingUserIds: Set[Id[User]],
  keepersEdgeSetSize: Int)

object SharingUserInfo {
  private implicit val userIdFormat = Id.format[User]
  implicit val sharingUserInfoFormat = Json.format[SharingUserInfo]
}
