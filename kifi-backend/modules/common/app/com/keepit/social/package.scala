package com.keepit

import play.api.libs.json.{ JsResult, JsValue, Format, Json }

package object social {

  type BasicUserLikeEntity = Either[BasicNonUser, BasicUser]

  object BasicUserLikeEntity {
    val format = basicUserLikeEntityFormat

    def apply(basicUser: BasicUser): BasicUserLikeEntity = user(basicUser)
    def apply(basicNonUser: BasicNonUser): BasicUserLikeEntity = nonUser(basicNonUser)

    object user {
      def apply(basicUser: BasicUser): BasicUserLikeEntity = Right(basicUser)
      def unapply(that: BasicUserLikeEntity): Option[BasicUser] = that.fold(nonUser => None, user => Some(user))
    }

    object nonUser {
      def apply(basicNonUser: BasicNonUser): BasicUserLikeEntity = Left(basicNonUser)
      def unapply(that: BasicUserLikeEntity): Option[BasicNonUser] = that.fold(nonUser => Some(nonUser), user => None)
    }

  }

  private implicit val nonUserTypeFormat = Json.format[NonUserKind]

  implicit val basicUserLikeEntityFormat = new Format[BasicUserLikeEntity] {
    def reads(json: JsValue): JsResult[BasicUserLikeEntity] = {
      // Detect if this is a BasicUser or BasicNonUser
      (json \ "kind").asOpt[String] match {
        case Some(kind) => BasicNonUser.format.reads(json).map { basicNonUser => BasicUserLikeEntity(basicNonUser) }
        case None => BasicUser.format.reads(json).map { basicUser => BasicUserLikeEntity(basicUser) }
      }
    }
    def writes(entity: BasicUserLikeEntity): JsValue = {
      entity match {
        case Right(basicUser) => BasicUser.format.writes(basicUser)
        case Left(basicNonUser) => BasicNonUser.format.writes(basicNonUser)
      }
    }
  }

}
