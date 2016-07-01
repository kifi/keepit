package com.keepit.common.db.slick

import java.sql.{ Clob, Timestamp }
import javax.sql.rowset.serial.SerialClob

import com.keepit.classify.{ DomainTagName, NormalizedHostname }
import com.keepit.common.db._
import com.keepit.common.service.IpAddress
import com.keepit.common.mail.{ EmailAddress, _ }
import com.keepit.common.math.ProbabilityDensity
import com.keepit.common.net.UserAgent
import com.keepit.common.path.Path
import com.keepit.common.store.ImagePath
import com.keepit.common.time._
import com.keepit.common.util.DollarAmount
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.model._
import java.sql.{ Clob, Timestamp }
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.notify.model.{ Recipient, NKind, NotificationKind }
import org.joda.time.{ LocalTime, DateTime }
import scala.slick.ast.TypedType
import scala.slick.jdbc.{ GetResult, SetParameter }
import play.api.libs.json._
import com.keepit.common.net.UserAgent
import com.keepit.classify.{ NormalizedHostname, DomainTagName }
import com.keepit.common.mail._
import com.keepit.social.SocialNetworkType
import securesocial.core.SocialUser
import com.keepit.notify.model.Recipient
import com.keepit.search.{ ArticleSearchResult, Lang, SearchConfig }
import com.keepit.serializer.SocialUserSerializer
import com.keepit.social.{ SocialId, SocialNetworkType }
import org.joda.time.{ DateTime, LocalTime }
import securesocial.core.SocialUser

import scala.concurrent.duration._
import scala.reflect.ClassTag

case class InvalidDatabaseEncodingException(msg: String) extends java.lang.Throwable

trait FortyTwoGenericTypeMappers { self: { val db: DataBaseComponent } =>
  import db.Driver.simple._

  implicit def idMapper[M] = MappedColumnType.base[Id[M], Long](_.id, Id[M])
  implicit def stateTypeMapper[M <: Model[M]] = MappedColumnType.base[State[M], String](_.value, State[M])
  implicit def externalIdTypeMapper[M <: Model[M]] = MappedColumnType.base[ExternalId[M], String](_.id, ExternalId[M])
  implicit def nameMapper[M <: Model[M]] = MappedColumnType.base[Name[M], String](_.name, Name[M])
  implicit val dateTimeMapper = MappedColumnType.base[DateTime, Timestamp](d => new Timestamp(d.getMillis), t => if (t == null) null else new DateTime(t.getTime, zones.UTC))
  implicit val localTimeMapper = MappedColumnType.base[LocalTime, Int](t => t.getMillisOfDay, t => new LocalTime(t, zones.UTC))
  implicit val durationMapper = MappedColumnType.base[Duration, Long](_.toMillis, _ millis)

  implicit def seqIdsMapper[M <: Model[M]]: TypedType[Seq[Id[M]]] = MappedColumnType.base[Seq[Id[M]], String](
    { ids => Json.stringify(Json.toJson(ids)) }, { jstr => Json.parse(jstr).as[Seq[Id[M]]] })

  implicit def sequenceNumberTypeMapper[T] = MappedColumnType.base[SequenceNumber[T], Long](_.value, SequenceNumber[T](_))
  implicit def versionNumberTypeMapper[T] = MappedColumnType.base[VersionNumber[T], Int](_.value, VersionNumber[T](_))
  implicit val abookOriginMapper = MappedColumnType.base[ABookOriginType, String](_.name, ABookOriginType.apply)
  implicit val normalizationMapper = MappedColumnType.base[Normalization, String](_.scheme, Normalization.apply)
  implicit val userToDomainKindMapper = MappedColumnType.base[UserToDomainKind, String](_.value, UserToDomainKind.apply)
  implicit val userPictureSource = MappedColumnType.base[UserPictureSource, String](_.name, UserPictureSource.apply)
  implicit val restrictionMapper = MappedColumnType.base[Restriction, String](_.context, Restriction.apply)
  implicit val issuerMapper = MappedColumnType.base[OAuth2TokenIssuer, String](_.name, OAuth2TokenIssuer.apply)
  implicit val electronicMailCategoryMapper = MappedColumnType.base[ElectronicMailCategory, String](_.category, ElectronicMailCategory.apply)
  implicit val userAgentMapper = MappedColumnType.base[UserAgent, String](_.userAgent, UserAgent.apply)
  implicit val emailAddressMapper = MappedColumnType.base[EmailAddress, String](_.address, EmailAddress.apply)
  implicit val pathMapper = MappedColumnType.base[Path, String](_.relativeWithLeadingSlash, Path(_))
  implicit val seqEmailAddressMapper = MappedColumnType.base[Seq[EmailAddress], String](v => v.map { e => e.address } mkString (","), v => v.trim match {
    case "" => Nil
    case trimmed => trimmed.split(",") map { addr => EmailAddress(addr.trim) }
  })
  implicit val urlHashMapper = MappedColumnType.base[UrlHash, String](_.hash, UrlHash.apply)
  implicit val emailAddressHashMapper = MappedColumnType.base[EmailAddressHash, String](_.hash, EmailAddressHash.apply)
  implicit val normalizedHostnameMapper = MappedColumnType.base[NormalizedHostname, String](_.value, NormalizedHostname.apply)
  implicit val ipAddressTypeMapper = MappedColumnType.base[IpAddress, String](_.toString, IpAddress(_))
  implicit val deepLocatorMapper = MappedColumnType.base[DeepLocator, String](_.value, DeepLocator.apply)
  implicit val deepLinkTokenMapper = MappedColumnType.base[DeepLinkToken, String](_.value, DeepLinkToken.apply)
  implicit val bookmarkSourceMapper = MappedColumnType.base[KeepSource, String](_.value, KeepSource.fromStr(_).get)
  implicit val domainTagNameMapper = MappedColumnType.base[DomainTagName, String](_.name, DomainTagName.apply)
  implicit val socialIdMapper = MappedColumnType.base[SocialId, String](_.id, SocialId.apply)
  implicit val socialNetworkTypeMapper = MappedColumnType.base[SocialNetworkType, String](SocialNetworkType.unapply(_).get, SocialNetworkType.apply)
  implicit val socialUserMapper = MappedColumnType.base[SocialUser, String](SocialUserSerializer.userSerializer.writes(_).toString, s => SocialUserSerializer.userSerializer.reads(Json.parse(s)).get)
  implicit val langTypeMapper = MappedColumnType.base[Lang, String](_.lang, Lang.apply)
  implicit val electronicMailMessageIdMapper = MappedColumnType.base[ElectronicMailMessageId, String](_.id, ElectronicMailMessageId.apply)
  implicit val mapStringStringMapper = MappedColumnType.base[Map[String, String], String](v => Json.stringify(JsObject(v.mapValues(JsString.apply).toSeq)), Json.parse(_).as[JsObject].fields.toMap.mapValues(_.as[JsString].value))
  implicit val userExperimentTypeMapper = MappedColumnType.base[UserExperimentType, String](_.value, UserExperimentType.apply)
  implicit val orgExperimentTypeMapper = MappedColumnType.base[OrganizationExperimentType, String](_.value, OrganizationExperimentType.apply)
  implicit val hitUUIDTypeMapper = MappedColumnType.base[ExternalId[ArticleSearchResult], String](_.id, ExternalId[ArticleSearchResult])
  implicit val uriImageSourceTypeMapper = MappedColumnType.base[ImageFormat, String](_.value, ImageFormat.apply)
  implicit val libraryVisibilityTypeMapper = MappedColumnType.base[LibraryVisibility, String](_.value, LibraryVisibility.apply)
  implicit val librarySlugTypeMapper = MappedColumnType.base[LibrarySlug, String](_.value, LibrarySlug.apply)
  implicit val libraryAccessTypeMapper = MappedColumnType.base[LibraryAccess, String](_.value, LibraryAccess.apply)
  implicit val libraryInvitePermissionTypeMapper = MappedColumnType.base[LibraryInvitePermissions, String](_.value, LibraryInvitePermissions.apply)
  implicit val libraryCommentPermissionTypeMapper = MappedColumnType.base[LibraryCommentPermissions, String](_.value, LibraryCommentPermissions.apply)
  implicit val usernameTypeMapper = MappedColumnType.base[Username, String](_.value, Username.apply)
  implicit val organizationHandleTypeMapper = MappedColumnType.base[OrganizationHandle, String](_.value, OrganizationHandle.apply)
  implicit val organizationSettingsTypeMapper = MappedColumnType.base[OrganizationSettings, String](
    { obj => Json.stringify(Json.toJson(obj)(OrganizationSettings.dbFormat)) },
    { str => Json.parse(str).as[OrganizationSettings](OrganizationSettings.dbFormat) }
  )
  implicit val libraryKindTypeMapper = MappedColumnType.base[LibraryKind, String](_.value, LibraryKind.apply)
  implicit val userValueNameTypeMapper = MappedColumnType.base[UserValueName, String](_.name, UserValueName.apply)
  implicit val hashtagTypeMapper = MappedColumnType.base[Hashtag, String](_.tag, Hashtag.apply)
  implicit val libraryColorTypeMapper = MappedColumnType.base[LibraryColor, String](_.hex, LibraryColor.apply)
  implicit def ldaTopicMapper = MappedColumnType.base[LDATopic, Int](_.index, LDATopic(_))
  implicit val imageHashMapper = MappedColumnType.base[ImageHash, String](_.hash, ImageHash.apply)
  implicit val imageSourceMapper = MappedColumnType.base[ImageSource, String](_.name, ImageSource.apply)
  implicit val imageStoreKeyMapper = MappedColumnType.base[ImagePath, String](_.path, ImagePath.apply)
  implicit val processImageOperationMapper = MappedColumnType.base[ProcessImageOperation, String](_.kind, ProcessImageOperation.apply)
  implicit val keepLibrariesHashMapper = MappedColumnType.base[LibrariesHash, Int](_.value, LibrariesHash.apply)
  implicit val keepParticipantsHashMapper = MappedColumnType.base[ParticipantsHash, Int](_.value, ParticipantsHash.apply)
  implicit val keepRecipientsMapper = MappedColumnType.base[KeepRecipients, String](
    { o => Json.stringify(KeepRecipients.format.writes(o)) },
    { j => Json.parse(j).as[KeepRecipients] }
  )
  implicit val dollarAmountMapper = MappedColumnType.base[DollarAmount, Int](_.cents, DollarAmount.cents)

  implicit val recipientMapper = MappedColumnType.base[Recipient, String](
    recip => Recipient.serialize(recip),
    str => Recipient(str)
  )

  implicit val notificationEventMapper = MappedColumnType.base[NotificationEvent, String]({ event =>
    Json.stringify(Json.toJson(event))
  }, { string =>
    Json.parse(string).as[NotificationEvent]
  })

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

  def jsonMapper[T](implicit format: Format[T], ctag: ClassTag[T]) = MappedColumnType.base[T, String](
    { t => Json.stringify(format.writes(t)) },
    { str => format.reads(Json.parse(str)).get }
  )

  implicit val jsValueMapper = MappedColumnType.base[JsValue, String]({ json =>
    Json.stringify(json)
  }, { src =>
    if (src == null || src.length == 0) {
      JsNull
    } else {
      Json.parse(src)
    }
  })

  // SetParameter conversions to be used for interpolated query parameters

  def setParameterFromMapper[T](implicit mapper: BaseColumnType[T]): SetParameter[T] = {
    SetParameter {
      case (value, pp) =>
        val npos = pp.pos + 1
        mapper.setValue(value, pp.ps, npos)
        pp.pos = npos
    }
  }

  def setOptionParameterFromMapper[T](implicit mapper: BaseColumnType[T]): SetParameter[Option[T]] = {
    SetParameter {
      case (value, pp) =>
        val npos = pp.pos + 1
        mapper.setOption(value, pp.ps, npos)
        pp.pos = npos
    }
  }

  def setSeqParameter[T](implicit pconv: SetParameter[T]): SetParameter[Seq[T]] = {
    SetParameter {
      case (seq, pp) =>
        seq.foreach(pconv(_, pp))
    }
  }

  //  def setParameterFromMapper[T](implicit mapper: BaseColumnType[T]): SetParameter[T] = SetParameter { case (value, parameters) => mapper.setValue(value, parameters) }
  //  def setOptionParameterFromMapper[T](implicit mapper: BaseColumnType[T]): SetParameter[Option[T]] = SetParameter { case (option, parameters) => mapper.setOption(option, parameters) }
  //  def setSeqParameter[T](implicit pconv: SetParameter[T]): SetParameter[Seq[T]] = SetParameter { case (seq, parameters) => seq.foreach(pconv(_, parameters)) }

  implicit val setSeqStringParameter = setSeqParameter[String]
  implicit val setSeqLongParameter = setSeqParameter[Long]
  implicit val setDateTimeParameter = setParameterFromMapper[DateTime]
  implicit val setSeqDateTimeParameter = setSeqParameter[DateTime]
  implicit val setLocalTimeParameter = setParameterFromMapper[LocalTime]
  implicit val setSeqLocalTimeParameter = setSeqParameter[LocalTime]
  implicit def setIdParameter[M <: Model[M]] = setParameterFromMapper[Id[M]]
  implicit def setSeqIdsParameter[M <: Model[M]] = setSeqParameter[Id[M]]
  implicit def setStateParameter[M <: Model[M]] = setParameterFromMapper[State[M]]
  implicit val setSocialNetworkTypeParameter = setParameterFromMapper[SocialNetworkType]
  implicit val setEmailAddressParameter = setParameterFromMapper[EmailAddress]
  implicit val setHashtagParameter = setParameterFromMapper[Hashtag]
  implicit val setLibraryVisibilityParameter = setParameterFromMapper[LibraryVisibility]

  // GetResult mappers to be used for interpolated query results

  def getResultFromMapper[T](implicit mapper: BaseColumnType[T]): GetResult[T] = {
    GetResult[T] { pr =>
      val npos = pr.currentPos + 1
      val r = mapper.getValue(pr.rs, npos)
      pr.skip
      r
    }
  }

  def getResultOptionFromMapper[T](implicit mapper: BaseColumnType[T]): GetResult[Option[T]] = {
    GetResult[Option[T]] { pr =>
      val npos = pr.currentPos + 1
      val r = Option(mapper.getValue(pr.rs, npos))
      pr.skip
      r
    }
  }

  //  def getResultFromMapper[T](implicit mapper: BaseColumnType[T]): GetResult[T] = GetResult[T](mapper.nextValue)
  //  def getResultOptionFromMapper[T](implicit mapper: BaseColumnType[T]): GetResult[Option[T]] = GetResult[Option[T]](mapper.nextOption)

  implicit val getDateTimeResult = getResultFromMapper[DateTime]
  implicit val getOptDateTimeResult = getResultOptionFromMapper[DateTime]
  implicit val getLocalTimeResult = getResultFromMapper[LocalTime]
  implicit val getOptLocalTimeResult = getResultOptionFromMapper[LocalTime]
  implicit val getSocialNetworkTypeResult = getResultFromMapper[SocialNetworkType]
  implicit def getSequenceNumberResult[T] = getResultFromMapper[SequenceNumber[T]]
  implicit def getOptSequenceNumberResult[T] = getResultOptionFromMapper[SequenceNumber[T]]
  implicit def getIdResult[M <: Model[M]] = getResultFromMapper[Id[M]]
  implicit def getOptIdResult[M <: Model[M]] = getResultOptionFromMapper[Id[M]]
  implicit def getStateResult[M <: Model[M]] = getResultFromMapper[State[M]]
  implicit def getExtIdResult[M <: Model[M]] = getResultFromMapper[ExternalId[M]]
  implicit def getOptExtIdResult[M <: Model[M]] = getResultOptionFromMapper[ExternalId[M]]
  implicit val getEmailAddressResult = getResultFromMapper[EmailAddress]
  implicit val getLibraryVisiblityResult = getResultFromMapper[LibraryVisibility]
  implicit val getOptLibraryVisiblityResult = getResultOptionFromMapper[LibraryVisibility]
  implicit val getLibraryCommentPermissionsResult = getResultFromMapper[LibraryCommentPermissions]
  implicit val getOptLibraryAccessResult = getResultOptionFromMapper[LibraryAccess]
  implicit val getLibraryAccessResult = getResultFromMapper[LibraryAccess]
  implicit val getHashtagResult = getResultFromMapper[Hashtag]
  implicit def getOptVersionNumberResult[T] = getResultOptionFromMapper[VersionNumber[T]]
  implicit val getOptDurationResult = getResultOptionFromMapper[Duration]
  implicit val getUrlHashResult = getResultFromMapper[UrlHash]
  implicit val getJsValueResult = getResultFromMapper[JsValue]
}
