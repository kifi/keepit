package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
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
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import play.api.mvc.QueryStringBindable
import play.api.mvc.JavascriptLitteral
import com.keepit.common.service.FortyTwoServices

case class DeepLinkToken(value: String)
object DeepLinkToken {
  def apply(): DeepLinkToken = DeepLinkToken(ExternalId().id) // use ExternalIds for now. Eventually, we may move off this.
}

case class DeepLocator(value: String)
object DeepLocator {
  def ofMessageThread(message: Comment) = DeepLocator("/messages/%s".format(message.externalId))
  def ofMessageThreadList = DeepLocator("/messages/")
  def ofComment(comment: Comment) = DeepLocator("/comments/%s".format(comment.externalId))
  def ofCommentList = DeepLocator("/comments/")
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

  def url(implicit fortyTwoServices: FortyTwoServices) = "%s/r/%s".format(fortyTwoServices.baseUrl, token.value)

  def withUrlId(urlId: Id[URL]) = copy(urlId = Some(urlId))

  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = Some(normUriId))
}

@ImplementedBy(classOf[DeepLinkRepoImpl])
trait DeepLinkRepo extends Repo[DeepLink] {
  def getByUri(urlId: Id[NormalizedURI])(implicit session: RSession): Seq[DeepLink]
  def getByUrl(urlId: Id[URL])(implicit session: RSession): Seq[DeepLink]
  def getByToken(token: DeepLinkToken)(implicit session: RSession): Option[DeepLink]
}

@Singleton
class DeepLinkRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[DeepLink] with DeepLinkRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
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

object DeepLinkStates extends States[DeepLink]
