package com.keepit.serializer

import com.keepit.common.db.{State, Id}
import com.keepit.common.time._
import com.keepit.common.social.{SocialNetworkType, SocialNetworks, SocialId}
import com.keepit.model.SocialUserInfo
import play.api.libs.json._
import org.joda.time.DateTime

class SocialUserInfoSerializer extends Format[SocialUserInfo] {

  def writes(info: SocialUserInfo): JsValue =
    JsObject(List(
      "id" -> (info.id map { i => JsNumber(i.id) } getOrElse (JsNull)),
      "createdAt" -> Json.toJson(info.createdAt),
      "updatedAt" -> Json.toJson(info.updatedAt),
      "userId" -> (info.userId map { e => JsNumber(e.id) } getOrElse(JsNull)),
      "fullName" -> JsString(info.fullName),
      "state" -> JsString(info.state.value),
      "socialId" -> JsString(info.socialId.id),
      "networkType" -> JsString(SocialNetworkType.unapply(info.networkType).get),
      "credentials" -> (info.credentials map { i => SocialUserSerializer.userSerializer.writes(i) } getOrElse (JsNull)),
      "lastGraphRefresh" -> Json.toJson(info.lastGraphRefresh)
    )
    )

  def reads(json: JsValue): JsResult[SocialUserInfo] =
    JsSuccess(SocialUserInfo(
      id = (json \ "id").asOpt[Long].map(Id[SocialUserInfo]),
      createdAt = (json \ "createdAt").as[DateTime],
      updatedAt = (json \ "updatedAt").as[DateTime],
      userId = (json \ "userId").asOpt[Long].map(Id(_)),
      fullName = (json \ "fullName").as[String],
      state = State[SocialUserInfo]((json \ "state").as[String]),
      socialId = SocialId((json \ "socialId").as[String]),
      networkType = SocialNetworkType((json \ "networkType").as[String]),
      credentials = (json \ "credentials") match {
        case n: JsObject => Some(SocialUserSerializer.userSerializer.reads(n).get)
        case n: JsValue => None
      },
      lastGraphRefresh = (json \ "lastGraphRefresh") match {
        case JsNull | JsUndefined(_) => None
        case v => Some(v.as[DateTime])
      }
    ))
}

object SocialUserInfoSerializer {
  implicit val socialUserInfoSerializer = new SocialUserInfoSerializer
}
