package com.keepit.common.db.slick

import org.scalaquery.ql.{TypeMapper, TypeMapperDelegate, BaseTypeMapper}
import org.scalaquery.ql.basic.BasicProfile
import org.scalaquery.session.{PositionedParameters, PositionedResult}

import com.keepit.common.db.{Id, State, Model}
import com.keepit.common.time._

import com.keepit.model._

import org.joda.time.DateTime

import java.sql.Types.{TIMESTAMP, BIGINT, VARCHAR}
import java.sql.Timestamp

import play.api.libs.json._

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
}

//************************************
//       DateTime -> Timestamp
//************************************
class DateTimeMapperDelegate extends TypeMapperDelegate[DateTime] {
  def zero = currentDateTime
  def sqlType = TIMESTAMP
  def setValue(value: DateTime, p: PositionedParameters) = p.setTimestamp(timestamp(value))
  def setOption(valueOpt: Option[DateTime], p: PositionedParameters) = p.setTimestampOption(valueOpt map timestamp)
  def nextValue(r: PositionedResult) = r.nextTimestamp
  def updateValue(value: DateTime, r: PositionedResult) = r.updateTimestamp(timestamp(value))
  override def valueToSQLLiteral(value: DateTime) = "{ts '" + timestamp(value) + "'}"

  private def timestamp(value: DateTime) = new Timestamp(value.toDate.getTime)
}


//************************************
//       Id -> Long
//************************************
class IdMapperDelegate[T] extends TypeMapperDelegate[Id[T]] {
  def zero = Id[T](0)
  def sqlType = BIGINT
  def setValue(value: Id[T], p: PositionedParameters) = p.setLong(value.id)
  def setOption(valueOpt: Option[Id[T]], p: PositionedParameters) = p.setLongOption(valueOpt map (_.id))
  def nextValue(r: PositionedResult) = Id(r.nextLong)
  def updateValue(value: Id[T], r: PositionedResult) = r.updateLong(value.id)
}

//************************************
//       State -> String
//************************************
class StateMapperDelegate[T] extends TypeMapperDelegate[State[T]] {
  def zero = new State("")
  def sqlType = VARCHAR
  def setValue(value: State[T], p: PositionedParameters) = p.setString(value.value)
  def setOption(valueOpt: Option[State[T]], p: PositionedParameters) = p.setStringOption(valueOpt map (_.value))
  def nextValue(r: PositionedResult) = State(r.nextString)
  def updateValue(value: State[T], r: PositionedResult) = r.updateString(value.value)
}
