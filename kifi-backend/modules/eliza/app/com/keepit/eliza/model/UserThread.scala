package com.keepit.eliza.model

import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.{Model, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{BasicUserLikeEntity, BasicNonUser, BasicUser}

import play.api.libs.json.{Json, JsValue, JsNull, JsObject}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}

import scala.slick.lifted.Query
import scala.concurrent.{Future, Promise, Await}
import scala.concurrent.duration._

import MessagingTypeMappers._
import com.keepit.common.mail.{PostOffice, ElectronicMailCategory}

case class Notification(thread: Id[MessageThread], message: Id[Message])

case class UserThreadActivity(id: Id[UserThread], threadId: Id[MessageThread], userId: Id[User], lastActive: Option[DateTime], started: Boolean, lastSeen: Option[DateTime])

case class UserThread(
    id: Option[Id[UserThread]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    user: Id[User],
    thread: Id[MessageThread],
    uriId: Option[Id[NormalizedURI]],
    lastSeen: Option[DateTime],
    unread: Boolean = false,
    muted: Boolean = false,
    lastMsgFromOther: Option[Id[Message]],
    lastNotification: JsValue,
    notificationUpdatedAt: DateTime = currentDateTime,
    notificationLastSeen: Option[DateTime] = None,
    notificationEmailed: Boolean = false,
    replyable: Boolean = true,
    lastActive: Option[DateTime] = None, //Contains the 'createdAt' timestamp of the last message this user sent on this thread
    started: Boolean = false //Whether or not this thread was started by this user
  )
  extends Model[UserThread] {

  def withId(id: Id[UserThread]): UserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt=updateTime)
}

case class UserThreadStats(all: Int, active, Int, started: Int)
