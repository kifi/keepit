package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.{ Id, Model, State, States }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class OrganizationAvatar(
    id: Option[Id[OrganizationAvatar]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationAvatar] = OrganizationAvatarStates.ACTIVE,
    organizationId: Id[Organization],
    width: Int,
    height: Int,
    format: ImageFormat,
    kind: ProcessImageOperation,
    imagePath: ImagePath,
    source: ImageSource,
    sourceFileHash: ImageHash,
    sourceImageURL: Option[String]) extends BaseImage with Model[OrganizationAvatar] {
  def isOriginal = kind == ProcessImageOperation.Original

  def dimensions = ImageSize(width, height)
  def withId(id: Id[OrganizationAvatar]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationAvatar]) = this.copy(state = newState)
}

object OrganizationAvatar {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationAvatar]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[OrganizationAvatar]) and
    (__ \ 'organizationId).format(Id.format[Organization]) and
    (__ \ 'width).format[Int] and
    (__ \ 'height).format[Int] and
    (__ \ 'format).format[ImageFormat] and
    (__ \ 'kind).format[ProcessImageOperation] and
    (__ \ 'imagePath).format[ImagePath] and
    (__ \ 'source).format[ImageSource] and
    (__ \ 'sourceFileHash).format[ImageHash] and
    (__ \ 'sourceImageURL).formatNullable[String]
  )(OrganizationAvatar.apply, unlift(OrganizationAvatar.unapply))
}

object OrganizationAvatarStates extends States[OrganizationAvatar]

case class OrganizationAvatarKey(orgId: Id[Organization]) extends Key[Set[OrganizationAvatar]] {
  override val version = 2
  val namespace = "organization_avatar"
  def toKey(): String = orgId.toString
}

class OrganizationAvatarCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrganizationAvatarKey, Set[OrganizationAvatar]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
