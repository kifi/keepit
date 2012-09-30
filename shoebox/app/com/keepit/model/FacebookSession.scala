package com.keepit.model

import java.sql.Connection
import org.joda.time.DateTime
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Entity
import com.keepit.common.db.EntityTable
import com.keepit.common.db.Id
import com.keepit.common.db.NotFoundException
import com.keepit.common.time._
import ru.circumflex.orm._
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import com.keepit.common.db.State

case class FacebookAccessToken(value: String)

case class FacebookId(val value: String) {
  override def toString = value
}

case class FacebookSession(id: Option[Id[FacebookSession]] = None, 
    createdAt: DateTime = currentDateTime, 
    updatedAt: DateTime = currentDateTime, 
    externalId: ExternalId[FacebookSession] = ExternalId(),
    userId: Id[User],
    state: State[FacebookSession] = FacebookSession.States.CREATED,
    facebookCode: Option[String] = None,
    facebookError: Option[String] = None, 
    facebookErrorDescription: Option[String] = None,
    facebookAccessToken: Option[FacebookAccessToken] = None,
    facebookJson: Option[JsValue] = None
) {
  def withOauthResponse(code: Option[String], error: Option[String], errorDescription: Option[String]) = 
      copy(facebookCode = code, facebookError = error, facebookErrorDescription = errorDescription, state = code match {
        case None => FacebookSession.States.ERROR
        case _ => FacebookSession.States.AUTHENTICATED
      })
      
  def withAccessToken(token: FacebookAccessToken) = copy(facebookAccessToken = Some(token))
  def cancel() = copy(state = FacebookSession.States.CANCELED)
  def withFacebookJson(facebookJson: JsValue) = copy(facebookJson = Some(facebookJson))
  
  def save(implicit conn: Connection): FacebookSession = {
    val entity = FacebookSessionEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

object FacebookSession {
  
  def apply(user: User): FacebookSession = FacebookSession(userId = user.id.get)
  
  def all(implicit conn: Connection): Seq[FacebookSession] =
    FacebookSessionEntity.all.map(_.view)
  
  def get(id: Id[FacebookSession])(implicit conn: Connection): FacebookSession =
    FacebookSessionEntity.get(id).get.view
    
  def get(externalId: ExternalId[FacebookSession]): FacebookSession =
    getOpt(externalId).getOrElse(throw NotFoundException(externalId))
        
  def getOpt(externalId: ExternalId[FacebookSession]): Option[FacebookSession] =
    (FacebookSessionEntity AS "f").map { f =>
      SELECT (f.*) FROM f WHERE (f.externalId EQ externalId) unique
    }.map(_.view)

  object States {
    val CANCELED = State[FacebookSession]("canceled")
    val CREATED = State[FacebookSession]("created")
    val ERROR = State[FacebookSession]("error")
    val AUTHENTICATED = State[FacebookSession]("authenticated")
  }
}

private[model] class FacebookSessionEntity extends Entity[FacebookSession, FacebookSessionEntity] {
  
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[FacebookSession].NOT_NULL(ExternalId())

  val userId = "user_id".ID[User]
  val state = "state".STATE[FacebookSession].NOT_NULL
  val facebookCode = "facebook_code".VARCHAR(512)
  val facebookError = "facebook_error".VARCHAR(256)
  val facebookErrorDescription = "facebook_error_description".VARCHAR(256)
  val facebookAccessToken = "facebook_access_token".VARCHAR(512)
  val facebookJson = "facebook_json".TEXT

  def relation = FacebookSessionEntity

  def view = FacebookSession(id.value, createdAt(), updatedAt(), externalId(), userId(), state(), 
      facebookCode.value, facebookError.value, facebookErrorDescription.value, facebookAccessToken.map(FacebookAccessToken(_)), 
      None)
      
  def parseJson(str: String): JsValue = try {
    Json.parse(str)
  } catch {
    case e => throw new IllegalArgumentException("Can't parse json:\n%s".format(str), e)
  }
}

private[model] object FacebookSessionEntity extends FacebookSessionEntity with EntityTable[FacebookSession, FacebookSessionEntity] {
  
  override def relationName = "facebook_session"
  
  def apply(view: FacebookSession): FacebookSessionEntity = {
    val session = new FacebookSessionEntity()
    session.id.set(view.id)
    session.createdAt := view.createdAt
    session.updatedAt := view.updatedAt 
    session.externalId := view.externalId 
    session.userId := view.userId
    session.state := view.state
    session.facebookCode.set(view.facebookCode)
    session.facebookError.set(view.facebookError)
    session.facebookErrorDescription.set(view.facebookErrorDescription)
    session.facebookAccessToken.set(view.facebookAccessToken.map(_.value))
    view.facebookJson map {json => session.facebookJson.set(Some(json.toString()))}
    session
  }
}
