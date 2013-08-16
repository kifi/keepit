package com.keepit.eliza

import scala.slick.lifted.{BaseTypeMapper}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.{IdMapperDelegate, ExternalIdMapperDelegate, StringMapperDelegate}
import scala.slick.driver.{BasicProfile}
import play.api.libs.json.{Json, JsValue, JsObject, JsSuccess, JsArray, JsNumber}
import com.keepit.model.{User, NormalizedURI}
import org.joda.time.DateTime


case class InvalidDatabaseEncodingException(msg: String) extends java.lang.Throwable

object MessagingTypeMappers {

  implicit object ThreadIdTypeMapper extends BaseTypeMapper[Id[MessageThread]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[MessageThread](profile)
  }

  implicit object ThreadExtIdTypeMapper extends BaseTypeMapper[ExternalId[MessageThread]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[MessageThread](profile)
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

  implicit object NormalizedUriIdSeqMapper extends BaseTypeMapper[Seq[Id[NormalizedURI]]] {
    def apply(profile: BasicProfile) = new StringMapperDelegate[Seq[Id[NormalizedURI]]](profile) {
      
      implicit val nUriIdFormat = Id.format[NormalizedURI]

      def safeDestToSource(source: String): Seq[Id[NormalizedURI]] = {
        Json.parse(source) match {
          case x: JsArray => {
            x.value.map(_.as[Id[NormalizedURI]])
          }
          case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for Seq of Normalized URI ids: $source")
        }
      }
      
      def sourceToDest(dest: Seq[Id[NormalizedURI]]): String = {
        Json.stringify(JsArray(dest.map( x => JsNumber(x.id) )))
      }
      
      def zero: Seq[Id[NormalizedURI]] = Seq[Id[NormalizedURI]]()
    }
  }
}
