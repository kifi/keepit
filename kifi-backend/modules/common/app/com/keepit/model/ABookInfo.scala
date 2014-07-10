package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.mvc.{ PathBindable, QueryStringBindable }
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.db.Id

sealed abstract class ABookOriginType(val name: String) {
  override def toString: String = name
}

object ABookOriginType {
  def apply(name: String): ABookOriginType = {
    val trimmed = name.toLowerCase.trim
    ABookOrigins.ALL.find(_.name == trimmed).getOrElse(throw new IllegalArgumentException(s"unrecognized abook origin type: $name"))
  }
  def unapply(aot: ABookOriginType): Option[String] = Some(aot.name)

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[ABookOriginType] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ABookOriginType]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(ABookOriginType(value))
        case _ => Left(s"Unable to bind $key $params")
      }
    }
    override def unbind(key: String, state: ABookOriginType): String = stringBinder.unbind(key, state.name)
  }

  implicit def pathBinder = new PathBindable[ABookOriginType] {
    override def bind(key: String, value: String): Either[String, ABookOriginType] = Right(ABookOriginType(value))
    override def unbind(key: String, state: ABookOriginType): String = state.toString
  }

  import play.api.libs.json._
  implicit val format: Format[ABookOriginType] = Format(__.read[String].map(ABookOriginType(_)), new Writes[ABookOriginType] { def writes(o: ABookOriginType) = JsString(o.name) })
}

object ABookOrigins {
  case object IOS extends ABookOriginType("ios")
  case object GMAIL extends ABookOriginType("gmail")
  val ALL: Seq[ABookOriginType] = Seq(IOS, GMAIL)
}

case class ABookRawInfo(userId: Option[Id[User]], origin: ABookOriginType, ownerId: Option[String] = None, ownerEmail: Option[String] = None, numContacts: Option[Int] = None, contacts: JsArray) // ios ownerId may not be present

object ABookRawInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import com.keepit.common.db.Id

  implicit val format = (
    (__ \ 'userId).formatNullable(Id.format[User]) and
    (__ \ 'origin).format[String].inmap(ABookOriginType.apply _, unlift(ABookOriginType.unapply)) and
    (__ \ 'ownerId).formatNullable[String] and
    (__ \ 'ownerEmail).formatNullable[String] and
    (__ \ 'numContacts).formatNullable[Int] and
    (__ \ 'contacts).format[JsArray]
  )(ABookRawInfo.apply _, unlift(ABookRawInfo.unapply))

  val EMPTY = ABookRawInfo(None, ABookOrigins.IOS, None, None, None, JsArray())
}

case class ExternalABookInfo(
  externalId: ExternalId[ABookInfo] = ExternalId(),
  origin: ABookOriginType,
  ownerId: Option[String] = None, // iOS
  ownerEmail: Option[String] = None,
  rawInfoLoc: Option[String] = None,
  numContacts: Option[Int] = None)

object ExternalABookInfo {
  implicit val format: Format[ExternalABookInfo] = (
    (__ \ 'externalId).format[ExternalId[ABookInfo]] and
    (__ \ 'origin).format[ABookOriginType] and
    (__ \ 'ownerId).formatNullable[String] and
    (__ \ 'ownerEmail).formatNullable[String] and
    (__ \ 'rawInfoLoc).formatNullable[String] and
    (__ \ 'numContacts).formatNullable[Int]
  )(ExternalABookInfo.apply, unlift(ExternalABookInfo.unapply))

  def fromABookInfo(info: ABookInfo) = {
    ExternalABookInfo(info.externalId, info.origin, info.ownerId, info.ownerEmail, info.rawInfoLoc, info.numContacts)
  }
}

case class ABookInfo(
    id: Option[Id[ABookInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[ABookInfo] = ExternalId(),
    state: State[ABookInfo] = ABookInfoStates.ACTIVE,
    userId: Id[User],
    origin: ABookOriginType,
    ownerId: Option[String] = None, // iOS
    ownerEmail: Option[String] = None,
    rawInfoLoc: Option[String] = None,
    oauth2TokenId: Option[Id[OAuth2Token]] = None,
    numContacts: Option[Int] = None,
    numProcessed: Option[Int] = None) extends ModelWithState[ABookInfo] with ModelWithExternalId[ABookInfo] {
  def withId(id: Id[ABookInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withOwnerInfo(ownerId: Option[String], ownerEmail: Option[String]) = this.copy(ownerId = ownerId, ownerEmail = ownerEmail)
  def withOAuth2TokenId(oauth2TokenId: Option[Id[OAuth2Token]]) = this.copy(oauth2TokenId = oauth2TokenId)
  def withState(state: State[ABookInfo]) = this.copy(state = state)
  def withNumContacts(nContacts: Option[Int]) = this.copy(numContacts = nContacts)
  def withNumProcessed(nProcessed: Option[Int]) = this.copy(numProcessed = nProcessed)
}

object ABookInfoStates extends States[ABookInfo] {
  val PENDING = State[ABookInfo]("pending")
  val PROCESSING = State[ABookInfo]("processing")
  val UPLOAD_FAILURE = State[ABookInfo]("upload_failure")
}

object ABookInfo {
  implicit val format: Format[ABookInfo] = (
    (__ \ 'id).formatNullable(Id.format[ABookInfo]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'externalId).format[ExternalId[ABookInfo]] and
    (__ \ 'state).format(State.format[ABookInfo]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'origin).format[ABookOriginType] and
    (__ \ 'ownerId).formatNullable[String] and
    (__ \ 'ownerEmail).formatNullable[String] and
    (__ \ 'rawInfoLoc).formatNullable[String] and
    (__ \ 'oauth2TokenId).formatNullable(Id.format[OAuth2Token]) and
    (__ \ 'numContacts).formatNullable[Int] and
    (__ \ 'numProcessed).formatNullable[Int]
  )(ABookInfo.apply, unlift(ABookInfo.unapply))
}

// OAuth2TokenXYZ: experimental -- for internal use/testing only

object OAuth2TokenStates extends States[OAuth2Token]

class OAuth2TokenIssuer(val name: String) {
  require(name != null)
  override def toString = name
  override def hashCode = name.hashCode
  override def equals(o: Any) = o match {
    case tk: OAuth2TokenIssuer => name == tk.name
    case _ => false
  }
}

object OAuth2TokenIssuer {
  def apply(name: String) = new OAuth2TokenIssuer(name)
  implicit def format = Format(__.read[String].map(OAuth2TokenIssuer(_)), new Writes[OAuth2TokenIssuer] { def writes(o: OAuth2TokenIssuer): JsValue = JsString(o.name) })
}

object OAuth2TokenIssuers {
  case object GOOGLE extends OAuth2TokenIssuer("google")
}

case class OAuth2Token(
    id: Option[Id[OAuth2Token]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OAuth2Token] = OAuth2TokenStates.ACTIVE,
    userId: Id[User],
    issuer: OAuth2TokenIssuer = OAuth2TokenIssuers.GOOGLE,
    scope: Option[String] = None,
    tokenType: Option[String] = None,
    accessToken: String,
    expiresIn: Option[Int] = None,
    refreshToken: Option[String] = None,
    autoRefresh: Boolean = false,
    lastRefreshedAt: Option[DateTime] = None,
    idToken: Option[String] = None,
    rawToken: Option[String] = None) extends ModelWithState[OAuth2Token] {
  def withId(id: Id[OAuth2Token]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[OAuth2Token]) = this.copy(state = state)
  def withAccessToken(accessToken: String) = this.copy(accessToken = accessToken)
  def withExpiresIn(expiresIn: Option[Int]) = this.copy(expiresIn = expiresIn)
  def withRefreshToken(refreshToken: Option[String]) = this.copy(refreshToken = refreshToken)
  def withAutoRefresh(autoRefresh: Boolean) = this.copy(autoRefresh = autoRefresh)
  def withLastRefresh(lastRefreshedAt: Option[DateTime]) = this.copy(lastRefreshedAt = lastRefreshedAt)
  def withRawToken(rawToken: Option[String]) = this.copy(rawToken = rawToken)
}

object OAuth2Token {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[OAuth2Token]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[OAuth2Token]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'issuer).format[OAuth2TokenIssuer] and
    (__ \ 'scope).formatNullable[String] and
    (__ \ 'tokenType).formatNullable[String] and
    (__ \ 'accessToken).format[String] and
    (__ \ 'expiresIn).formatNullable[Int] and
    (__ \ 'refreshToken).formatNullable[String] and
    (__ \ 'autoRefresh).format[Boolean] and
    (__ \ 'lastRefreshedAt).formatNullable[DateTime] and
    (__ \ 'idToken).formatNullable[String] and
    (__ \ 'rawToken).formatNullable[String]
  )(OAuth2Token.apply, unlift(OAuth2Token.unapply))
}
