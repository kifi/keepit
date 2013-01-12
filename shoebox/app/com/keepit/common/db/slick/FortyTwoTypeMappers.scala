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

object FortyTwoTypeMappers {

  implicit object DateTimeTypeMapper extends BaseTypeMapper[DateTime] {
    def apply(profile: BasicProfile) = new DateTimeMapperDelegate
  }

  implicit object GenericIdTypeMapper extends BaseTypeMapper[Id[Model[_]]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Model[_]]
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

  implicit object NormalizedURIIdTypeMapper extends BaseTypeMapper[Id[NormalizedURI]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[NormalizedURI]
  }

  implicit object FollowStateTypeMapper extends BaseTypeMapper[State[Follow]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[Follow]
  }

  implicit object UserStateTypeMapper extends BaseTypeMapper[State[User]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[User]
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
  def nextValue(r: PositionedResult) = ExternalId(delegate.nextValue(r))
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
