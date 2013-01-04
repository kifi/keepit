package com.keepit.model

import com.keepit.common.db.{ CX, Id, Entity, EntityTable, ExternalId, State }
import com.keepit.common.db.NotFoundException
import com.keepit.common.db.StateException
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.libs.json._
import ru.circumflex.orm._
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import com.keepit.common.logging.Logging
import play.api.mvc.QueryStringBindable
import play.api.mvc.JavascriptLitteral
import com.keepit.common.controller.FortyTwoServices
import com.keepit.inject.inject
import play.api.Play.current
import com.keepit.serializer.{NormalizedURIMetadataSerializer => NURIS}


case class DeepLinkToken(value: String)
object DeepLinkToken {
  def apply(): DeepLinkToken = DeepLinkToken(ExternalId().id) // use ExternalIds for now. Eventually, we may move off this.
}

case class DeepLocator(value: String)
object DeepLocator {
  def ofMessageThread(message: Comment) = DeepLocator("/messages/%s".format(message.externalId))
  def ofComment(comment: Comment) = DeepLocator("/comments/%s".format(comment.externalId))
  def ofSlider = DeepLocator("/default")
}

case class DeepLink(
  id: Option[Id[DeepLink]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  initatorUserId: Option[Id[User]],
  recipientUserId: Option[Id[User]],
  uriId: Option[Id[NormalizedURI]],
  uriData: Option[NormalizedURIMetadata] = None,
  deepLocator: DeepLocator,
  token: DeepLinkToken = DeepLinkToken(),
  state: State[DeepLink] = DeepLink.States.ACTIVE) extends Logging {
  lazy val baseUrl = inject[FortyTwoServices].baseUrl
  lazy val url = "%s/r/%s".format(baseUrl,token.value)

  def withUriData(uriData: NormalizedURIMetadata) = copy(uriData = Some(uriData))

  def save(implicit conn: Connection): DeepLink = {
    val entity = DeepLinkEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

}

object DeepLink {

  def all(implicit conn: Connection): Seq[DeepLink] =
    DeepLinkEntity.all.map(_.view)

  def all(userId: Id[User])(implicit conn: Connection): Seq[DeepLink] =
    (DeepLinkEntity AS "i").map { i => SELECT(i.*) FROM i WHERE (i.initatorUserId EQ userId) list }.map(_.view)

  def getOpt(id: Id[DeepLink])(implicit conn: Connection): Option[DeepLink] =
    DeepLinkEntity.get(id).map(_.view)

  def get(id: Id[DeepLink])(implicit conn: Connection): DeepLink =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(token: DeepLinkToken)(implicit conn: Connection): Option[DeepLink] =
    (DeepLinkEntity AS "i").map { i => SELECT(i.*) FROM i WHERE (i.token EQ token.value) unique }.map(_.view)

  object States {
    val ACTIVE = State[DeepLink]("active")
    val INACTIVE = State[DeepLink]("inactive")
  }

}

private[model] class DeepLinkEntity extends Entity[DeepLink, DeepLinkEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val initatorUserId = "initiator_user_id".ID[User]
  val recipientUserId = "recipient_user_id".ID[User]
  val uriId = "uri_id".ID[NormalizedURI]
  val uriData = "uri_data".VARCHAR(1024)
  val deepLocator = "deep_locator".VARCHAR(512).NOT_NULL
  val token = "token".VARCHAR(16).NOT_NULL
  val state = "state".STATE[DeepLink].NOT_NULL(DeepLink.States.ACTIVE)

  def relation = DeepLinkEntity

  def view(implicit conn: Connection): DeepLink = DeepLink(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    initatorUserId = initatorUserId.value,
    recipientUserId = recipientUserId.value,
    uriId = uriId.value,
    uriData = {
      try {
        val json = Json.parse(uriData.value.getOrElse("{}")) // after grandfathering, force having a value
        val serializer = NURIS.normalizedURIMetadataSerializer
        Some(serializer.reads(json))
      }
      catch {
        case ex: Throwable =>
          // after grandfathering process, throw error
          None
      }
    },
    deepLocator = DeepLocator(deepLocator()),
    token = DeepLinkToken(token()),
    state = state())
}

private[model] object DeepLinkEntity extends DeepLinkEntity with EntityTable[DeepLink, DeepLinkEntity] {
  override def relationName = "deep_link"

  def apply(view: DeepLink): DeepLinkEntity = {
    val uri = new DeepLinkEntity
    uri.id.set(view.id)
    uri.createdAt := view.createdAt
    uri.updatedAt := view.updatedAt
    uri.initatorUserId.set(view.initatorUserId)
    uri.recipientUserId.set(view.recipientUserId)
    uri.uriId.set(view.uriId)
    uri.uriData.set(view.uriData.map { m =>
      val serializer = NURIS.normalizedURIMetadataSerializer
      Json.stringify(serializer.writes(m))
    })
    uri.deepLocator := view.deepLocator.value
    uri.token := view.token.value
    uri.state := view.state
    uri
  }
}
