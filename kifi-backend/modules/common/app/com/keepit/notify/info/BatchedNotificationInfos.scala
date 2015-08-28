package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.store.ImagePath
import com.keepit.model._
import com.keepit.notify.info.NotificationInfoRequest.{RequestOrganization, RequestKeep, RequestLibrary, RequestUser}

class BatchedNotificationInfos(
  private val users: Map[Id[User], UserNotificationInfo],
  private val libraries: Map[Id[Library], LibraryNotificationInfo],
  private val keeps: Map[Id[Keep], KeepNotificationInfo],
  private val orgs: Map[Id[Organization], OrganizationNotificationInfo]
) {

  def lookup[M <: HasId[M], R](request: NotificationInfoRequest[M, R]): R =
    request match {
      case RequestUser(id) => users(id)
      case RequestLibrary(id) => libraries(id)
      case RequestKeep(id) => keeps(id)
      case RequestOrganization(id) => orgs(id)
    }

}



sealed trait NotificationInfoRequest[M <: HasId[M], R] {

  val id: Id[M]

  def lookup(batched: BatchedNotificationInfos): R = batched.lookup(this)

}

object NotificationInfoRequest {

  case class RequestUser(id: Id[User]) extends NotificationInfoRequest[User, UserNotificationInfo]
  case class RequestLibrary(id: Id[Library]) extends NotificationInfoRequest[Library, LibraryNotificationInfo]
  case class RequestKeep(id: Id[Keep]) extends NotificationInfoRequest[Keep, KeepNotificationInfo]
  case class RequestOrganization(id: Id[Organization]) extends NotificationInfoRequest[Organization, OrganizationNotificationInfo]

}

/**
 * Represents a wrapper of a function that requests a whole bunch of notification infos, then constructs a value.
 */
case class RequestingNotificationInfos[A](requests: Seq[ExInfoRequest])(val fn: BatchedNotificationInfos => A)

/**
 * The compiler cannot infer that a Seq of specific notification info should be existential using the Seq(...) method,
 * so this hints it explicitly.
 */
object Requests {
  def apply(requests: ExInfoRequest*): Seq[ExInfoRequest]
    = requests
}
