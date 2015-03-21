package com.keepit.model

import com.keepit.common.time._
import com.keepit.commanders.BasicCollection
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.ExternalId
import com.keepit.common.json.TupleFormat
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json.{ JsNull, Json, Writes }

case class KeepInfo(
  id: Option[ExternalId[Keep]] = None,
  title: Option[String],
  url: String,
  isPrivate: Boolean, // deprecated
  createdAt: Option[DateTime] = None,
  others: Option[Int] = None, // deprecated
  keeps: Option[Set[BasicKeep]] = None,
  keepers: Option[Seq[BasicUser]] = None,
  keepersOmitted: Option[Int] = None,
  keepersTotal: Option[Int] = None,
  libraries: Option[Seq[(BasicLibrary, BasicUser)]] = None,
  librariesOmitted: Option[Int] = None,
  librariesTotal: Option[Int] = None,
  collections: Option[Set[String]] = None, // deprecated
  tags: Option[Set[BasicCollection]] = None, // deprecated
  hashtags: Option[Set[Hashtag]] = None,
  summary: Option[URISummary] = None,
  siteName: Option[String] = None,
  libraryId: Option[PublicId[Library]] = None,
  sourceAttribution: Option[KeepSourceAttribution] = None,
  note: Option[String] = None)

object KeepInfo {

  val maxKeepersShown = 20
  val maxLibrariesShown = 10

  implicit val writes = {
    implicit val libraryWrites = Writes[BasicLibrary] { library =>
      Json.obj("id" -> library.id, "name" -> library.name, "path" -> library.path, "visibility" -> library.visibility, "color" -> library.color, "secret" -> library.isSecret) //todo(Léo): remove secret field
    }
    implicit val libraryWithContributorWrites = TupleFormat.tuple2Writes[BasicLibrary, BasicUser]
    Json.writes[KeepInfo]
  }

  // Are you looking for a decorated keep (with tags, rekeepers, etc)?
  // Use KeepsCommander#decorateKeepsIntoKeepInfos(userId, keeps)
  def fromKeep(bookmark: Keep)(implicit publicIdConfig: PublicIdConfiguration): KeepInfo = {
    KeepInfo(Some(bookmark.externalId), bookmark.title, bookmark.url, bookmark.isPrivate, libraryId = bookmark.libraryId.map(Library.publicId))
  }
}
