package com.keepit.notify.link

import com.google.inject.Inject
import com.keepit.common.db.slick.{ Database, DataBaseComponent }
import com.keepit.model.{ User, UserRepo }
import com.keepit.notify.model.{ NewFollower, NotificationAction, NotificationKind }

abstract class LinkGenerator[N <: NotificationAction] {

  val kind = implicitly[NotificationKind[NotificationAction]]

  def getLinks(actions: Set[N]): Set[String]

  def getMainLink(actions: Set[N]): String = getLinks(actions).head

}

@Singleton
class NewCollaboratorLinkGenerator @Inject() (
    db: Database,
    userRepo: UserRepo) extends LinkGenerator[NewFollower] {

  val userLinkable = Linkable[User]

  override def getLinks(actions: Set[NewFollower]): Set[String] = db.readOnlyReplica { implicit session =>
    actions.map { action =>
      userLinkable.getLink(userRepo.get(action.followerId))
    }
  }

}
