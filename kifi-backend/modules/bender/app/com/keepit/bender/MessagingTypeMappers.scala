package com.keepit.bender

import scala.slick.lifted.{BaseTypeMapper}
import com.keepit.common.db.{Id}
import com.keepit.common.db.slick.{IdMapperDelegate, StringMapperDelegate}
import scala.slick.driver.{BasicProfile}
import play.api.libs.json.{Json, JsValue, JsObject, JsSuccess}
import com.keepit.model.{User}
import org.joda.time.DateTime


case class InvalidDatabaseEncodingException(msg: String) extends java.lang.Throwable

object MessagingTypeMappers {

  implicit object ThreadIdTypeMapper extends BaseTypeMapper[Id[MessageThread]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[MessageThread](profile)
  }

  implicit object MessageIdTypeMapper extends BaseTypeMapper[Id[Message]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Message](profile)
  }


  implicit object MessageThreadParticipantsMapper extends BaseTypeMapper[MessageThreadParticipants] {
    def apply(profile: BasicProfile) = new StringMapperDelegate[MessageThreadParticipants](profile) {
      
      def safeDestToSource(source: String): MessageThreadParticipants = {
        Json.parse(source).validate[MessageThreadParticipants] match {
          case JsSuccess(mtps,_) => mtps
          case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for MessageThreadParticipants: $source")
        }
      }
      
      def sourceToDest(dest: MessageThreadParticipants): String = Json.stringify(Json.toJson(dest))
      
      def zero: MessageThreadParticipants = MessageThreadParticipants(Set[Id[User]]())

    }
  }

}