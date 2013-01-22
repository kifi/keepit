package com.keepit.common.db.slick

import securesocial.core.{SocialUser, UserId, AuthenticationMethod}
import org.scalaquery.ql.{TypeMapper, TypeMapperDelegate, BaseTypeMapper}
import org.scalaquery.ql.basic.BasicProfile
import org.scalaquery.session.{PositionedParameters, PositionedResult}
import com.keepit.common.db.{Id, State, Model, ExternalId}
import com.keepit.common.time._
import com.keepit.common.social._
import com.keepit.model._
import org.joda.time.DateTime
import java.sql.Types.{TIMESTAMP, BIGINT, VARCHAR}
import java.sql.Timestamp
import play.api.libs.json._
import org.scalaquery.ql.basic.BasicTypeMapperDelegates._
import com.keepit.serializer.{URLHistorySerializer => URLHS, SocialUserSerializer}

object FortyTwoTypeMappers {
  // Time
  implicit object DateTimeTypeMapper extends BaseTypeMapper[DateTime] {
    def apply(profile: BasicProfile) = new DateTimeMapperDelegate
  }

  implicit object GenericIdTypeMapper extends BaseTypeMapper[Id[Model[_]]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Model[_]]
  }

  //ExternalIds
  implicit object KifiInstallationExternalIdTypeMapper extends BaseTypeMapper[ExternalId[KifiInstallation]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[KifiInstallation]
  }

  implicit object NormalizedURIExternalIdTypeMapper extends BaseTypeMapper[ExternalId[NormalizedURI]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[NormalizedURI]
  }

  //Ids
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


  //Other
  implicit object URLHistorySeqHistoryStateTypeMapper extends BaseTypeMapper[Seq[URLHistory]] {
    def apply(profile: BasicProfile) = new URLHistorySeqMapperDelegate
  }

  implicit object BookmarkSourceHistoryStateTypeMapper extends BaseTypeMapper[BookmarkSource] {
    def apply(profile: BasicProfile) = new BookmarkSourceMapperDelegate
  }

  implicit object SocialNetworkTypeHistoryStateTypeMapper extends BaseTypeMapper[SocialNetworkType] {
    def apply(profile: BasicProfile) = new SocialNetworkTypeMapperDelegate
  }

  implicit object SocialUserHistoryStateTypeMapper extends BaseTypeMapper[SocialUser] {
    def apply(profile: BasicProfile) = new SocialUserMapperDelegate
  }

  implicit object SocialIdHistoryStateTypeMapper extends BaseTypeMapper[SocialId] {
    def apply(profile: BasicProfile) = new SocialIdMapperDelegate
  }
}

//************************************
//       Abstract string mapper
//************************************
abstract class StringMapperDelegate[T] extends TypeMapperDelegate[T] {
  private val delegate = new StringTypeMapperDelegate()
  def sqlType = delegate.sqlType
  def setValue(value: T, p: PositionedParameters) = delegate.setValue(typeToString(value), p)
  def setOption(valueOpt: Option[T], p: PositionedParameters) = delegate.setOption(valueOpt map typeToString, p)
  def nextValue(r: PositionedResult) = stringToType(delegate.nextValue(r))
  def updateValue(value: T, r: PositionedResult) = delegate.updateValue(typeToString(value), r)
  override def valueToSQLLiteral(value: T) = delegate.valueToSQLLiteral(typeToString(value))

  def typeToString(value: T): String
  def stringToType(str: String): T = Option(str) match {
    case None => zero
    case Some(value) => safeStringToType(value)
  }
  def safeStringToType(str: String): T
}

//************************************
//       DateTime -> Timestamp
//************************************
class DateTimeMapperDelegate extends TypeMapperDelegate[DateTime] {
  private val delegate = new TimestampTypeMapperDelegate()
  def zero = currentDateTime
  def sqlType = delegate.sqlType
  def setValue(value: DateTime, p: PositionedParameters) = delegate.setValue(timestamp(value), p)
  def setOption(valueOpt: Option[DateTime], p: PositionedParameters) = delegate.setOption(valueOpt map timestamp, p)
  def nextValue(r: PositionedResult): DateTime = Option(delegate.nextValue(r)) match {
    case Some(date) => date
    case None => START_OF_TIME
  }
  def updateValue(value: DateTime, r: PositionedResult) = delegate.updateValue(timestamp(value), r)
  override def valueToSQLLiteral(value: DateTime) = delegate.valueToSQLLiteral(timestamp(value))

  private def timestamp(value: DateTime) = new Timestamp(value.toDate.getTime)
}

//************************************
//       Id -> Long
//************************************
class IdMapperDelegate[T] extends TypeMapperDelegate[Id[T]] {
  private val delegate = new LongTypeMapperDelegate()
  def zero = Id[T](0)
  def sqlType = delegate.sqlType
  def setValue(value: Id[T], p: PositionedParameters) = delegate.setValue(value.id, p)
  def setOption(valueOpt: Option[Id[T]], p: PositionedParameters) = delegate.setOption(valueOpt map (_.id), p)
  def nextValue(r: PositionedResult) = Id(delegate.nextValue(r))
  def updateValue(value: Id[T], r: PositionedResult) = delegate.updateValue(value.id, r)
  override def valueToSQLLiteral(value: Id[T]) = delegate.valueToSQLLiteral(value.id)
}

//************************************
//       ExternalId -> String
//************************************
class ExternalIdMapperDelegate[T] extends StringMapperDelegate[ExternalId[T]] {
  def zero = ExternalId[T]()
  def typeToString(value: ExternalId[T]): String = value.id
  def safeStringToType(str: String): ExternalId[T] = ExternalId[T](str)
}

//************************************
//       State -> String
//************************************
class StateMapperDelegate[T] extends StringMapperDelegate[State[T]] {
  def zero = new State("")
  def typeToString(value: State[T]): String = value.value
  def safeStringToType(str: String): State[T] = State[T](str)
}

//************************************
//       Seq[URLHistory] -> String
//************************************
class URLHistorySeqMapperDelegate extends StringMapperDelegate[Seq[URLHistory]] {
  def zero = Nil
  def typeToString(history: Seq[URLHistory]) = {
    val serializer = URLHS.urlHistorySerializer
    Json.stringify(serializer.writes(history))
  }
  def safeStringToType(history: String) = {
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
  def typeToString(value: BookmarkSource): String = value.value
  def safeStringToType(str: String): BookmarkSource = BookmarkSource(str)
}


//************************************
//       SocialNetworkType -> String
//************************************
class SocialNetworkTypeMapperDelegate extends StringMapperDelegate[SocialNetworkType] {
  def zero = SocialNetworks.FACEBOOK
  def typeToString(socialNetworkType: SocialNetworkType) = socialNetworkType.name
  def safeStringToType(str: String) = str match {
    case SocialNetworks.FACEBOOK.name => SocialNetworks.FACEBOOK
    case _ => throw new RuntimeException("unknown network type %s".format(str))
  }
}

//************************************
//       SocialNetworkType -> String
//************************************
class SocialUserMapperDelegate extends StringMapperDelegate[SocialUser] {
  def zero = SocialUser(id = UserId("", ""), displayName = "", email = None, avatarUrl = None, authMethod = AuthenticationMethod.OAuth2)
  def typeToString(socialUser: SocialUser) = SocialUserSerializer.userSerializer.writes(socialUser).toString
  def safeStringToType(str: String) = SocialUserSerializer.userSerializer.reads(Json.parse(str))
}

//************************************
//       SocialId -> String
//************************************
class SocialIdMapperDelegate extends StringMapperDelegate[SocialId] {
  def zero = SocialId("")
  def typeToString(socialId: SocialId) = socialId.id
  def safeStringToType(str: String) = SocialId(str)
}

