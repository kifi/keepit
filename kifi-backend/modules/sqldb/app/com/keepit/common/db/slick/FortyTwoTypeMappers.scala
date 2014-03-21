package com.keepit.common.db.slick

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import java.sql.{Clob, Date, Timestamp}
import org.joda.time.{DateTime, LocalDate}
import scala.slick.jdbc.{PositionedResult, GetResult, PositionedParameters, SetParameter}
import play.api.libs.json._
import com.keepit.common.net.UserAgent
import com.keepit.classify.DomainTagName
import com.keepit.common.mail._
import com.keepit.social.SocialNetworkType
import securesocial.core.SocialUser
import com.keepit.serializer.SocialUserSerializer
import com.keepit.search.{SearchConfig, Lang}
import javax.sql.rowset.serial.SerialClob
import com.keepit.model.UrlHash
import play.api.libs.json.JsArray
import play.api.libs.json.JsString
import play.api.libs.json.JsObject
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.social.SocialId
import com.keepit.model.DeepLocator

case class InvalidDatabaseEncodingException(msg: String) extends java.lang.Throwable

trait FortyTwoGenericTypeMappers { self: {val db: DataBaseComponent} =>
  import db.Driver.simple._

  implicit def idMapper[M <: Model[M]] = MappedColumnType.base[Id[M], Long](_.id, Id[M])
  implicit def stateTypeMapper[M <: Model[M]] = MappedColumnType.base[State[M], String](_.value, State[M])
  implicit def externalIdTypeMapper[M <: Model[M]] = MappedColumnType.base[ExternalId[M], String](_.id, ExternalId[M])
  implicit def nameMapper[M <: Model[M]] = MappedColumnType.base[Name[M], String](_.name, Name[M])
  implicit val dateTimeMapper = MappedColumnType.base[DateTime, Timestamp](d => new Timestamp(d.getMillis), t => new DateTime(t.getTime, zones.UTC))

  implicit def sequenceNumberTypeMapper[T] = MappedColumnType.base[SequenceNumber[T], Long](_.value, SequenceNumber[T](_))
  implicit val abookOriginMapper = MappedColumnType.base[ABookOriginType, String](_.name, ABookOriginType.apply)
  implicit val normalizationMapper = MappedColumnType.base[Normalization, String](_.scheme, Normalization.apply)
  implicit val userToDomainKindMapper = MappedColumnType.base[UserToDomainKind, String](_.value, UserToDomainKind.apply)
  implicit val userPictureSource = MappedColumnType.base[UserPictureSource, String](_.name, UserPictureSource.apply)
  implicit val restrictionMapper = MappedColumnType.base[Restriction, String](_.context, Restriction.apply)
  implicit val issuerMapper = MappedColumnType.base[OAuth2TokenIssuer, String](_.name, OAuth2TokenIssuer.apply)
  implicit val electronicMailCategoryMapper = MappedColumnType.base[ElectronicMailCategory, String](_.category, ElectronicMailCategory.apply)
  implicit val userAgentMapper = MappedColumnType.base[UserAgent, String](_.userAgent, UserAgent.fromString)
  implicit val emailAddressHolderMapper = MappedColumnType.base[EmailAddressHolder, String](_.address, GenericEmailAddress.apply)
  implicit val seqEmailAddressHolderMapper = MappedColumnType.base[Seq[EmailAddressHolder], String](v => v.map {e => e.address} mkString(","), v => v.trim match {
    case "" => Nil
    case trimmed => trimmed.split(",") map { addr => new GenericEmailAddress(addr.trim) }
  })
  implicit val kifiVersionMapper = MappedColumnType.base[KifiVersion, String](_.toString, KifiVersion.apply)
  implicit val urlHashMapper = MappedColumnType.base[UrlHash, String](_.hash, UrlHash.apply)
  implicit val deepLocatorMapper = MappedColumnType.base[DeepLocator, String](_.value, DeepLocator.apply)
  implicit val deepLinkTokenMapper = MappedColumnType.base[DeepLinkToken, String](_.value, DeepLinkToken.apply)
  implicit val bookmarkSourceMapper = MappedColumnType.base[KeepSource, String](_.value, KeepSource.apply)
  implicit val systemEmailAddressMapper = MappedColumnType.base[SystemEmailAddress, String](_.address, EmailAddresses.apply)
  implicit val domainTagNameMapper = MappedColumnType.base[DomainTagName, String](_.name, DomainTagName.apply)
  implicit val socialIdMapper = MappedColumnType.base[SocialId, String](_.id, SocialId.apply)
  implicit val socialNetworkTypeMapper = MappedColumnType.base[SocialNetworkType, String](SocialNetworkType.unapply(_).get, SocialNetworkType.apply)
  implicit val socialUserMapper = MappedColumnType.base[SocialUser, String](SocialUserSerializer.userSerializer.writes(_).toString, s => SocialUserSerializer.userSerializer.reads(Json.parse(s)).get)
  implicit val langTypeMapper = MappedColumnType.base[Lang, String](_.lang, Lang.apply)
  implicit val electronicMailMessageIdMapper = MappedColumnType.base[ElectronicMailMessageId, String](_.id, ElectronicMailMessageId.apply)
  implicit val mapStringStringMapper = MappedColumnType.base[Map[String,String], String](v => Json.stringify(JsObject(v.mapValues(JsString.apply).toSeq)), Json.parse(_).as[JsObject].fields.toMap.mapValues(_.as[JsString].value))
  implicit val experimentTypeMapper = MappedColumnType.base[ExperimentType, String](_.value, ExperimentType.apply)
  implicit val scraperWorkerIdTypeMapper = MappedColumnType.base[Id[ScraperWorker], Long](_.id, value => Id[ScraperWorker](value)) // todo(martin): this one shouldn't be necessary
  implicit def experimentTypeProbabilityDensityMapper[T](implicit outcomeFormat: Format[T]) = MappedColumnType.base[ProbabilityDensity[T], String](
    obj => Json.stringify(ProbabilityDensity.format[T].writes(obj)),
    str => Json.parse(str).as(ProbabilityDensity.format[T])
  )

  implicit val searchConfigMapper = MappedColumnType.base[SearchConfig, String]({ value =>
    Json.stringify(JsObject(value.params.map { case (k, v) => k -> JsString(v) }.toSeq))
  }, { value =>
    SearchConfig(Json.parse(value).asInstanceOf[JsObject].fields.map { case (k, v) => k -> v.as[String] }.toMap)
  })

  implicit val seqURLHistoryMapper = MappedColumnType.base[Seq[URLHistory], String]({ value =>
    Json.stringify(Json.toJson(value))
  }, { value =>
    Json.fromJson[Seq[URLHistory]](Json.parse(value)).get
  })

  implicit val largeStringMapper = MappedColumnType.base[LargeString, Clob]({ value =>
    new SerialClob(value.value.toCharArray()) {
      override def getSubString(pos: Long, length: Int): String = {
        // workaround for an empty clob problem
        if (pos == 1 && length == 0) "" else super.getSubString(pos, length)
      }
    }
  }, { value =>
    val clob = new SerialClob(value)
    clob.length match {
      case empty if (empty <= 0) => LargeString("")
      case length => LargeString(clob.getSubString(1, length.intValue()))
    }
  })

  implicit val jsArrayMapper = MappedColumnType.base[JsArray, String]({ json =>
    Json.stringify(json)
  }, { src =>
    Json.parse(src) match {
      case x: JsArray => x
      case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for JsArray: $src")
    }
  })

  implicit val jsObjectMapper = MappedColumnType.base[JsObject, String]({ json =>
    Json.stringify(json)
  }, { src =>
    Json.parse(src).as[JsObject]
  })

  implicit val jsValueMapper = MappedColumnType.base[JsValue, String]({ json =>
    Json.stringify(json)
  }, { src =>
    Json.parse(src)
  })

  // SetParameter conversions to be used for interpolated query parameters

  def setParameterFromMapper[T](implicit mapper: BaseColumnType[T]): SetParameter[T] = SetParameter { case (value, parameters) => mapper.setValue(value, parameters) }
  def setOptionParameterFromMapper[T](implicit mapper: BaseColumnType[T]): SetParameter[Option[T]] = SetParameter { case (option, parameters) => mapper.setOption(option, parameters) }
  def setSeqParameter[T](implicit pconv: SetParameter[T]): SetParameter[Seq[T]] = SetParameter { case (seq, parameters) => seq.foreach(pconv(_, parameters)) }

  implicit val setSeqStringParameter = setSeqParameter[String]
  implicit val setDateTimeParameter = setParameterFromMapper[DateTime]
  implicit val setSeqDateTimeParameter = setSeqParameter[DateTime]
  implicit def setIdParameter[M <: Model[M]] = setParameterFromMapper[Id[M]]
  implicit def setStateParameter[M <: Model[M]] = setParameterFromMapper[State[M]]
  implicit val setSocialNetworkTypeParameter = setParameterFromMapper[SocialNetworkType]

  // GetResult mappers to be used for interpolated query results

  def getResultFromMapper[T](implicit mapper: BaseColumnType[T]): GetResult[T] = GetResult[T](mapper.nextValue)
  def getResultOptionFromMapper[T](implicit mapper: BaseColumnType[T]): GetResult[Option[T]] = GetResult[Option[T]](mapper.nextOption)

  implicit val getDateTimeResult = getResultFromMapper[DateTime]
  implicit def getSequenceNumberResult[T] = getResultFromMapper[SequenceNumber[T]]
  implicit def getIdResult[M <: Model[M]] = getResultFromMapper[Id[M]]
  implicit def getOptIdResult[M <: Model[M]] = getResultOptionFromMapper[Id[M]]
  implicit def getStateResult[M <: Model[M]] = getResultFromMapper[State[M]]
  implicit def getExtIdResult[M <: Model[M]] = getResultFromMapper[ExternalId[M]]
  implicit def getOptExtIdResult[M <: Model[M]] = getResultOptionFromMapper[ExternalId[M]]
}
