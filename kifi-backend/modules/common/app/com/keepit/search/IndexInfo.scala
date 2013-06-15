package com.keepit.search
import play.api.libs.json.Json
import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.model.{NormalizedURI, User}

case class IndexInfo(
  name: String,
  sequenceNumber: Option[SequenceNumber],
  numDocs: Int,
  committedAt: Option[String]
)

object IndexInfoJson {
  implicit val indexInfoFormat = Json.format[IndexInfo]
}

case class ResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean)

object ResultClickedJson {
  private implicit val uriIdFormat = Id.format[NormalizedURI]
  private implicit val userIdFormat = Id.format[User]
  implicit val resultClickedFormat = Json.format[ResultClicked]
}

case class SharingUserInfo(
                            sharingUserIds: Set[Id[User]],
                            keepersEdgeSetSize: Int)

object SharingUserInfoJson {
  private implicit val userIdFormat = Id.format[User]
  implicit val sharingUserInfoFormat = Json.format[SharingUserInfo]
}
