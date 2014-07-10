package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import org.joda.time.DateTime
import com.keepit.common.db.slick.Repo
import com.keepit.common.db.{ SequenceNumber, Id, LargeString, State }
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.db.slick.DbRepo
import com.keepit.common.db.slick.DBSession

@ImplementedBy(classOf[SystemValueRepoImpl])
trait SystemValueRepo extends Repo[SystemValue] {
  def getValue(name: Name[SystemValue])(implicit session: RSession): Option[String]
  def getSystemValue(name: Name[SystemValue])(implicit session: RSession): Option[SystemValue]
  def setValue(name: Name[SystemValue], value: String)(implicit session: RWSession): String
  def clearValue(name: Name[SystemValue])(implicit session: RWSession): Boolean
  def getSequenceNumber[T](name: Name[SequenceNumber[T]])(implicit session: RSession): Option[SequenceNumber[T]]
  def setSequenceNumber[T](name: Name[SequenceNumber[T]], seq: SequenceNumber[T])(implicit session: RWSession): Unit
}

@Singleton
class SystemValueRepoImpl @Inject() (
  val db: DataBaseComponent,
  val valueCache: SystemValueCache,
  val clock: Clock)
    extends DbRepo[SystemValue] with SystemValueRepo {
  import db.Driver.simple._
  import DBSession._

  type RepoImpl = SystemValueTable
  case class SystemValueTable(tag: Tag) extends RepoTable[SystemValue](db, tag, "system_value") {
    def value = column[LargeString]("value", O.NotNull)
    def name = column[Name[SystemValue]]("name", O.NotNull)

    def * = (id.?, createdAt, updatedAt, name, value, state) <> (rowToObj, objToRow)
  }

  private val rowToObj: ((Option[Id[SystemValue]], DateTime, DateTime, Name[SystemValue], LargeString, State[SystemValue])) => SystemValue = {
    case (id, createdAt, updatedAt, name, LargeString(value), state) => SystemValue(id, createdAt, updatedAt, name, value, state)
  }

  private val objToRow: SystemValue => Option[(Option[Id[SystemValue]], DateTime, DateTime, Name[SystemValue], LargeString, State[SystemValue])] = {
    case SystemValue(id, createdAt, updatedAt, name, value, state) => Some((id, createdAt, updatedAt, name, LargeString(value), state))
    case _ => None
  }

  def table(tag: Tag) = new SystemValueTable(tag)

  override def invalidateCache(systemValue: SystemValue)(implicit session: RSession): Unit = {
    valueCache.remove(SystemValueKey(systemValue.name))
  }

  override def deleteCache(systemValue: SystemValue)(implicit session: RSession): Unit = {
    valueCache.remove(SystemValueKey(systemValue.name))
  }

  def getValue(name: Name[SystemValue])(implicit session: RSession): Option[String] = {
    //valueCache.getOrElseOpt(SystemValueKey(name)) {
    (for (f <- rows if f.state === SystemValueStates.ACTIVE && f.name === name) yield f.value).firstOption.map(_.value)
    //}
  }

  def getSystemValue(name: Name[SystemValue])(implicit session: RSession): Option[SystemValue] =
    (for (f <- rows if f.state === SystemValueStates.ACTIVE && f.name === name) yield f).firstOption

  def setValue(name: Name[SystemValue], value: String)(implicit session: RWSession): String = {
    val updated = (for (v <- rows if v.name === name) yield (v.value, v.state, v.updatedAt)).update((value, SystemValueStates.ACTIVE, clock.now())) > 0
    if (updated) {
      valueCache.set(SystemValueKey(name), value)
      value
    } else {
      save(SystemValue(name = name, value = value))
      value
    }
  }

  def clearValue(name: Name[SystemValue])(implicit session: RWSession): Boolean = {
    val changed = (for (v <- rows if v.name === name && v.state =!= SystemValueStates.INACTIVE) yield (v.state, v.updatedAt))
      .update((SystemValueStates.INACTIVE, clock.now())) > 0
    if (changed) {
      valueCache.remove(SystemValueKey(name))
    }
    changed
  }

  private def toSystemValueName[T](name: Name[SequenceNumber[T]]): Name[SystemValue] = Name(name.name + "_sequence")
  def getSequenceNumber[T](name: Name[SequenceNumber[T]])(implicit session: RSession): Option[SequenceNumber[T]] = getValue(toSystemValueName(name)).map(value => SequenceNumber[T](value.toLong))
  def setSequenceNumber[T](name: Name[SequenceNumber[T]], seq: SequenceNumber[T])(implicit session: RWSession): Unit = setValue(toSystemValueName(name), seq.value.toString)
}

