package com.keepit.common.db.slick

import org.scalaquery.ql.{TypeMapper, TypeMapperDelegate, BaseTypeMapper}
import org.scalaquery.ql.basic.BasicProfile
import org.scalaquery.session.{PositionedParameters, PositionedResult}
import com.keepit.common.db.{Id, State, Model, ExternalId}
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime
import java.sql.Types.{TIMESTAMP, BIGINT, VARCHAR}
import java.sql.Timestamp
import play.api.libs.json._
import org.scalaquery.ql.basic.BasicTypeMapperDelegates._
import com.keepit.serializer.{URLHistorySerializer => URLHS}

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

  //Ids
  implicit object FollowIdTypeMapper extends BaseTypeMapper[Id[Follow]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Follow]
  }

  implicit object UserIdTypeMapper extends BaseTypeMapper[Id[User]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[User]
  }

  implicit object URLIdTypeMapper extends BaseTypeMapper[Id[URL]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[URL]
  }

  implicit object NormalizedURIIdTypeMapper extends BaseTypeMapper[Id[NormalizedURI]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[NormalizedURI]
  }

  //States
  implicit object FollowStateTypeMapper extends BaseTypeMapper[State[Follow]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[Follow]
  }

  implicit object URLStateTypeMapper extends BaseTypeMapper[State[URL]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[URL]
  }

  implicit object UserStateTypeMapper extends BaseTypeMapper[State[User]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[User]
  }

  implicit object BookmarkStateTypeMapper extends BaseTypeMapper[State[Bookmark]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[Bookmark]
  }

  implicit object NormalizedURIStateTypeMapper extends BaseTypeMapper[State[NormalizedURI]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[NormalizedURI]
  }

  implicit object ExperimentTypeStateTypeMapper extends BaseTypeMapper[State[ExperimentType]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[ExperimentType]
  }

  implicit object EmailAddressStateTypeMapper extends BaseTypeMapper[State[EmailAddress]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[EmailAddress]
  }

  implicit object UserExperimentStateTypeMapper extends BaseTypeMapper[State[UserExperiment]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[UserExperiment]
  }

  //Other
  implicit object URLHistorySeqHistoryStateTypeMapper extends BaseTypeMapper[Seq[URLHistory]] {
    def apply(profile: BasicProfile) = new URLHistorySeqMapperDelegate
  }

  implicit object BookmarkSourceHistoryStateTypeMapper extends BaseTypeMapper[BookmarkSource] {
    def apply(profile: BasicProfile) = new BookmarkSourceMapperDelegate
  }
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
  def nextValue(r: PositionedResult): DateTime = delegate.nextValue(r)
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
class ExternalIdMapperDelegate[T] extends TypeMapperDelegate[ExternalId[T]] {
  private val delegate = new StringTypeMapperDelegate()
  def zero = ExternalId[T]()
  def sqlType = delegate.sqlType
  def setValue(value: ExternalId[T], p: PositionedParameters) = delegate.setValue(value.id, p)
  def setOption(valueOpt: Option[ExternalId[T]], p: PositionedParameters) = delegate.setOption(valueOpt map (_.id), p)
  def nextValue(r: PositionedResult) = delegate.nextValueOrElse("", r) match {
    case "" => zero
    case some => ExternalId(some)
  }
  def updateValue(value: ExternalId[T], r: PositionedResult) = delegate.updateValue(value.id, r)
  override def valueToSQLLiteral(value: ExternalId[T]) = delegate.valueToSQLLiteral(value.id)
}

//************************************
//       State -> String
//************************************
class StateMapperDelegate[T] extends TypeMapperDelegate[State[T]] {
  private val delegate = new StringTypeMapperDelegate()
  def zero = new State("")
  def sqlType = delegate.sqlType
  def setValue(value: State[T], p: PositionedParameters) = delegate.setValue(value.value, p)
  def setOption(valueOpt: Option[State[T]], p: PositionedParameters) = delegate.setOption(valueOpt map (_.value), p)
  def nextValue(r: PositionedResult) = State(delegate.nextValue(r))
  def updateValue(value: State[T], r: PositionedResult) = delegate.updateValue(value.value, r)
  override def valueToSQLLiteral(value: State[T]) = delegate.valueToSQLLiteral(value.value)
}

//************************************
//       Seq[URLHistory] -> String
//************************************
class URLHistorySeqMapperDelegate extends TypeMapperDelegate[Seq[URLHistory]] {
  private val delegate = new StringTypeMapperDelegate()
  def zero = Nil
  def sqlType = delegate.sqlType
  def setValue(value: Seq[URLHistory], p: PositionedParameters) = delegate.setValue(historyToString(value), p)
  def setOption(valueOpt: Option[Seq[URLHistory]], p: PositionedParameters) = delegate.setOption(valueOpt map historyToString, p)
  def nextValue(r: PositionedResult) = historyFromString(delegate.nextValue(r))
  def updateValue(value: Seq[URLHistory], r: PositionedResult) = delegate.updateValue(historyToString(value), r)
  override def valueToSQLLiteral(value: Seq[URLHistory]) = delegate.valueToSQLLiteral(historyToString(value))

  private def historyToString(history: Seq[URLHistory]) = {
    val serializer = URLHS.urlHistorySerializer
    Json.stringify(serializer.writes(history))
  }

  private def historyFromString(history: String) = {
    val json = Json.parse(history)
    val serializer = URLHS.urlHistorySerializer
    serializer.reads(json)
  }
}

//************************************
//       BookmarkSource -> String
//************************************
class BookmarkSourceMapperDelegate extends TypeMapperDelegate[BookmarkSource] {
  private val delegate = new StringTypeMapperDelegate()
  def zero = BookmarkSource("")
  def sqlType = delegate.sqlType
  def setValue(value: BookmarkSource, p: PositionedParameters) = delegate.setValue(value.value, p)
  def setOption(valueOpt: Option[BookmarkSource], p: PositionedParameters) = delegate.setOption(valueOpt.map(_.value), p)
  def nextValue(r: PositionedResult) = BookmarkSource(delegate.nextValue(r))
  def updateValue(value: BookmarkSource, r: PositionedResult) = delegate.updateValue(value.value, r)
  override def valueToSQLLiteral(value: BookmarkSource) = delegate.valueToSQLLiteral(value.value)
}

