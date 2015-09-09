package com.keepit.notify.info

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.ImagePath
import com.keepit.model._
import com.keepit.notify.info.NotificationInfoRequest._
import com.keepit.social.BasicUser

import scala.concurrent.Future

class BatchedNotificationInfos(
    private val users: Map[Id[User], BasicUser],
    private val usersExternal: Map[ExternalId[User], BasicUser],
    private val libraries: Map[Id[Library], BasicLibraryDetails],
    private val keeps: Map[Id[Keep], BasicKeep],
    private val orgs: Map[Id[Organization], BasicOrganization]) {

  def lookup[M, R](request: NotificationInfoRequest[M, R]): R = {
    // If intellij shows red underlines here, don't listen, it's lying.
    // It cannot detect that the result type R varies based on the pattern match here,
    // but the Scala compiler can.
    request match {
      case RequestUser(id) => users(id)
      case RequestUserExternal(id) => usersExternal(id)
      case RequestLibrary(id) => libraries(id)
      case RequestKeep(id) => keeps(id)
      case RequestOrganization(id) => orgs(id)
    }
  }

}

sealed trait NotificationInfoRequest[M, R] {

  def lookup(batched: BatchedNotificationInfos): R = batched.lookup(this)

}

object NotificationInfoRequest {

  case class RequestUser(id: Id[User]) extends NotificationInfoRequest[User, BasicUser]
  case class RequestUserExternal(id: ExternalId[User]) extends NotificationInfoRequest[User, BasicUser]
  case class RequestLibrary(id: Id[Library]) extends NotificationInfoRequest[Library, BasicLibraryDetails]
  case class RequestKeep(id: Id[Keep]) extends NotificationInfoRequest[Keep, BasicKeep]
  case class RequestOrganization(id: Id[Organization]) extends NotificationInfoRequest[Organization, BasicOrganization]

}

/**
 * Represents a wrapper of a function that requests a whole bunch of notification infos, then constructs a value.
 */
case class RequestingNotificationInfos[+A](requests: Seq[NotificationInfoRequest[_, _]])(val fn: BatchedNotificationInfos => A)

/**
 * The compiler cannot infer that a Seq of specific notification info should be existential using the Seq(...) method,
 * so this hints it explicitly.
 */
object Requests {
  def apply(requests: NotificationInfoRequest[_, _]*): Seq[NotificationInfoRequest[_, _]] = requests
}
