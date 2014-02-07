package com.keepit.common.db.slick

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import java.sql.Date
import org.joda.time.{DateTime, LocalDate}
import scala.slick.jdbc.{PositionedParameters, SetParameter}
import java.sql.Timestamp
import play.api.libs.json.{JsValue, JsArray, Json}
import com.keepit.common.mail.{ElectronicMailCategory, GenericEmailAddress, EmailAddressHolder}

case class InvalidDatabaseEncodingException(msg: String) extends java.lang.Throwable

trait FortyTwoGenericTypeMappers { self: {val db: DataBaseComponent} =>
  import db.Driver.simple._

  implicit def idMapper[M <: Model[M]] = MappedColumnType.base[Id[M], Long](_.id, Id[M])
  implicit def stateTypeMapper[M <: Model[M]] = MappedColumnType.base[State[M], String](_.value, State[M])
  implicit def externalIdTypeMapper[M <: Model[M]] = MappedColumnType.base[ExternalId[M], String](_.id, ExternalId[M])
  implicit def dateTimeMapper[M <: Model[M]] = MappedColumnType.base[DateTime, Timestamp](d => new Timestamp(d.getMillis), t => new DateTime(t.getTime, zones.UTC))

  implicit val sequenceNumberTypeMapper = MappedColumnType.base[SequenceNumber, Long](_.value, SequenceNumber.apply)
  implicit val abookOriginMapper = MappedColumnType.base[ABookOriginType, String](_.name, ABookOriginType.apply)
  implicit val issuerMapper = MappedColumnType.base[OAuth2TokenIssuer, String](_.name, OAuth2TokenIssuer.apply)
  implicit val electronicMailCategoryMapper = MappedColumnType.base[ElectronicMailCategory, String](_.category, ElectronicMailCategory.apply)
  implicit val emailAddressHolderMapper = MappedColumnType.base[EmailAddressHolder, String](_.address, GenericEmailAddress.apply)


  implicit val jsArrayMapper = MappedColumnType.base[JsArray, String]({ json =>
    Json.stringify(json)
  }, { src =>
    Json.parse(src) match {
      case x: JsArray => x
      case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for JsArray: $src")
    }
  })

  implicit val jsValueMapper = MappedColumnType.base[JsValue, String]({ json =>
    Json.stringify(json)
  }, { src =>
    Json.parse(src)
  })

  def seqParam[A](implicit pconv: SetParameter[A]): SetParameter[Seq[A]] = SetParameter {
    case (seq, pp) =>
      for (a <- seq) {
        pconv.apply(a, pp)
      }
  }

  implicit val listStringSP: SetParameter[List[String]] = seqParam[String]

  // Time

  implicit object SetDateTime extends SetParameter[DateTime] {
    def apply(v: DateTime, pp: PositionedParameters) {
      pp.setTimestamp(new Timestamp(v.toDate().getTime()))
    }
  }

  implicit val listDateTimeSP: SetParameter[Seq[DateTime]] = seqParam[DateTime]

  implicit object SetLocalDate extends SetParameter[LocalDate] {
    def apply(v: LocalDate, pp: PositionedParameters) {
      pp.setDate(new Date(v.toDate().getTime()))
    }
  }

  implicit val listLocalDateSP: SetParameter[Seq[LocalDate]] = seqParam[LocalDate]


}

object FortyTwoTypeMappers {


  //ExternalIds
//  implicit object ArticleSearchResultExternalIdTypeMapper extends BaseTypeMapper[ExternalId[ArticleSearchResult]] {
//    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[ArticleSearchResult](profile)
//  }
//
//  implicit object KifiInstallationExternalIdTypeMapper extends BaseTypeMapper[ExternalId[KifiInstallation]] {
//    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[KifiInstallation](profile)
//  }
//
//  implicit object NormalizedURIExternalIdTypeMapper extends BaseTypeMapper[ExternalId[NormalizedURI]] {
//    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[NormalizedURI](profile)
//  }

  //Ids
//  implicit object ABookInfoIdTypeMapper extends BaseTypeMapper[Id[ABookInfo]] {
//    def apply(profile:BasicProfile) = new IdMapperDelegate[ABookInfo](profile)
//  }
//
//  implicit object OAuth2TokenIdTypeMapper extends BaseTypeMapper[Id[OAuth2Token]] {
//    def apply(profile:BasicProfile) = new IdMapperDelegate[OAuth2Token](profile)
//  }
//
//  implicit object EmailAddressIdTypeMapper extends BaseTypeMapper[Id[EmailAddress]] {
//    def apply(profile:BasicProfile) = new IdMapperDelegate[EmailAddress](profile)
//  }
//
//  implicit object UserPictureIdTypeMapper extends BaseTypeMapper[Id[UserPicture]] {
//    def apply(profile:BasicProfile) = new IdMapperDelegate[UserPicture](profile)
//  }
//
//  implicit object SocialUserInfoIdTypeMapper extends BaseTypeMapper[Id[SocialUserInfo]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[SocialUserInfo](profile)
//  }
//
//  implicit object ScraperWorkerIdTypeMapper extends BaseTypeMapper[Id[ScraperWorker]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[ScraperWorker](profile)
//  }
//
//  implicit object EContactIdTypeMapper extends BaseTypeMapper[Id[EContact]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[EContact](profile)
//  }
//
//  implicit object UserIdTypeMapper extends BaseTypeMapper[Id[User]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[User](profile)
//  }
//
//  implicit object URLIdTypeMapper extends BaseTypeMapper[Id[URL]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[URL](profile)
//  }
//
//  implicit object UrlPatternRuleIdTypeMapper extends BaseTypeMapper[Id[UrlPatternRule]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[UrlPatternRule](profile)
//  }
//
//  implicit object NormalizedURIIdTypeMapper extends BaseTypeMapper[Id[NormalizedURI]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[NormalizedURI](profile)
//  }
//
//  implicit object DomainToTagIdTypeMapper extends BaseTypeMapper[Id[DomainToTag]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[DomainToTag](profile)
//  }
//
//  implicit object DomainIdTypeMapper extends BaseTypeMapper[Id[Domain]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[Domain](profile)
//  }
//
//  implicit object DomainTagIdTypeMapper extends BaseTypeMapper[Id[DomainTag]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[DomainTag](profile)
//  }
//
//  implicit object SearchConfigExperimentIdTypeMapper extends BaseTypeMapper[Id[SearchConfigExperiment]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[SearchConfigExperiment](profile)
//  }
//
//  implicit object BookmarkIdTypeMapper extends BaseTypeMapper[Id[Bookmark]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[Bookmark](profile)
//  }
//
//  implicit object KeepToCollectionIdTypeMapper extends BaseTypeMapper[Id[KeepToCollection]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[KeepToCollection](profile)
//  }
//
//  implicit object CollectionIdTypeMapper extends BaseTypeMapper[Id[Collection]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[Collection](profile)
//  }
//
//  implicit object HttpProxyIdTypeMapper extends BaseTypeMapper[Id[HttpProxy]] {
//    def apply(profile: BasicProfile) = new IdMapperDelegate[HttpProxy](profile)
//  }
//
//  //States
//  implicit object NormalizedURIStateTypeMapper extends BaseTypeMapper[State[NormalizedURI]] {
//    def apply(profile: BasicProfile) = new StateMapperDelegate[NormalizedURI](profile)
//  }
//
//  implicit object ExperimentTypeTypeMapper extends BaseTypeMapper[ExperimentType] {
//    def apply(profile: BasicProfile) = new ExperimentTypeMapperDelegate(profile)
//  }
//
//  implicit object DomainTagStateTypeMapper extends BaseTypeMapper[State[DomainTag]] {
//    def apply(profile: BasicProfile) = new StateMapperDelegate[DomainTag](profile)
//  }
//
//  implicit object DomainStateTypeMapper extends BaseTypeMapper[State[Domain]] {
//    def apply(profile: BasicProfile) = new StateMapperDelegate[Domain](profile)
//  }
//
//  implicit object DomainToTagStateTypeMapper extends BaseTypeMapper[State[DomainToTag]] {
//    def apply(profile: BasicProfile) = new StateMapperDelegate[DomainToTag](profile)
//  }
//
//  implicit object UserToDomainKindTypeMapper extends BaseTypeMapper[State[UserToDomainKind]] {
//    def apply(profile: BasicProfile) = new StateMapperDelegate[UserToDomainKind](profile)
//  }

  //Other
//  implicit object UrlHashTypeMapper extends BaseTypeMapper[UrlHash] {
//    def apply(profile: BasicProfile) = new UrlHashTypeMapperDelegate(profile)
//  }


//  implicit object URLHistorySeqHistoryTypeMapper extends BaseTypeMapper[Seq[URLHistory]] {
//    def apply(profile: BasicProfile) = new URLHistorySeqMapperDelegate(profile)
//  }
//
//  implicit object ElectronicMailMessageIdTypeMapper extends BaseTypeMapper[ElectronicMailMessageId] {
//    def apply(profile: BasicProfile) = new ElectronicMailMessageIdMapperDelegate(profile)
//  }
//
//  implicit object ElectronicMailCategoryTypeMapper extends BaseTypeMapper[ElectronicMailCategory] {
//    def apply(profile: BasicProfile) = new ElectronicMailCategoryMapperDelegate(profile)
//  }
//
//  implicit object SystemEmailAddressTypeMapper extends BaseTypeMapper[SystemEmailAddress] {
//    def apply(profile: BasicProfile) = new SystemEmailAddressMapperDelegate(profile)
//  }
//
//  implicit object EmailAddressHolderTypeMapper extends BaseTypeMapper[EmailAddressHolder] {
//    def apply(profile: BasicProfile) = new EmailAddressHolderMapperDelegate(profile)
//  }
//
//  implicit object EmailAddressHolderListTypeMapper extends BaseTypeMapper[Seq[EmailAddressHolder]] {
//    def apply(profile: BasicProfile) = new EmailAddressHolderSeqMapperDelegate(profile)
//  }
//
//  implicit object BookmarkSourceHistoryTypeMapper extends BaseTypeMapper[BookmarkSource] {
//    def apply(profile: BasicProfile) = new BookmarkSourceMapperDelegate(profile)
//  }
//
//  implicit object SocialNetworkTypeTypeMapper extends BaseTypeMapper[SocialNetworkType] {
//    def apply(profile: BasicProfile) = new SocialNetworkTypeMapperDelegate(profile)
//  }
//
//  implicit object SocialUserHistoryTypeMapper extends BaseTypeMapper[SocialUser] {
//    def apply(profile: BasicProfile) = new SocialUserMapperDelegate(profile)
//  }
//
//  implicit object ABookOriginTypeMapper extends BaseTypeMapper[ABookOriginType] {
//    def apply(profile: BasicProfile) = new ABookOriginTypeMapperDelegate(profile)
//  }
//
//
//
//  implicit object SocialIdHistoryTypeMapper extends BaseTypeMapper[SocialId] {
//    def apply(profile: BasicProfile) = new SocialIdMapperDelegate(profile)
//  }
//
//  implicit object LargeStringTypeMapper extends BaseTypeMapper[LargeString] {
//    def apply(profile: BasicProfile) = new LargeStringMapperDelegate(profile)
//  }
//
//  implicit object ByteArrayTypeMapper extends BaseTypeMapper[Array[Byte]] {
//    def apply(profile: BasicProfile) = new ByteArrayMapperDelegate(profile)
//  }
//
//  implicit object DeepLinkTokenTypeMapper extends BaseTypeMapper[DeepLinkToken] {
//    def apply(profile: BasicProfile) = new DeepLinkTokenMapperDelegate(profile)
//  }
//
//  implicit object DeepLocatorTypeMapper extends BaseTypeMapper[DeepLocator] {
//    def apply(profile: BasicProfile) = new DeepLocatorMapperDelegate(profile)
//  }
//
//  implicit object NormalizationTypeMapper extends BaseTypeMapper[Normalization] {
//    def apply(profile: BasicProfile) = new NormalizationMapperDelegate(profile)
//  }
//
//  implicit object RestrictionTypeMapper extends BaseTypeMapper[Restriction] {
//    def apply(profile: BasicProfile) = new RestrictionMapperDelegate(profile)
//  }
//
//  implicit object JsArrayTypeMapper extends BaseTypeMapper[JsArray] {
//    def apply(profile: BasicProfile) = new JsArrayMapperDelegate(profile)
//  }
//
//  implicit object JsObjectTypeMapper extends BaseTypeMapper[JsObject] {
//    def apply(profile: BasicProfile) = new JsObjectMapperDelegate(profile)
//  }
//
//  implicit object JsValueTypeMapper extends BaseTypeMapper[JsValue] {
//    def apply(profile: BasicProfile) = new JsValueMapperDelegate(profile)
//  }
//
//  implicit object SearchConfigTypeMapper extends BaseTypeMapper[SearchConfig] {
//    def apply(profile: BasicProfile) = new SearchConfigMapperDelegate(profile)
//  }
//
//  implicit object KifiVersionTypeMapper extends BaseTypeMapper[KifiVersion] {
//    def apply(profile: BasicProfile) = new KifiVersionMapperDelegate(profile)
//  }
//
//  implicit object UserAgentTypeMapper extends BaseTypeMapper[UserAgent] {
//    def apply(profile: BasicProfile) = new UserAgentMapperDelegate(profile)
//  }
//
//  implicit object DomainTagNameTypeMapper extends BaseTypeMapper[DomainTagName] {
//    def apply(profile: BasicProfile) = new DomainTagNameMapperDelegate(profile)
//  }
//
//  implicit object LangTypeMapper extends BaseTypeMapper[Lang] {
//    def apply(profile: BasicProfile) = new LangMapperDelegate(profile)
//  }
//
//  implicit object OAuth2ProviderMapper extends BaseTypeMapper[OAuth2TokenIssuer] {
//    def apply(profile: BasicProfile) = new OAuth2ProviderMapperDelegate(profile)
//  }
//
//  implicit object UserPictureSourceIdTypeMapper extends BaseTypeMapper[UserPictureSource] {
//    def apply(profile:BasicProfile) = new UserPictureSourceMapperDelegate(profile)
//  }
//
//  implicit object SimpleKeyValueTypeMapper extends BaseTypeMapper[Map[String,String]] {
//    def apply(profile : BasicProfile) = new SimpleKeyValueMapperDelegate(profile)
//  }

}

////************************************
////       Abstract mappers
////************************************
//abstract class DelegateMapperDelegate[S, D] extends TypeMapperDelegate[S] {
//  protected def delegate: TypeMapperDelegate[D]
//  def sqlType = delegate.sqlType
//  def setValue(value: S, p: PositionedParameters) = delegate.setValue(sourceToDest(value), p)
//  def setOption(valueOpt: Option[S], p: PositionedParameters) = delegate.setOption(valueOpt map sourceToDest, p)
//  def nextValue(r: PositionedResult): S = destToSource(delegate.nextValue(r))
//  def updateValue(value: S, r: PositionedResult) = delegate.updateValue(sourceToDest(value), r)
//  override def valueToSQLLiteral(value: S) = delegate.valueToSQLLiteral(sourceToDest(value))
//  override def sqlTypeName = delegate.sqlTypeName
//
//  def destToSource(dest: D): S = Option(dest) match {
//    case None => zero
//    case Some(value) => safeDestToSource(dest)
//  }
//
//  def sourceToDest(dest: S): D
//  def safeDestToSource(source: D): S
//}
//
//abstract class StringMapperDelegate[T](val profile: BasicProfile) extends DelegateMapperDelegate[T, String] {
//  override val delegate = profile.typeMapperDelegates.stringTypeMapperDelegate
//}
//
////************************************
////       DateTime -> Timestamp
////************************************
//class DateTimeMapperDelegate(val profile: BasicProfile) extends DelegateMapperDelegate[DateTime, Timestamp] {
//  protected val delegate = profile.typeMapperDelegates.timestampTypeMapperDelegate
//  def zero = currentDateTime
//  def sourceToDest(value: DateTime): Timestamp = new Timestamp(value.toDate().getTime())
//  def safeDestToSource(value: Timestamp): DateTime = value.toDateTime
//}
//
////************************************
////       SequenceNumber -> Long
////************************************
//class SequenceNumberTypeMapperDelegate(val profile: BasicProfile)
//    extends DelegateMapperDelegate[SequenceNumber, Long] {
//  protected val delegate = profile.typeMapperDelegates.longTypeMapperDelegate
//  def zero = SequenceNumber.ZERO
//  def sourceToDest(value: SequenceNumber): Long = value.value
//  def safeDestToSource(value: Long): SequenceNumber = SequenceNumber(value)
//}
//
////************************************
////       UrlHash -> String
////************************************
//class UrlHashTypeMapperDelegate(val profile: BasicProfile)
//    extends DelegateMapperDelegate[UrlHash, String] {
//  protected val delegate = profile.typeMapperDelegates.stringTypeMapperDelegate
//  def zero = UrlHash("")
//  def sourceToDest(value: UrlHash): String = value.hash
//  def safeDestToSource(value: String): UrlHash = UrlHash(value)
//}
//
////************************************
////       Id -> Long
////************************************
//class IdMapperDelegate[T](val profile: BasicProfile) extends DelegateMapperDelegate[Id[T], Long] {
//  protected val delegate = profile.typeMapperDelegates.longTypeMapperDelegate
//  def zero = Id[T](0)
//  def sourceToDest(value: Id[T]): Long = value.id
//  def safeDestToSource(value: Long): Id[T] = Id[T](value)
//}
//
////************************************
////       ExternalId -> String
////************************************
//class ExternalIdMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[ExternalId[T]](profile) {
//  def zero = ExternalId[T]()
//  def sourceToDest(value: ExternalId[T]): String = value.id
//  def safeDestToSource(str: String): ExternalId[T] = ExternalId[T](str)
//}
//
////************************************
////       State -> String
////************************************
//class StateMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[State[T]](profile) {
//  def zero = new State("")
//  def sourceToDest(value: State[T]): String = value.value
//  def safeDestToSource(str: String): State[T] = State[T](str)
//}
//
////************************************
////       DeepLocator -> String
////************************************
//class DeepLocatorMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[DeepLocator](profile) {
//  def zero = DeepLocator("")
//  def sourceToDest(value: DeepLocator): String = value.value
//  def safeDestToSource(str: String): DeepLocator = DeepLocator(str)
//}
//
////************************************
////       DeepLinkToken -> String
////************************************
//class DeepLinkTokenMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[DeepLinkToken](profile) {
//  def zero = DeepLinkToken("")
//  def sourceToDest(value: DeepLinkToken): String = value.value
//  def safeDestToSource(str: String): DeepLinkToken = DeepLinkToken(str)
//}
//
////************************************
////       JsArray -> String
////************************************
//class JsArrayMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[JsArray](profile) {
//  def zero = JsArray()
//  def sourceToDest(value: JsArray): String = Json.stringify(value)
//  def safeDestToSource(str: String): JsArray = Json.parse(str).as[JsArray]
//}
//
////************************************
////       JsObject -> String
////************************************
//class JsObjectMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[JsObject](profile) {
//  def zero = JsObject(Nil)
//  def sourceToDest(value: JsObject): String = Json.stringify(value)
//  def safeDestToSource(str: String): JsObject = Json.parse(str).as[JsObject]
//}
//
////************************************
////       JsValue -> String
////************************************
//class JsValueMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[JsValue](profile) {
//  def zero = JsNull
//  def sourceToDest(value: JsValue): String = Json.stringify(value)
//  def safeDestToSource(str: String): JsValue = Json.parse(str)
//}
//
////************************************
////       SearchConfig -> String
////************************************
//class SearchConfigMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SearchConfig](profile) {
//  def zero = SearchConfig(Map[String, String]())
//  def sourceToDest(value: SearchConfig): String =
//    Json.stringify(JsObject(value.params.map { case (k, v) => k -> JsString(v) }.toSeq))
//  def safeDestToSource(str: String): SearchConfig =
//    SearchConfig(Json.parse(str).asInstanceOf[JsObject].fields.map { case (k, v) => k -> v.as[String] }.toMap)
//}
//
////************************************
////       Seq[URLHistory] -> String
////************************************
//class URLHistorySeqMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Seq[URLHistory]](profile) {
//  def zero = Nil
//  def sourceToDest(history: Seq[URLHistory]) = {
//    Json.stringify(Json.toJson(history))
//  }
//  def safeDestToSource(history: String) = {
//    val json = Json.parse(history)
//    Json.fromJson[Seq[URLHistory]](json).get
//  }
//}
//
////************************************
////       BookmarkSource -> String
////************************************
//class BookmarkSourceMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[BookmarkSource](profile) {
//  def zero = BookmarkSource("")
//  def sourceToDest(value: BookmarkSource): String = value.value
//  def safeDestToSource(str: String): BookmarkSource = BookmarkSource(str)
//}
//
//
////************************************
////       SocialNetworkType -> String
////************************************
//class SocialNetworkTypeMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SocialNetworkType](profile) {
//  def zero = SocialNetworks.FACEBOOK
//  def sourceToDest(socialNetworkType: SocialNetworkType) = SocialNetworkType.unapply(socialNetworkType).get
//  def safeDestToSource(str: String) = SocialNetworkType(str)
//}
//
////************************************
////       SocialUser -> String
////************************************
//class SocialUserMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SocialUser](profile) {
//  def zero = SocialUser(identityId = IdentityId("", ""), firstName = "", lastName = "",
//    fullName = "", authMethod = AuthenticationMethod.OAuth2, email = None, avatarUrl = None)
//  def sourceToDest(socialUser: SocialUser) = SocialUserSerializer.userSerializer.writes(socialUser).toString
//  def safeDestToSource(str: String) = SocialUserSerializer.userSerializer.reads(Json.parse(str)).get
//}
//
////************************************
////       ABookOriginType -> String
////************************************
//class ABookOriginTypeMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[ABookOriginType](profile) {
//  def zero = ABookOrigins.IOS
//  def sourceToDest(abookOriginType:ABookOriginType) = ABookOriginType.unapply(abookOriginType).get
//  def safeDestToSource(str:String) = ABookOriginType(str)
//}
//
////************************************
////       SocialId -> String
////************************************
//class SocialIdMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SocialId](profile) {
//  def zero = SocialId("")
//  def sourceToDest(socialId: SocialId) = socialId.id
//  def safeDestToSource(str: String) = SocialId(str)
//}
//
////************************************
////       KifiVersion -> String
////************************************
//class KifiVersionMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[KifiVersion](profile) {
//  def zero = KifiVersion(0, 0, 0)
//  def sourceToDest(version: KifiVersion) = version.toString
//  def safeDestToSource(str: String) = KifiVersion(str)
//}
//
////************************************
////       UserAgent -> String
////************************************
//class UserAgentMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[UserAgent](profile) {
//  def zero = UserAgent.fromString("")
//  def sourceToDest(value: UserAgent) = value.userAgent
//  def safeDestToSource(str: String) = UserAgent.fromString(str)
//}
//
////************************************
////       ElectronicMailMessageId -> String
////************************************
//class ElectronicMailMessageIdMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[ElectronicMailMessageId](profile) {
//  def zero = ElectronicMailMessageId("")
//  def sourceToDest(value: ElectronicMailMessageId) = value.id
//  def safeDestToSource(str: String) = ElectronicMailMessageId(str)
//}
//
////************************************
////       ElectronicMailCategory -> String
////************************************
//class ElectronicMailCategoryMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[ElectronicMailCategory](profile) {
//  def zero = ElectronicMailCategory("")
//  def sourceToDest(value: ElectronicMailCategory) = value.category
//  def safeDestToSource(str: String) = ElectronicMailCategory(str)
//}
//
////************************************
////       SystemEmailAddress -> String
////************************************
//class SystemEmailAddressMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SystemEmailAddress](profile) {
//  def zero = EmailAddresses.TEAM
//  def sourceToDest(value: SystemEmailAddress) = value.address
//  def safeDestToSource(str: String) = EmailAddresses(str)
//}
//
////************************************
////       EmailAddressHolder -> String
////************************************
//class EmailAddressHolderMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[EmailAddressHolder](profile) {
//  def zero = new GenericEmailAddress("")
//  def sourceToDest(value: EmailAddressHolder) = value.address
//  def safeDestToSource(str: String) = new GenericEmailAddress(str.trim)
//}
//
////************************************
////       Seq[EmailAddressHolder] -> String
////************************************
//class EmailAddressHolderSeqMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Seq[EmailAddressHolder]](profile) {
//  def zero = Seq[EmailAddressHolder]()
//  def sourceToDest(value: Seq[EmailAddressHolder]) = value map {e => e.address} mkString(",")
//  def safeDestToSource(str: String) = str.trim match {
//    case "" => zero
//    case trimmed => trimmed.split(",") map { addr => new GenericEmailAddress(addr.trim) }
//  }
//}
//
////************************************
////       DomainTagName -> String
////************************************
//class DomainTagNameMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[DomainTagName](profile) {
//  def zero = DomainTagName("")
//  def sourceToDest(value: DomainTagName) = value.name
//  def safeDestToSource(str: String) = DomainTagName(str)
//}
//
////************************************
////       LargeString -> Clob
////************************************
//class LargeStringMapperDelegate(val profile: BasicProfile) extends DelegateMapperDelegate[LargeString, Clob] {
//  protected val delegate = profile.typeMapperDelegates.clobTypeMapperDelegate
//  def zero = LargeString("")
//  def sourceToDest(value: LargeString): Clob = {
//    new SerialClob(value.value.toCharArray()) {
//      override def getSubString(pos: Long, length: Int): String = {
//        // workaround for an empty clob problem
//        if (pos == 1 && length == 0) "" else super.getSubString(pos, length)
//      }
//    }
//  }
//  def safeDestToSource(value: Clob): LargeString = {
//    val clob = new SerialClob(value)
//    clob.length match {
//      case empty if (empty <= 0) => zero
//      case length => LargeString(clob.getSubString(1, length.intValue()))
//    }
//  }
//}
//
////************************************
////       Array[Byte] -> Blob
////************************************
//class ByteArrayMapperDelegate(val profile: BasicProfile) extends DelegateMapperDelegate[Array[Byte], Blob] {
//  protected val delegate = profile.typeMapperDelegates.blobTypeMapperDelegate
//  def zero = new Array[Byte](0)
//  def sourceToDest(value: Array[Byte]): Blob = new SerialBlob(value)
//  def safeDestToSource(value: Blob): Array[Byte] = {
//    val blob = new SerialBlob(value)
//    blob.length match {
//      case empty if (empty <= 0) => zero
//      case length => blob.getBytes(1, length.toInt)
//    }
//  }
//}
//
////************************************
////       Lang -> String
////************************************
//class LangMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Lang](profile) {
//  def zero = Lang("")
//  def sourceToDest(value: Lang) = value.lang
//  def safeDestToSource(str: String) = Lang(str)
//}
//
////************************************
////       OAuth2Provider -> String
////************************************
//class OAuth2ProviderMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[OAuth2TokenIssuer](profile) {
//  def zero = OAuth2TokenIssuer("")
//  def sourceToDest(value: OAuth2TokenIssuer) = value.name
//  def safeDestToSource(str: String) = OAuth2TokenIssuer(str)
//}
//
////************************************
////       UserPictureSourceMapperDelegate -> String
////************************************
//class UserPictureSourceMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[UserPictureSource](profile) {
//  def zero = UserPictureSource("")
//  def sourceToDest(value: UserPictureSource) = value.name
//  def safeDestToSource(str: String) = UserPictureSource(str)
//}
//
////************************************
////       ExperimentType -> String
////************************************
//class ExperimentTypeMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[ExperimentType](profile) {
//  def zero = ExperimentType("")
//  def sourceToDest(value: ExperimentType): String = value.value
//  def safeDestToSource(str: String): ExperimentType = ExperimentType(str)
//}
//
////************************************
////       Normalization -> String
////************************************
//class NormalizationMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Normalization](profile) {
//  def zero = Normalization("")
//  def sourceToDest(value: Normalization): String = value.scheme
//  def safeDestToSource(str: String): Normalization = Normalization(str)
//}
//
////************************************
////       Restriction -> String
////************************************
//class RestrictionMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Restriction](profile) {
//  def zero = Restriction("")
//  def sourceToDest(value: Restriction): String = value.context
//  def safeDestToSource(str: String): Restriction = Restriction(str)
//}
//
////******************************************
////       Map[String,String] -> String (json)
////******************************************
//class SimpleKeyValueMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Map[String,String]](profile) {
//  def zero = Map[String,String]()
//  def sourceToDest(value: Map[String,String]): String = Json.stringify(JsObject(value.mapValues(JsString(_)).toSeq))
//  def safeDestToSource(str: String): Map[String,String] = Json.parse(str).as[JsObject].fields.toMap.mapValues(_.as[JsString].value)
//}

