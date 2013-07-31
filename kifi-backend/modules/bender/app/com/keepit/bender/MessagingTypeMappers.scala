package com.keepit.bender

import scala.slick.lifted.{BaseTypeMapper}
import com.keepit.common.db.{Id}
import com.keepit.common.db.slick.{IdMapperDelegate, StringMapperDelegate}
import scala.slick.driver.{BasicProfile}
import play.api.libs.json.{Json, JsValue, JsObject}
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
      
      //Todo Stephen: This is terrible
      def safeDestToSource(source: String): MessageThreadParticipants = {
        Json.parse(source) match {
          case obj: JsObject => {
            val mtps = zero
            mtps.participants = obj.fields.toMap.map( uid_dt => (Id[User](uid_dt._1.toLong), uid_dt._2.as[DateTime]) )
            mtps
          }
          case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for MessageThreadParticipants: $source")
        }
      }
      
      //Todo Stephen: And so is this
      def sourceToDest(dest: MessageThreadParticipants): String = Json.stringify(JsObject(dest.participants.toSeq.map{x => (x._1.id.toString, Json.toJson(x._2))}))
      
      def zero: MessageThreadParticipants = MessageThreadParticipants(Set[Id[User]]())

    }
  }

}