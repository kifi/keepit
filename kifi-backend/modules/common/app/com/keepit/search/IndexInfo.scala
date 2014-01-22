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

object IndexInfo {
  implicit val indexInfoFormat = Json.format[IndexInfo]
}

case class SharingUserInfo(
  sharingUserIds: Set[Id[User]],
  keepersEdgeSetSize: Int)

object SharingUserInfo {
  private implicit val userIdFormat = Id.format[User]
  implicit val sharingUserInfoFormat = Json.format[SharingUserInfo]
}
