package com.keepit.common.db.slick

import securesocial.core.{SocialUser, UserId, AuthenticationMethod}
import org.scalaquery.ql.{TypeMapper, TypeMapperDelegate, BaseTypeMapper}
import org.scalaquery.ql.basic.BasicProfile
import org.scalaquery.session.{PositionedParameters, PositionedResult}
import com.keepit.common.db.{Id, State, Model, ExternalId, LargeString}
import com.keepit.common.time._
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.search._
import com.keepit.common.mail._
import org.joda.time.DateTime
import java.sql.{Timestamp, Clob, Blob, PreparedStatement}
import javax.sql.rowset.serial.{SerialBlob, SerialClob}
import play.api.libs.json._
import org.scalaquery.ql.basic.BasicTypeMapperDelegates._
import com.keepit.serializer.{URLHistorySerializer => URLHS, SocialUserSerializer}
import java.io.BufferedReader
import java.io.StringReader

object FortyTwoTypeMappers {
  // Time
  implicit object DateTimeTypeMapper extends BaseTypeMapper[DateTime] {
    def apply(profile: BasicProfile) = new DateTimeMapperDelegate
  }

  implicit object GenericIdTypeMapper extends BaseTypeMapper[Id[Model[_]]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Model[_]]
  }

  //ExternalIds
  implicit object ArticleSearchResultRefExternalIdTypeMapper extends BaseTypeMapper[ExternalId[ArticleSearchResultRef]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[ArticleSearchResultRef]
  }

  implicit object KifiInstallationExternalIdTypeMapper extends BaseTypeMapper[ExternalId[KifiInstallation]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[KifiInstallation]
  }

  implicit object NormalizedURIExternalIdTypeMapper extends BaseTypeMapper[ExternalId[NormalizedURI]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[NormalizedURI]
  }

  //Ids
  implicit object CommentIdTypeMapper extends BaseTypeMapper[Id[Comment]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Comment]
  }

  implicit object SocialUserInfoIdTypeMapper extends BaseTypeMapper[Id[SocialUserInfo]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[SocialUserInfo]
  }

  implicit object FollowIdTypeMapper extends BaseTypeMapper[Id[Follow]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Follow]
  }

  implicit object UserIdTypeMapper extends BaseTypeMapper[Id[User]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[User]
  }

  implicit object URLIdTypeMapper extends BaseTypeMapper[Id[URL]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[URL]
  }

  implicit object UnscrapableIdTypeMapper extends BaseTypeMapper[Id[Unscrapable]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Unscrapable]
  }

  implicit object NormalizedURIIdTypeMapper extends BaseTypeMapper[Id[NormalizedURI]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[NormalizedURI]
  }

  //States

  implicit object NormalizedURIStateTypeMapper extends BaseTypeMapper[State[NormalizedURI]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[NormalizedURI]
  }

  implicit object ExperimentTypeStateTypeMapper extends BaseTypeMapper[State[ExperimentType]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[ExperimentType]
  }

  implicit object CommentPermissionTypeStateTypeMapper extends BaseTypeMapper[State[CommentPermission]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[CommentPermission]
  }

  //Other
  implicit object URLHistorySeqHistoryTypeMapper extends BaseTypeMapper[Seq[URLHistory]] {
    def apply(profile: BasicProfile) = new URLHistorySeqMapperDelegate
  }

  implicit object ElectronicMailMessageIdTypeMapper extends BaseTypeMapper[ElectronicMailMessageId] {
    def apply(profile: BasicProfile) = new ElectronicMailMessageIdMapperDelegate
  }

  implicit object ElectronicMailCategoryTypeMapper extends BaseTypeMapper[ElectronicMailCategory] {
    def apply(profile: BasicProfile) = new ElectronicMailCategoryMapperDelegate
  }

  implicit object SystemEmailAddressTypeMapper extends BaseTypeMapper[SystemEmailAddress] {
    def apply(profile: BasicProfile) = new SystemEmailAddressMapperDelegate
  }

  implicit object EmailAddressHolderTypeMapper extends BaseTypeMapper[EmailAddressHolder] {
    def apply(profile: BasicProfile) = new EmailAddressHolderMapperDelegate
  }

  implicit object BookmarkSourceHistoryTypeMapper extends BaseTypeMapper[BookmarkSource] {
    def apply(profile: BasicProfile) = new BookmarkSourceMapperDelegate
  }

  implicit object SocialNetworkTypeHistoryTypeMapper extends BaseTypeMapper[SocialNetworkType] {
    def apply(profile: BasicProfile) = new SocialNetworkTypeMapperDelegate
  }

  implicit object SocialUserHistoryTypeMapper extends BaseTypeMapper[SocialUser] {
    def apply(profile: BasicProfile) = new SocialUserMapperDelegate
  }

  implicit object SocialIdHistoryTypeMapper extends BaseTypeMapper[SocialId] {
    def apply(profile: BasicProfile) = new SocialIdMapperDelegate
  }

  implicit object LargeStringTypeMapper extends BaseTypeMapper[LargeString] {
    def apply(profile: BasicProfile) = new LargeStringMapperDelegate
  }

  implicit object ByteArrayTypeMapper extends BaseTypeMapper[Array[Byte]] {
    def apply(profile: BasicProfile) = new ByteArrayMapperDelegate
  }

  implicit object DeepLinkTokenTypeMapper extends BaseTypeMapper[DeepLinkToken] {
    def apply(profile: BasicProfile) = new DeepLinkTokenMapperDelegate
  }

  implicit object DeepLocatorTypeMapper extends BaseTypeMapper[DeepLocator] {
    def apply(profile: BasicProfile) = new DeepLocatorMapperDelegate
  }

  implicit object KifiVersionTypeMapper extends BaseTypeMapper[KifiVersion] {
    def apply(profile: BasicProfile) = new KifiVersionMapperDelegate
  }

  implicit object UserAgentTypeMapper extends BaseTypeMapper[UserAgent] {
    def apply(profile: BasicProfile) = new UserAgentMapperDelegate
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

  def destToSource(dest: D): S = Option(dest) match {
    case None => zero
    case Some(value) => safeDestToSource(dest)
  }

  def sourceToDest(dest: S): D
  def safeDestToSource(source: D): S
}

abstract class StringMapperDelegate[T] extends DelegateMapperDelegate[T, String] {
  override val delegate = new StringTypeMapperDelegate()
}

//************************************
//       DateTime -> Timestamp
//************************************
class DateTimeMapperDelegate extends DelegateMapperDelegate[DateTime, Timestamp] {
  protected val delegate = new TimestampTypeMapperDelegate()
  def zero = currentDateTime
  def sourceToDest(value: DateTime): Timestamp = new Timestamp(value.toDate().getTime())
  def safeDestToSource(value: Timestamp): DateTime = value
}

//************************************
//       Id -> Long
//************************************
class IdMapperDelegate[T] extends DelegateMapperDelegate[Id[T], Long] {
  protected val delegate = new LongTypeMapperDelegate()
  def zero = Id[T](0)
  def sourceToDest(value: Id[T]): Long = value.id
  def safeDestToSource(value: Long): Id[T] = Id[T](value)
}

//************************************
//       ExternalId -> String
//************************************
class ExternalIdMapperDelegate[T] extends StringMapperDelegate[ExternalId[T]] {
  def zero = ExternalId[T]()
  def sourceToDest(value: ExternalId[T]): String = value.id
  def safeDestToSource(str: String): ExternalId[T] = ExternalId[T](str)
}

//************************************
//       State -> String
//************************************
class StateMapperDelegate[T] extends StringMapperDelegate[State[T]] {
  def zero = new State("")
  def sourceToDest(value: State[T]): String = value.value
  def safeDestToSource(str: String): State[T] = State[T](str)
}

//************************************
//       DeepLocator -> String
//************************************
class DeepLocatorMapperDelegate[T] extends StringMapperDelegate[DeepLocator] {
  def zero = DeepLocator("")
  def sourceToDest(value: DeepLocator): String = value.value
  def safeDestToSource(str: String): DeepLocator = DeepLocator(str)
}

//************************************
//       DeepLinkToken -> String
//************************************
class DeepLinkTokenMapperDelegate[T] extends StringMapperDelegate[DeepLinkToken] {
  def zero = DeepLinkToken("")
  def sourceToDest(value: DeepLinkToken): String = value.value
  def safeDestToSource(str: String): DeepLinkToken = DeepLinkToken(str)
}

//************************************
//       Seq[URLHistory] -> String
//************************************
class URLHistorySeqMapperDelegate extends StringMapperDelegate[Seq[URLHistory]] {
  def zero = Nil
  def sourceToDest(history: Seq[URLHistory]) = {
    val serializer = URLHS.urlHistorySerializer
    Json.stringify(serializer.writes(history))
  }
  def safeDestToSource(history: String) = {
    val json = Json.parse(history)
    val serializer = URLHS.urlHistorySerializer
    serializer.reads(json)
  }
}

//************************************
//       BookmarkSource -> String
//************************************
class BookmarkSourceMapperDelegate extends StringMapperDelegate[BookmarkSource] {
  def zero = BookmarkSource("")
  def sourceToDest(value: BookmarkSource): String = value.value
  def safeDestToSource(str: String): BookmarkSource = BookmarkSource(str)
}


//************************************
//       SocialNetworkType -> String
//************************************
class SocialNetworkTypeMapperDelegate extends StringMapperDelegate[SocialNetworkType] {
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
class SocialUserMapperDelegate extends StringMapperDelegate[SocialUser] {
  def zero = SocialUser(id = UserId("", ""), displayName = "", email = None, avatarUrl = None, authMethod = AuthenticationMethod.OAuth2)
  def sourceToDest(socialUser: SocialUser) = SocialUserSerializer.userSerializer.writes(socialUser).toString
  def safeDestToSource(str: String) = SocialUserSerializer.userSerializer.reads(Json.parse(str))
}

//************************************
//       SocialId -> String
//************************************
class SocialIdMapperDelegate extends StringMapperDelegate[SocialId] {
  def zero = SocialId("")
  def sourceToDest(socialId: SocialId) = socialId.id
  def safeDestToSource(str: String) = SocialId(str)
}

//************************************
//       KifiVersion -> String
//************************************
class KifiVersionMapperDelegate extends StringMapperDelegate[KifiVersion] {
  def zero = KifiVersion(0, 0, 0)
  def sourceToDest(version: KifiVersion) = version.toString
  def safeDestToSource(str: String) = KifiVersion(str)
}

//************************************
//       UserAgent -> String
//************************************
class UserAgentMapperDelegate extends StringMapperDelegate[UserAgent] {
  def zero = UserAgent("")
  def sourceToDest(value: UserAgent) = value.userAgent
  def safeDestToSource(str: String) = UserAgent(str)
}

//************************************
//       ElectronicMailMessageId -> String
//************************************
class ElectronicMailMessageIdMapperDelegate extends StringMapperDelegate[ElectronicMailMessageId] {
  def zero = ElectronicMailMessageId("")
  def sourceToDest(value: ElectronicMailMessageId) = value.id
  def safeDestToSource(str: String) = ElectronicMailMessageId(str)
}

//************************************
//       ElectronicMailCategory -> String
//************************************
class ElectronicMailCategoryMapperDelegate extends StringMapperDelegate[ElectronicMailCategory] {
  def zero = ElectronicMailCategory("")
  def sourceToDest(value: ElectronicMailCategory) = value.category
  def safeDestToSource(str: String) = ElectronicMailCategory(str)
}

//************************************
//       SystemEmailAddress -> String
//************************************
class SystemEmailAddressMapperDelegate extends StringMapperDelegate[SystemEmailAddress] {
  def zero = EmailAddresses.TEAM
  def sourceToDest(value: SystemEmailAddress) = value.address
  def safeDestToSource(str: String) = EmailAddresses(str)
}

//************************************
//       EmailAddressHolder -> String
//************************************
class EmailAddressHolderMapperDelegate extends StringMapperDelegate[EmailAddressHolder] {
  def zero = new EmailAddressHolder(){val address = ""}
  def sourceToDest(value: EmailAddressHolder) = value.address
  def safeDestToSource(str: String) = new EmailAddressHolder(){val address = str}
}

//************************************
//       LargeString -> Clob
//************************************
class LargeStringMapperDelegate extends DelegateMapperDelegate[LargeString, Clob] {
  protected val delegate = new ClobTypeMapperDelegate()
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
class ByteArrayMapperDelegate extends DelegateMapperDelegate[Array[Byte], Blob] {
  protected val delegate = new BlobTypeMapperDelegate()
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
