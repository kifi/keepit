package com.keepit.shoebox.data.keep

import com.keepit.common.crypto.PublicId
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.path.Path
import com.keepit.common.store.{ ImageUrl, ImageSize, ImagePath }
import com.keepit.model._
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.social.{ BasicNonUser, BasicAuthor, BasicUser }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

final case class NewKeepInfo(
  id: PublicId[Keep],
  path: Path,
  url: String,
  title: Option[String],
  image: Option[NewKeepImageInfo],
  author: BasicAuthor,
  keptAt: DateTime,
  source: Option[SourceAttribution],
  recipients: KeepRecipientsInfo,
  activity: KeepActivity,
  viewer: NewKeepViewerInfo)

object NewKeepInfo {
  private implicit val timeFormat: Writes[DateTime] = Writes { dt => DateTimeJsonFormat.writes(dt) }
  private implicit val sourceWrites = SourceAttribution.externalWrites
  implicit val writes: Writes[NewKeepInfo] = Json.writes[NewKeepInfo]
}

final case class NewKeepImageInfo(
  url: ImageUrl,
  dimensions: ImageSize)

object NewKeepImageInfo {
  implicit val writes: Writes[NewKeepImageInfo] = Json.writes[NewKeepImageInfo]
}

final case class KeepRecipientsInfo(
  users: Seq[BasicUser],
  emails: Seq[BasicContact],
  libraries: Seq[BasicLibrary])

object KeepRecipientsInfo {
  private implicit val bastardizedBasicContactWrites: Writes[BasicContact] = Writes { bc => BasicContact.format.writes(bc) ++ Json.obj("id" -> bc.email) }
  implicit val writes: Writes[KeepRecipientsInfo] = (
    (__ \ 'users).write[Seq[BasicUser]] and
    (__ \ 'emails).write[Seq[BasicContact]] and
    (__ \ 'libraries).write[Seq[BasicLibrary]]
  )(unlift(KeepRecipientsInfo.unapply))
}

case class NewKeepViewerInfo(
  permissions: Set[KeepPermission])

object NewKeepViewerInfo {
  implicit val writes: Writes[NewKeepViewerInfo] = Json.writes[NewKeepViewerInfo]
}
