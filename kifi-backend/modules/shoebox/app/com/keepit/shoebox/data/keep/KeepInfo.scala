package com.keepit.shoebox.data.keep

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.json.TupleFormat
import com.keepit.common.store.ImagePath
import com.keepit.common.time._
import com.keepit.discussion.{ Discussion, DiscussionKeep }
import com.keepit.model._
import com.keepit.social.{ BasicAuthor, BasicUser }
import org.joda.time.DateTime
import play.api.libs.json.{ JsObject, Json, OWrites, Writes }

case class BasicLibraryWithKeptAt(library: BasicLibrary, keptAt: DateTime)
object BasicLibraryWithKeptAt {
  implicit val writes: OWrites[BasicLibraryWithKeptAt] = OWrites[BasicLibraryWithKeptAt] {
    case BasicLibraryWithKeptAt(library, keptAt) =>
      BasicLibrary.format.writes(library).as[JsObject] + ("keptAt" -> Json.toJson(keptAt))
  }
}

case class KeepInfo(
    id: Option[ExternalId[Keep]] = None,
    pubId: Option[PublicId[Keep]] = None,
    title: Option[String],
    url: String,
    path: String,
    isPrivate: Boolean, // deprecated
    user: Option[BasicUser], // The user to be shown as associated with this keep, esp. with notes
    author: BasicAuthor,
    createdAt: Option[DateTime] = None,
    keeps: Option[Set[PersonalKeep]] = None,
    keepers: Option[Seq[BasicUser]] = None,
    keepersOmitted: Option[Int] = None,
    keepersTotal: Option[Int] = None,
    libraries: Option[Seq[(BasicLibraryWithKeptAt, BasicUser)]] = None,
    librariesOmitted: Option[Int] = None,
    librariesTotal: Option[Int] = None,
    sources: Option[Seq[SourceAttribution]] = None,
    collections: Option[Set[String]] = None, // deprecated
    tags: Option[Set[BasicCollection]] = None, // deprecated
    hashtags: Option[Set[Hashtag]] = None,
    summary: Option[URISummary] = None,
    siteName: Option[String] = None,
    libraryId: Option[PublicId[Library]] = None, // deprecated, use .library.id instead
    library: Option[LibraryCardInfo] = None,
    organization: Option[BasicOrganization] = None,
    sourceAttribution: Option[(SourceAttribution, Option[BasicUser])],
    note: Option[String] = None,
    discussion: Option[Discussion],
    activity: Option[KeepActivity], // todo(cam): make non-optional when removing activity_log experiment
    participants: Seq[BasicUser],
    members: KeepMembers, // Léo: this was Derek's idea.
    permissions: Set[KeepPermission]) {

  def asDiscussionKeep: DiscussionKeep = DiscussionKeep(
    id = pubId.get,
    url = url,
    path = path,
    title = title,
    note = note,
    tags = hashtags.getOrElse(Set.empty),
    keptBy = user,
    keptAt = createdAt.get,
    imagePath = summary.flatMap(_.imageUrl).map(ImagePath(_)),
    libraries = members.libraries.map(_.library).toSet,
    permissions = permissions
  )
}

object KeepInfo {
  val maxKeepsShown = 10
  val maxKeepersShown = 20
  val maxLibrariesShown = 10

  implicit val writes = {
    implicit val libraryWithContributorWrites = TupleFormat.tuple2Writes[BasicLibraryWithKeptAt, BasicUser]
    new Writes[KeepInfo] {
      import com.keepit.common.core._
      def writes(o: KeepInfo) = Json.obj(
        "author" -> o.author,
        "id" -> o.id,
        "pubId" -> o.pubId,
        "title" -> o.title,
        "url" -> o.url,
        "path" -> o.path,
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
        "sources" -> o.sources.map(_.map(SourceAttribution.externalWrites.writes(_))),
        "collections" -> o.collections,
        "tags" -> o.tags,
        "hashtags" -> o.hashtags,
        "summary" -> o.summary,
        "siteName" -> o.siteName,
        "libraryId" -> o.libraryId,
        "library" -> o.library,
        "organization" -> o.organization,
        "sourceAttribution" -> o.sourceAttribution.map(SourceAttribution.deprecatedWrites.writes(_)),
        "note" -> o.note,
        "discussion" -> o.discussion,
        "activity" -> o.activity,
        "participants" -> o.participants,
        "members" -> o.members,
        "permissions" -> o.permissions
      ).nonNullFields
    }
  }

  def fromKeep(bookmark: Keep)(implicit publicIdConfig: PublicIdConfiguration): KeepInfo = {
    KeepInfo(Some(bookmark.externalId), Some(Keep.publicId(bookmark.id.get)), bookmark.title, bookmark.url,
      bookmark.path.relativeWithLeadingSlash, false, user = None, author = BasicAuthor.Fake, libraryId = bookmark.lowestLibraryId.map(Library.publicId),
      sourceAttribution = None, discussion = None, activity = None, participants = Seq.empty, members = KeepMembers.empty, permissions = Set.empty)
  }
}
