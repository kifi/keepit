package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.common.time.currentDateTime
import com.keepit.common.cache._
import scala.concurrent.duration._

case class Comment(
  id: Option[Id[Comment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Comment] = ExternalId(),
  uriId: Id[NormalizedURI],
  urlId: Option[Id[URL]] = None, // todo(Andrew): remove Option after grandfathering process
  userId: Id[User],
  text: LargeString,
  pageTitle: String,
  parent: Option[Id[Comment]] = None,
  permissions: State[CommentPermission] = CommentPermissions.PUBLIC,
  state: State[Comment] = CommentStates.ACTIVE,
  seq: SequenceNumber = SequenceNumber.ZERO
) extends ModelWithExternalId[Comment] {
  def withId(id: Id[Comment]): Comment = copy(id = Some(id))
  def withUpdateTime(now: DateTime): Comment = copy(updatedAt = now)
  def withState(state: State[Comment]): Comment = copy(state = state)
  def withUrlId(urlId: Id[URL]): Comment = copy(urlId = Some(urlId))
  def withNormUriId(normUriId: Id[NormalizedURI]): Comment = copy(uriId = normUriId)
  def isActive: Boolean = state == CommentStates.ACTIVE
}

object Comment {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import com.keepit.common.db.Id

  implicit val userExternalIdFormat = ExternalId.format[User]
  implicit val commentExternalIdFormat = ExternalId.format[Comment]
  implicit val idFormat = Id.format[Comment]

  implicit val commentFormat = (
      (__ \ 'id).formatNullable(Id.format[Comment]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'externalId).format(ExternalId.format[Comment]) and
      (__ \ 'uriId).format(Id.format[NormalizedURI]) and
      (__ \ 'urlId).formatNullable(Id.format[URL]) and
      (__ \ 'userId).format(Id.format[User]) and
      (__ \ 'text).format(LargeString.format) and
      (__ \ 'pageTitle).format[String] and
      (__ \ 'parent).formatNullable(Id.format[Comment]) and
      (__ \ 'permissions).format(State.format[CommentPermission]) and
      (__ \ 'state).format(State.format[Comment]) and
      (__ \ 'seq).format(SequenceNumber.sequenceNumberFormat)
  )(Comment.apply, unlift(Comment.unapply))
}

case class CommentCountUriIdKey(normUriId: Id[NormalizedURI]) extends Key[Int] {
  override val version = 2
  val namespace = "comment_by_normuriid"
  def toKey(): String = normUriId.id.toString
}
class CommentCountUriIdCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[CommentCountUriIdKey, Int](innermostPluginSettings, innerToOuterPluginSettings:_*)

object CommentStates extends States[Comment]

sealed trait CommentPermission

object CommentPermissions {
  val PRIVATE = State[CommentPermission]("private")
  val MESSAGE = State[CommentPermission]("message")
  val PUBLIC  = State[CommentPermission]("public")
}
