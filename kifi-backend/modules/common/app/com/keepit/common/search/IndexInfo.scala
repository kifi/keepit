package com.keepit.common.search
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.{NormalizedURI, User}
import play.api.libs.json.Json

case class IndexInfo(
  name: String,
  sequenceNumber: Option[SequenceNumber],
  numDocs: Int,
  committedAt: Option[String]
)

object IndexInfo {
  implicit val indexInfoFormat = Json.format[IndexInfo]
}

case class ResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean)

object ResultClicked {
  private implicit val uriIdFormat = Id.format[NormalizedURI]
  private implicit val userIdFormat = Id.format[User]
  implicit val resultClickedFormat = Json.format[ResultClicked]
}

case class SharingUserInfo(
  sharingUserIds: Set[Id[User]],
  keepersEdgeSetSize: Int)

object SharingUserInfo {
  private implicit val userIdFormat = Id.format[User]
  implicit val sharingUserInfoFormat = Json.format[SharingUserInfo]
}
