package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.cache._
import scala.concurrent.duration._
import scala.Some
import net.codingwell.scalaguice.ScalaModule

case class ClickHistory (
  id: Option[Id[ClickHistory]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[ClickHistory] = ClickHistoryStates.ACTIVE,
  userId: Id[User],
  tableSize: Int,
  filter: Array[Byte],
  numHashFuncs: Int,
  minHits: Int,
  updatesCount: Int = 0
) extends Model[ClickHistory] {
  def withFilter(filter: Array[Byte]) = this.copy(filter = filter)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[ClickHistory]) = this.copy(id = Some(id))
}

case class ClickHistoryUserIdKey(userId: Id[User]) extends Key[ClickHistory] {
  override val version = 2
  val namespace = "click_history_by_userid"
  def toKey(): String = userId.id.toString
}

class ClickHistoryUserIdCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[ClickHistoryUserIdKey, ClickHistory](innermostPluginSettings, innerToOuterPluginSettings:_*)

object ClickHistoryStates extends States[ClickHistory]

trait ClickHistoryModule extends ScalaModule
