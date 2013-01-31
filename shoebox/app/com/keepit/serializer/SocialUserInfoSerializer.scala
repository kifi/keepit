package com.keepit.serializer

import com.keepit.common.db.{State, Id}
import com.keepit.common.time._
import com.keepit.common.social.SocialNetworks
import com.keepit.model.SocialUserInfo
import play.api.libs.json._
import com.keepit.common.social.SocialId

class SocialUserInfoSerializer extends Format[SocialUserInfo] {

  def writes(info: SocialUserInfo): JsValue =
    JsObject(List(
      "id" -> (info.id map { i => JsNumber(i.id) } getOrElse (JsNull)),
      "createdAt" -> JsString(info.createdAt.toStandardTimeString),
      "updatedAt" -> JsString(info.updatedAt.toStandardTimeString),
      "userId" -> (info.userId map { e => JsNumber(e.id) } getOrElse(JsNull)),
      "fullName" -> JsString(info.fullName),
      "state" -> JsString(info.state.value),
      "socialId" -> JsString(info.socialId.id),
      "networkType" -> JsString(info.networkType.name),
      "credentials" -> (info.credentials map { i => SocialUserSerializer.userSerializer.writes(i) } getOrElse (JsNull)),
      "lastGraphRefresh" -> (info.lastGraphRefresh map { i => JsString(i.toStandardTimeString) } getOrElse (JsNull))
    )
    )

  def reads(json: JsValue): SocialUserInfo =
    SocialUserInfo(
      id = (json \ "id").asOpt[Long].map(Id[SocialUserInfo]),
      createdAt = parseStandardTime((json \ "createdAt").as[String]),
      updatedAt = parseStandardTime((json \ "updatedAt").as[String]),
      userId = (json \ "userId").asOpt[Long].map(Id(_)),
      fullName = (json \ "fullName").as[String],
      state = State[SocialUserInfo]((json \ "state").as[String]),
      socialId = SocialId((json \ "socialId").as[String]),
      networkType = (json \ "networkType").as[String] match {
        case SocialNetworks.FACEBOOK.name => SocialNetworks.FACEBOOK
      },
      credentials = (json \ "credentials") match {
        case n: JsObject => Some(SocialUserSerializer.userSerializer.reads(n))
        case n: JsValue => None
      },
      lastGraphRefresh = ((json \ "lastGraphRefresh").asOpt[String]).map(parseStandardTime)
    )
}

object SocialUserInfoSerializer {
  implicit val socialUserInfoSerializer = new SocialUserInfoSerializer
}
