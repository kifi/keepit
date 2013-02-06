package com.keepit.model

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
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
  urlId: Option[Id[URL]] = None, // todo(Andrew): remove Option after grandfathering process
  deepLocator: DeepLocator,
  token: DeepLinkToken = DeepLinkToken(),
  state: State[DeepLink] = DeepLinkStates.ACTIVE
) extends Model[DeepLink] {
  def withId(id: Id[DeepLink]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  lazy val baseUrl = inject[FortyTwoServices].baseUrl
  lazy val url = "%s/r/%s".format(baseUrl,token.value)

  def withUrlId(urlId: Id[URL]) = copy(urlId = Some(urlId))

  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = Some(normUriId))

  def save(implicit conn: Connection): DeepLink = {
    val entity = DeepLinkEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

}

@ImplementedBy(classOf[DeepLinkRepoImpl])
trait DeepLinkRepo extends Repo[DeepLink] {
  def getByUri(urlId: Id[NormalizedURI])(implicit session: RSession): Seq[DeepLink]
  def getByUrl(urlId: Id[URL])(implicit session: RSession): Seq[DeepLink]
  def getByToken(token: DeepLinkToken)(implicit session: RSession): Option[DeepLink]
}

@Singleton
class DeepLinkRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[DeepLink] with DeepLinkRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[DeepLink](db, "deep_link") {
    def initatorUserId = column[Id[User]]("initiator_user_id")
    def recipientUserId = column[Id[User]]("recipient_user_id")
    def uriId = column[Id[NormalizedURI]]("uri_id")
    def urlId = column[Id[URL]]("url_id")
    def deepLocator = column[DeepLocator]("deep_locator", O.NotNull)
    def token = column[DeepLinkToken]("token", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ initatorUserId.? ~ recipientUserId.? ~ uriId.? ~ urlId.? ~ deepLocator ~ token ~ state <> (DeepLink, DeepLink.unapply _)
  }

  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[DeepLink] =
    (for(b <- table if b.uriId === uriId) yield b).list

  def getByUrl(urlId: Id[URL])(implicit session: RSession): Seq[DeepLink] =
    (for(b <- table if b.urlId === urlId) yield b).list

  def getByToken(token: DeepLinkToken)(implicit session: RSession): Option[DeepLink] =
    (for(b <- table if b.token === token) yield b).firstOption
}

//slicked!
object DeepLinkCxRepo {

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

  def getByUrlId(urlId: Id[URL])(implicit conn: Connection): Seq[DeepLink] =
    (DeepLinkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.urlId EQ urlId) list() }.map(_.view)

  def getByUriId(uri: NormalizedURI)(implicit conn: Connection): Seq[DeepLink] =
    (DeepLinkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.uriId EQ uri.id.get) list }.map(_.view)

}

object DeepLinkStates {
  val ACTIVE = State[DeepLink]("active")
  val INACTIVE = State[DeepLink]("inactive")
}

private[model] class DeepLinkEntity extends Entity[DeepLink, DeepLinkEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val initatorUserId = "initiator_user_id".ID[User]
  val recipientUserId = "recipient_user_id".ID[User]
  val uriId = "uri_id".ID[NormalizedURI]
  val urlId = "url_id".ID[URL]
  val deepLocator = "deep_locator".VARCHAR(512).NOT_NULL
  val token = "token".VARCHAR(16).NOT_NULL
  val state = "state".STATE[DeepLink].NOT_NULL(DeepLinkStates.ACTIVE)

  def relation = DeepLinkEntity

  def view(implicit conn: Connection): DeepLink = DeepLink(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    initatorUserId = initatorUserId.value,
    recipientUserId = recipientUserId.value,
    uriId = uriId.value,
    urlId = urlId.value,
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
    uri.urlId.set(view.urlId)
    uri.deepLocator := view.deepLocator.value
    uri.token := view.token.value
    uri.state := view.state
    uri
  }
}
