package com.keepit.serializer

import play.api.libs.functional.syntax._
import play.api.libs.json._
import securesocial.core._

object SocialUserSerializer {
  implicit val userIdSerializer = Json.format[UserId]
  implicit val oAuth2InfoSerializer = Json.format[OAuth2Info]
  implicit val oAuth1InfoSerializer = Json.format[OAuth1Info]
  implicit val passwordInfoSerializer = Json.format[PasswordInfo]

  // This is written to be able to read our old SocialUser format as well as the new format and write the new format
  implicit val userSerializer: Format[SocialUser] = (
    (__ \ "id").format[UserId] and
    (__ \ "firstName").formatNullable[String].inmap[String](_.getOrElse(""), Some(_)) and
    (__ \ "lastName").formatNullable[String].inmap[String](_.getOrElse(""), Some(_)) and
    OFormat(
      (__ \ "fullName").read[String] orElse (__ \ "displayName").read[String],
      (__ \ "fullName").write[String]) and
    (__ \ "email").formatNullable[String] and
    (__ \ "avatarUrl").formatNullable[String] and
    (__ \ "authMethod").format[String]
        .inmap[AuthenticationMethod](AuthenticationMethod.apply, unlift(AuthenticationMethod.unapply)) and
    (__ \ "oAuth1Info").formatNullable[OAuth1Info] and
    (__ \ "oAuth2Info").formatNullable[OAuth2Info] and
    (__ \ "passwordInfo").formatNullable[PasswordInfo]
  )(SocialUser.apply, unlift(SocialUser.unapply))
}
