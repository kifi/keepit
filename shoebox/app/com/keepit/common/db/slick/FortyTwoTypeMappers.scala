package com.keepit.common.db.slick

import com.keepit.classify.DomainTag
import com.keepit.classify.DomainToTag
import com.keepit.classify.{DomainTagName, Domain}
import com.keepit.common.db._
import com.keepit.common.db.{Id, State, Model, ExternalId, LargeString}
import com.keepit.common.mail._
import com.keepit.common.social.SocialId
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.common.net.UserAgent
import com.keepit.model._
import com.keepit.search.ArticleSearchResultRef
import com.keepit.search.Lang
import com.keepit.search.SearchConfigExperiment
import com.keepit.search._
import com.keepit.serializer.{URLHistorySerializer => URLHS, SocialUserSerializer}
import java.sql.{Timestamp, Clob, Blob}
import javax.sql.rowset.serial.{SerialBlob, SerialClob}
import org.joda.time.DateTime
import play.api.libs.json.JsArray
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json._
import scala.Some
import scala.slick.driver._
import scala.slick.lifted.{BaseTypeMapper, TypeMapperDelegate}
import scala.slick.session.{PositionedParameters, PositionedResult}
import securesocial.core.AuthenticationMethod
import securesocial.core.SocialUser
import securesocial.core.UserId

object FortyTwoTypeMappers {
  // Time
  implicit object DateTimeTypeMapper extends BaseTypeMapper[DateTime] {
    def apply(profile: BasicProfile) = new DateTimeMapperDelegate(profile)
  }

  implicit object GenericIdTypeMapper extends BaseTypeMapper[Id[Model[_]]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Model[_]](profile)
  }

  //ExternalIds
  implicit object ArticleSearchResultRefExternalIdTypeMapper extends BaseTypeMapper[ExternalId[ArticleSearchResultRef]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[ArticleSearchResultRef](profile)
  }

  implicit object KifiInstallationExternalIdTypeMapper extends BaseTypeMapper[ExternalId[KifiInstallation]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[KifiInstallation](profile)
  }

  implicit object NormalizedURIExternalIdTypeMapper extends BaseTypeMapper[ExternalId[NormalizedURI]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[NormalizedURI](profile)
  }

  implicit object UserNotificationExternalIdTypeMapper extends BaseTypeMapper[ExternalId[UserNotification]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[UserNotification](profile)
  }

  //Ids
  implicit object CommentIdTypeMapper extends BaseTypeMapper[Id[Comment]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Comment](profile)
  }

  implicit object SocialUserInfoIdTypeMapper extends BaseTypeMapper[Id[SocialUserInfo]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[SocialUserInfo](profile)
  }

  implicit object FollowIdTypeMapper extends BaseTypeMapper[Id[Follow]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Follow](profile)
  }

  implicit object UserIdTypeMapper extends BaseTypeMapper[Id[User]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[User](profile)
  }

  implicit object URLIdTypeMapper extends BaseTypeMapper[Id[URL]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[URL](profile)
  }

  implicit object UnscrapableIdTypeMapper extends BaseTypeMapper[Id[Unscrapable]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Unscrapable](profile)
  }

  implicit object NormalizedURIIdTypeMapper extends BaseTypeMapper[Id[NormalizedURI]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[NormalizedURI](profile)
  }

  implicit object DomainToTagIdTypeMapper extends BaseTypeMapper[Id[DomainToTag]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[DomainToTag](profile)
  }

  implicit object DomainIdTypeMapper extends BaseTypeMapper[Id[Domain]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Domain](profile)
  }

  implicit object DomainTagIdTypeMapper extends BaseTypeMapper[Id[DomainTag]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[DomainTag](profile)
  }

  implicit object SearchConfigExperimentIdTypeMapper extends BaseTypeMapper[Id[SearchConfigExperiment]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[SearchConfigExperiment](profile)
  }

  implicit object UserNotificationIdTypeMapper extends BaseTypeMapper[Id[UserNotification]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[UserNotification](profile)
  }

  //States
  implicit object NormalizedURIStateTypeMapper extends BaseTypeMapper[State[NormalizedURI]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[NormalizedURI](profile)
  }

  implicit object ExperimentTypeStateTypeMapper extends BaseTypeMapper[State[ExperimentType]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[ExperimentType](profile)
  }

  implicit object CommentPermissionTypeStateTypeMapper extends BaseTypeMapper[State[CommentPermission]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[CommentPermission](profile)
  }

  implicit object DomainTagStateTypeMapper extends BaseTypeMapper[State[DomainTag]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[DomainTag](profile)
  }

  implicit object DomainStateTypeMapper extends BaseTypeMapper[State[Domain]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[Domain](profile)
  }

  implicit object DomainToTagStateTypeMapper extends BaseTypeMapper[State[DomainToTag]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[DomainToTag](profile)
  }

  implicit object UserToDomainKindTypeMapper extends BaseTypeMapper[State[UserToDomainKind]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[UserToDomainKind](profile)
  }

  implicit object UserNotificationStateMapper extends BaseTypeMapper[State[UserNotification]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[UserNotification](profile)
  }

  //Other
  implicit object SequenceNumberTypeMapper extends BaseTypeMapper[SequenceNumber] {
    def apply(profile: BasicProfile) = new SequenceNumberTypeMapperDelegate(profile)
  }

  implicit object URLHistorySeqHistoryTypeMapper extends BaseTypeMapper[Seq[URLHistory]] {
    def apply(profile: BasicProfile) = new URLHistorySeqMapperDelegate(profile)
  }

  implicit object ElectronicMailMessageIdTypeMapper extends BaseTypeMapper[ElectronicMailMessageId] {
    def apply(profile: BasicProfile) = new ElectronicMailMessageIdMapperDelegate(profile)
  }

  implicit object ElectronicMailCategoryTypeMapper extends BaseTypeMapper[ElectronicMailCategory] {
    def apply(profile: BasicProfile) = new ElectronicMailCategoryMapperDelegate(profile)
  }

  implicit object SystemEmailAddressTypeMapper extends BaseTypeMapper[SystemEmailAddress] {
    def apply(profile: BasicProfile) = new SystemEmailAddressMapperDelegate(profile)
  }

  implicit object EmailAddressHolderTypeMapper extends BaseTypeMapper[EmailAddressHolder] {
    def apply(profile: BasicProfile) = new EmailAddressHolderMapperDelegate(profile)
  }

  implicit object EmailAddressHolderListTypeMapper extends BaseTypeMapper[Seq[EmailAddressHolder]] {
    def apply(profile: BasicProfile) = new EmailAddressHolderSeqMapperDelegate(profile)
  }

  implicit object BookmarkSourceHistoryTypeMapper extends BaseTypeMapper[BookmarkSource] {
    def apply(profile: BasicProfile) = new BookmarkSourceMapperDelegate(profile)
  }

  implicit object SocialNetworkTypeHistoryTypeMapper extends BaseTypeMapper[SocialNetworkType] {
    def apply(profile: BasicProfile) = new SocialNetworkTypeMapperDelegate(profile)
  }

  implicit object SocialUserHistoryTypeMapper extends BaseTypeMapper[SocialUser] {
    def apply(profile: BasicProfile) = new SocialUserMapperDelegate(profile)
  }

  implicit object SocialIdHistoryTypeMapper extends BaseTypeMapper[SocialId] {
    def apply(profile: BasicProfile) = new SocialIdMapperDelegate(profile)
  }

  implicit object LargeStringTypeMapper extends BaseTypeMapper[LargeString] {
    def apply(profile: BasicProfile) = new LargeStringMapperDelegate(profile)
  }

  implicit object ByteArrayTypeMapper extends BaseTypeMapper[Array[Byte]] {
    def apply(profile: BasicProfile) = new ByteArrayMapperDelegate(profile)
  }

  implicit object DeepLinkTokenTypeMapper extends BaseTypeMapper[DeepLinkToken] {
    def apply(profile: BasicProfile) = new DeepLinkTokenMapperDelegate(profile)
  }

  implicit object DeepLocatorTypeMapper extends BaseTypeMapper[DeepLocator] {
    def apply(profile: BasicProfile) = new DeepLocatorMapperDelegate(profile)
  }

  implicit object JsArrayTypeMapper extends BaseTypeMapper[JsArray] {
    def apply(profile: BasicProfile) = new JsArrayMapperDelegate(profile)
  }

  implicit object SearchConfigTypeMapper extends BaseTypeMapper[SearchConfig] {
    def apply(profile: BasicProfile) = new SearchConfigMapperDelegate(profile)
  }

  implicit object KifiVersionTypeMapper extends BaseTypeMapper[KifiVersion] {
    def apply(profile: BasicProfile) = new KifiVersionMapperDelegate(profile)
  }

  implicit object UserAgentTypeMapper extends BaseTypeMapper[UserAgent] {
    def apply(profile: BasicProfile) = new UserAgentMapperDelegate(profile)
  }

  implicit object DomainTagNameTypeMapper extends BaseTypeMapper[DomainTagName] {
    def apply(profile: BasicProfile) = new DomainTagNameMapperDelegate(profile)
  }

  implicit object LangTypeMapper extends BaseTypeMapper[Lang] {
    def apply(profile: BasicProfile) = new LangMapperDelegate(profile)
  }

  implicit object UserNotificationCategoryTypeMapper extends BaseTypeMapper[UserNotificationCategory] {
    def apply(profile: BasicProfile) = new UserNotificationCategoryMapperDelegate(profile)
  }

  implicit object UserNotificationDetailsTypeMapper extends BaseTypeMapper[UserNotificationDetails] {
    def apply(profile: BasicProfile) = new UserNotificationDetailsMapperDelegate(profile)
  }
}

//************************************
//       Abstract mappers
//************************************
abstract class DelegateMapperDelegate[S, D] extends TypeMapperDelegate[S] {
  protected def delegate: TypeMapperDelegate[D]
  def sqlType = delegate.sqlType
  def setValue(value: S, p: PositionedParameters) = delegate.setValue(sourceToDest(value), p)
  def setOption(valueOpt: Option[S], p: PositionedParameters) = delegate.setOption(valueOpt map sourceToDest, p)
  def nextValue(r: PositionedResult): S = destToSource(delegate.nextValue(r))
  def updateValue(value: S, r: PositionedResult) = delegate.updateValue(sourceToDest(value), r)
  override def valueToSQLLiteral(value: S) = delegate.valueToSQLLiteral(sourceToDest(value))
  override def sqlTypeName = delegate.sqlTypeName

  def destToSource(dest: D): S = Option(dest) match {
    case None => zero
    case Some(value) => safeDestToSource(dest)
  }

  def sourceToDest(dest: S): D
  def safeDestToSource(source: D): S
}

abstract class StringMapperDelegate[T](val profile: BasicProfile) extends DelegateMapperDelegate[T, String] {
  override val delegate = profile.typeMapperDelegates.stringTypeMapperDelegate
}

//************************************
//       DateTime -> Timestamp
//************************************
class DateTimeMapperDelegate(val profile: BasicProfile) extends DelegateMapperDelegate[DateTime, Timestamp] {
  protected val delegate = profile.typeMapperDelegates.timestampTypeMapperDelegate
  def zero = currentDateTime
  def sourceToDest(value: DateTime): Timestamp = new Timestamp(value.toDate().getTime())
  def safeDestToSource(value: Timestamp): DateTime = value.toDateTime
}

//************************************
//       SequenceNumber -> Long
//************************************
class SequenceNumberTypeMapperDelegate(val profile: BasicProfile)
    extends DelegateMapperDelegate[SequenceNumber, Long] {
  protected val delegate = profile.typeMapperDelegates.longTypeMapperDelegate
  def zero = SequenceNumber.ZERO
  def sourceToDest(value: SequenceNumber): Long = value.value
  def safeDestToSource(value: Long): SequenceNumber = SequenceNumber(value)
}

//************************************
//       Id -> Long
//************************************
class IdMapperDelegate[T](val profile: BasicProfile) extends DelegateMapperDelegate[Id[T], Long] {
  protected val delegate = profile.typeMapperDelegates.longTypeMapperDelegate
  def zero = Id[T](0)
  def sourceToDest(value: Id[T]): Long = value.id
  def safeDestToSource(value: Long): Id[T] = Id[T](value)
}

//************************************
//       ExternalId -> String
//************************************
class ExternalIdMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[ExternalId[T]](profile) {
  def zero = ExternalId[T]()
  def sourceToDest(value: ExternalId[T]): String = value.id
  def safeDestToSource(str: String): ExternalId[T] = ExternalId[T](str)
}

//************************************
//       State -> String
//************************************
class StateMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[State[T]](profile) {
  def zero = new State("")
  def sourceToDest(value: State[T]): String = value.value
  def safeDestToSource(str: String): State[T] = State[T](str)
}

//************************************
//       DeepLocator -> String
//************************************
class DeepLocatorMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[DeepLocator](profile) {
  def zero = DeepLocator("")
  def sourceToDest(value: DeepLocator): String = value.value
  def safeDestToSource(str: String): DeepLocator = DeepLocator(str)
}

//************************************
//       DeepLinkToken -> String
//************************************
class DeepLinkTokenMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[DeepLinkToken](profile) {
  def zero = DeepLinkToken("")
  def sourceToDest(value: DeepLinkToken): String = value.value
  def safeDestToSource(str: String): DeepLinkToken = DeepLinkToken(str)
}

//************************************
//       JsArray -> String
//************************************
class JsArrayMapperDelegate[T](profile: BasicProfile) extends StringMapperDelegate[JsArray](profile) {
  def zero = JsArray()
  def sourceToDest(value: JsArray): String = Json.stringify(value)
  def safeDestToSource(str: String): JsArray = Json.parse(str).asInstanceOf[JsArray]
}

//************************************
//       SearchConfig -> String
//************************************
class SearchConfigMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SearchConfig](profile) {
  def zero = SearchConfig(Map[String, String]())
  def sourceToDest(value: SearchConfig): String =
    Json.stringify(JsObject(value.params.map { case (k, v) => k -> JsString(v) }.toSeq))
  def safeDestToSource(str: String): SearchConfig =
    SearchConfig(Json.parse(str).asInstanceOf[JsObject].fields.map { case (k, v) => k -> v.as[String] }.toMap)
}

//************************************
//       Seq[URLHistory] -> String
//************************************
class URLHistorySeqMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Seq[URLHistory]](profile) {
  def zero = Nil
  def sourceToDest(history: Seq[URLHistory]) = {
    val serializer = URLHS.urlHistorySerializer
    Json.stringify(serializer.writes(history))
  }
  def safeDestToSource(history: String) = {
    val json = Json.parse(history)
    val serializer = URLHS.urlHistorySerializer
    serializer.reads(json).get
  }
}

//************************************
//       BookmarkSource -> String
//************************************
class BookmarkSourceMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[BookmarkSource](profile) {
  def zero = BookmarkSource("")
  def sourceToDest(value: BookmarkSource): String = value.value
  def safeDestToSource(str: String): BookmarkSource = BookmarkSource(str)
}


//************************************
//       SocialNetworkType -> String
//************************************
class SocialNetworkTypeMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SocialNetworkType](profile) {
  def zero = SocialNetworks.FACEBOOK
  def sourceToDest(socialNetworkType: SocialNetworkType) = socialNetworkType.name
  def safeDestToSource(str: String) = str match {
    case SocialNetworks.FACEBOOK.name => SocialNetworks.FACEBOOK
    case _ => throw new RuntimeException("unknown network type %s".format(str))
  }
}

//************************************
//       SocialNetworkType -> String
//************************************
class SocialUserMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SocialUser](profile) {
  def zero = new SocialUser(id = UserId("", ""), firstName = "", lastName = "",
    fullName = "", authMethod = AuthenticationMethod.OAuth2, email = None, avatarUrl = None)
  def sourceToDest(socialUser: SocialUser) = SocialUserSerializer.userSerializer.writes(socialUser).toString
  def safeDestToSource(str: String) = SocialUserSerializer.userSerializer.reads(Json.parse(str)).get
}

//************************************
//       SocialId -> String
//************************************
class SocialIdMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SocialId](profile) {
  def zero = SocialId("")
  def sourceToDest(socialId: SocialId) = socialId.id
  def safeDestToSource(str: String) = SocialId(str)
}

//************************************
//       KifiVersion -> String
//************************************
class KifiVersionMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[KifiVersion](profile) {
  def zero = KifiVersion(0, 0, 0)
  def sourceToDest(version: KifiVersion) = version.toString
  def safeDestToSource(str: String) = KifiVersion(str)
}

//************************************
//       UserAgent -> String
//************************************
class UserAgentMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[UserAgent](profile) {
  def zero = UserAgent.fromString("")
  def sourceToDest(value: UserAgent) = value.userAgent
  def safeDestToSource(str: String) = UserAgent.fromString(str)
}

//************************************
//       ElectronicMailMessageId -> String
//************************************
class ElectronicMailMessageIdMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[ElectronicMailMessageId](profile) {
  def zero = ElectronicMailMessageId("")
  def sourceToDest(value: ElectronicMailMessageId) = value.id
  def safeDestToSource(str: String) = ElectronicMailMessageId(str)
}

//************************************
//       ElectronicMailCategory -> String
//************************************
class ElectronicMailCategoryMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[ElectronicMailCategory](profile) {
  def zero = ElectronicMailCategory("")
  def sourceToDest(value: ElectronicMailCategory) = value.category
  def safeDestToSource(str: String) = ElectronicMailCategory(str)
}

//************************************
//       SystemEmailAddress -> String
//************************************
class SystemEmailAddressMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[SystemEmailAddress](profile) {
  def zero = EmailAddresses.TEAM
  def sourceToDest(value: SystemEmailAddress) = value.address
  def safeDestToSource(str: String) = EmailAddresses(str)
}

//************************************
//       EmailAddressHolder -> String
//************************************
class EmailAddressHolderMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[EmailAddressHolder](profile) {
  def zero = new GenericEmailAddress("")
  def sourceToDest(value: EmailAddressHolder) = value.address
  def safeDestToSource(str: String) = new GenericEmailAddress(str.trim)
}

//************************************
//       Seq[EmailAddressHolder] -> String
//************************************
class EmailAddressHolderSeqMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Seq[EmailAddressHolder]](profile) {
  def zero = Seq[EmailAddressHolder]()
  def sourceToDest(value: Seq[EmailAddressHolder]) = value map {e => e.address} mkString(",")
  def safeDestToSource(str: String) = str.trim match {
    case "" => zero
    case trimmed => trimmed.split(",") map { addr => new GenericEmailAddress(addr.trim) }
  }
}

//************************************
//       DomainTagName -> String
//************************************
class DomainTagNameMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[DomainTagName](profile) {
  def zero = DomainTagName("")
  def sourceToDest(value: DomainTagName) = value.name
  def safeDestToSource(str: String) = DomainTagName(str)
}

//************************************
//       LargeString -> Clob
//************************************
class LargeStringMapperDelegate(val profile: BasicProfile) extends DelegateMapperDelegate[LargeString, Clob] {
  protected val delegate = profile.typeMapperDelegates.clobTypeMapperDelegate
  def zero = LargeString("")
  def sourceToDest(value: LargeString): Clob = new SerialClob(value.value.toCharArray())
  def safeDestToSource(value: Clob): LargeString = {
    val clob = new SerialClob(value)
    clob.length match {
      case empty if (empty <= 0) => zero
      case length => LargeString(clob.getSubString(1, length.intValue()))
    }
  }
}

//************************************
//       Array[Byte] -> Blob
//************************************
class ByteArrayMapperDelegate(val profile: BasicProfile) extends DelegateMapperDelegate[Array[Byte], Blob] {
  protected val delegate = profile.typeMapperDelegates.blobTypeMapperDelegate
  def zero = new Array[Byte](0)
  def sourceToDest(value: Array[Byte]): Blob = new SerialBlob(value)
  def safeDestToSource(value: Blob): Array[Byte] = {
    val blob = new SerialBlob(value)
    blob.length match {
      case empty if (empty <= 0) => zero
      case length => blob.getBytes(1, length.toInt)
    }
  }
}

//************************************
//       Lang -> String
//************************************
class LangMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[Lang](profile) {
  def zero = Lang("")
  def sourceToDest(value: Lang) = value.lang
  def safeDestToSource(str: String) = Lang(str)
}

//************************************
//       UserNotificationCategory -> String
//************************************
class UserNotificationCategoryMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[UserNotificationCategory](profile) {
  def zero = UserNotificationCategory("")
  def sourceToDest(value: UserNotificationCategory) = value.name
  def safeDestToSource(str: String) = UserNotificationCategory(str)
}

//************************************
//       UserNotificationDetails -> String
//************************************
class UserNotificationDetailsMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[UserNotificationDetails](profile) {
  def zero = UserNotificationDetails(Json.obj())
  def sourceToDest(value: UserNotificationDetails) = Json.stringify(value.payload)
  def safeDestToSource(str: String) = UserNotificationDetails(Json.parse(str).asInstanceOf[JsObject])
}

