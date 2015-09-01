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
  user: Option[BasicUser], // The user to be shown as associated with this keep, esp. with notes
  createdAt: Option[DateTime] = None,
  keeps: Option[Set[PersonalKeep]] = None,
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
  libraryId: Option[PublicId[Library]] = None, // deprecated, use .library.id instead
  library: Option[BasicLibrary] = None,
  organization: Option[BasicOrganization] = None,
  sourceAttribution: Option[KeepSourceAttribution] = None,
  note: Option[String] = None)

object KeepInfo {

  val maxKeepersShown = 20
  val maxLibrariesShown = 10

  implicit val writes = {
    implicit val libraryWithContributorWrites = TupleFormat.tuple2Writes[BasicLibrary, BasicUser]
    new Writes[KeepInfo] {
      import com.keepit.common.core._
      def writes(o: KeepInfo) = Json.obj(
        "id" -> o.id,
        "title" -> o.title,
        "url" -> o.url,
        "isPrivate" -> o.isPrivate,
        "user" -> o.user,
        "createdAt" -> o.createdAt,
        "keeps" -> o.keeps,
        "keepers" -> o.keepers,
        "keepersOmitted" -> o.keepersOmitted,
        "keepersTotal" -> o.keepersTotal,
        "libraries" -> o.libraries,
        "librariesOmitted" -> o.librariesOmitted,
        "librariesTotal" -> o.librariesTotal,
        "collections" -> o.collections,
        "tags" -> o.tags,
        "hashtags" -> o.hashtags,
        "summary" -> o.summary,
        "siteName" -> o.siteName,
        "libraryId" -> o.libraryId,
        "library" -> o.library,
        "organization" -> o.organization,
        "sourceAttribution" -> o.sourceAttribution,
        "note" -> o.note
      ).nonNullFields
    }
  }

  // Are you looking for a decorated keep (with tags, rekeepers, etc)?
  // Use KeepsCommander#decorateKeepsIntoKeepInfos(userId, keeps)
  def fromKeep(bookmark: Keep)(implicit publicIdConfig: PublicIdConfiguration): KeepInfo = {
    KeepInfo(Some(bookmark.externalId), bookmark.title, bookmark.url, bookmark.isPrivate, user = None, libraryId = bookmark.libraryId.map(Library.publicId))
  }
}
