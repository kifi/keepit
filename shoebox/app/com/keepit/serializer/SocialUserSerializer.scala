package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import securesocial.core._
import securesocial.core.AuthenticationMethod._

import play.api.libs.json._

class SocialUserSerializer extends Format[SocialUser] {

  def writes(user: SocialUser): JsValue =
    JsObject(List(
      "id"  -> JsObject(List(
          "id" -> JsString(user.id.id),
          "providerId" -> JsString(user.id.providerId)
      )),
      "displayName" -> JsString(user.displayName),
      "email"  -> (user.email map { e => JsString(e) } getOrElse(JsNull)),
      "avatarUrl"  -> (user.avatarUrl map { e => JsString(e) } getOrElse(JsNull)),
      "authMethod" -> JsString(user.authMethod.method),
      "isEmailVerified" -> JsBoolean(user.isEmailVerified),
      //assume there is facebook and only facebook (for now)
      "oAuth2Info" -> writesOAuth2Info(user.oAuth2Info.get)
      )
    )

  def writesOAuth2Info(info: OAuth2Info): JsValue =
    JsObject(List(
      "accessToken" -> JsString(info.accessToken),
      "tokenType" -> (info.tokenType map { e => JsString(e) } getOrElse(JsNull)),
      "expiresIn" -> (info.expiresIn map { e => JsNumber(e) } getOrElse(JsNull)),
      "refreshToken" -> (info.refreshToken map { e => JsString(e) } getOrElse(JsNull))
    ))

  def reads(json: JsValue): SocialUser =
    SocialUser(
        UserId((json \ "id" \ "id").as[String],
               (json \ "id" \ "providerId").as[String]),
        (json \ "displayName").as[String],
        (json \ "email").asOpt[String],
        (json \ "avatarUrl").asOpt[String],
        AuthenticationMethod((json \ "authMethod").as[String]) match {
          case OAuth1 => OAuth1
          case OAuth2 => OAuth2
          case OpenId => OpenId
          case UserPassword => UserPassword
          case _ => throw new Exception("Unknown AuthenticationMethod in %s".format(json))
        },
        (json \ "isEmailVerified").as[Boolean],
        //assume there is facebook and only facebook (for now)
        None,
        Some(readsOAuth2Info(json \ "oAuth2Info")),
        None
    )

  def readsOAuth2Info(json: JsValue): OAuth2Info =
    OAuth2Info(
      (json \ "accessToken").as[String],
      (json \ "tokenType").asOpt[String],
      (json \ "expiresIn").asOpt[Int],
      (json \ "refreshToken").asOpt[String]
    )
}

object SocialUserSerializer {
  implicit val userSerializer = new SocialUserSerializer
}
