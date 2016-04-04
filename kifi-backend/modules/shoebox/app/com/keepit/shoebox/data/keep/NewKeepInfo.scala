package com.keepit.shoebox.data.keep

import com.keepit.common.crypto.PublicId
import com.keepit.common.mail.EmailAddress
import com.keepit.common.path.Path
import com.keepit.common.store.{ ImageSize, ImagePath }
import com.keepit.model._
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.social.{ BasicNonUser, BasicAuthor, BasicUser }
import org.joda.time.DateTime
import play.api.libs.json._

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
  activity: Option[KeepActivity],
  viewer: NewKeepViewerInfo)

object NewKeepInfo {
  private implicit val timeFormat: Writes[DateTime] = Writes { dt => DateTimeJsonFormat.writes(dt) }
  private implicit val sourceWrites = SourceAttribution.externalWrites
  implicit val writes: Writes[NewKeepInfo] = Json.writes[NewKeepInfo]
}

final case class NewKeepImageInfo(
  path: ImagePath,
  dimensions: ImageSize)

object NewKeepImageInfo {
  implicit val writes: Writes[NewKeepImageInfo] = Json.writes[NewKeepImageInfo]
}

final case class KeepRecipientsInfo(
  users: Seq[BasicUser],
  emails: Seq[EmailAddress],
  libraries: Seq[BasicLibrary])

object KeepRecipientsInfo {
  implicit val writes: Writes[KeepRecipientsInfo] = Json.writes[KeepRecipientsInfo]
}

case class NewKeepViewerInfo(
  permissions: Set[KeepPermission])

object NewKeepViewerInfo {
  implicit val writes: Writes[NewKeepViewerInfo] = Json.writes[NewKeepViewerInfo]
}
