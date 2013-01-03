package com.keepit.common.db.slick

import org.scalaquery.ql.{TypeMapper, TypeMapperDelegate, BaseTypeMapper}
import org.scalaquery.ql.basic.BasicProfile
import org.scalaquery.session.{PositionedParameters, PositionedResult}

import com.keepit.common.db.{Id, State}
import com.keepit.common.time._
import org.joda.time.DateTime

import java.sql.Types.{TIMESTAMP, BIGINT, VARCHAR}
import java.sql.Timestamp

object FortyTwoTypeMappers {

  //************************************
  //       DateTime -> Timestamp
  //************************************

  implicit object DateTimeTypeMapper extends BaseTypeMapper[DateTime] {
    def apply(profile: BasicProfile) = new DateTimeMapperDelegate
  }

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

  implicit object IdTypeMapper extends BaseTypeMapper[Id[_]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate
  }

  class IdMapperDelegate extends TypeMapperDelegate[Id[_]] {
    def zero = Id(0)
    def sqlType = BIGINT
    def setValue(value: Id[_], p: PositionedParameters) = p.setLong(value.id)
    def setOption(valueOpt: Option[Id[_]], p: PositionedParameters) = p.setLongOption(valueOpt map (_.id))
    def nextValue(r: PositionedResult) = Id(r.nextLong)
    def updateValue(value: Id[_], r: PositionedResult) = r.updateLong(value.id)
  }

  //************************************
  //       State -> String
  //************************************

  implicit object StateTypeMapper extends BaseTypeMapper[State[_]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate
  }

  class StateMapperDelegate extends TypeMapperDelegate[State[_]] {
    def zero = new State("")
    def sqlType = VARCHAR
    def setValue(value: State[_], p: PositionedParameters) = p.setString(value.value)
    def setOption(valueOpt: Option[State[_]], p: PositionedParameters) = p.setStringOption(valueOpt map (_.value))
    def nextValue(r: PositionedResult) = State(r.nextString)
    def updateValue(value: State[_], r: PositionedResult) = r.updateString(value.value)
  }
}