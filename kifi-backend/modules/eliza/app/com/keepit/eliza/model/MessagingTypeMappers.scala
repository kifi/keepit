package com.keepit.eliza.model

import com.keepit.common.db.{ Id }
import play.api.libs.json.{ Json, JsSuccess, JsArray, JsNumber }
import com.keepit.model.{ NormalizedURI }
import com.keepit.common.db.slick.DataBaseComponent

case class InvalidDatabaseEncodingException(msg: String) extends java.lang.Throwable

trait MessagingTypeMappers { self: { val db: DataBaseComponent } =>
  import db.Driver.simple._

  implicit val messageSourceTypeMapper = MappedColumnType.base[MessageSource, String](_.value, MessageSource.apply)

  implicit val messageThreadParticipantsMapper = MappedColumnType.base[MessageThreadParticipants, String]({ people =>
    Json.stringify(Json.toJson(people))
  }, { source =>
    Json.parse(source).validate[MessageThreadParticipants] match {
      case JsSuccess(mtps, _) => mtps
      case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for MessageThreadParticipants: $source")
    }
  })
  private implicit val nUriIdFormat = Id.format[NormalizedURI]
  implicit val normalizedUriIdSeqMapper = MappedColumnType.base[Seq[Id[NormalizedURI]], String]({ dest =>
    Json.stringify(JsArray(dest.map(x => JsNumber(x.id))))
  }, { source =>
    Json.parse(source) match {
      case x: JsArray => {
        x.value.map(_.as[Id[NormalizedURI]])
      }
      case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for Seq of Normalized URI ids: $source")
    }
  })
  implicit def threadAccessTokenTypeMapper = MappedColumnType.base[ThreadAccessToken, String](_.token, ThreadAccessToken.apply _)

}

@deprecated("Not needed anymore!", "2014-02-06")
object MessagingTypeMappers
