package com.keepit.common.social

import com.keepit.common.cache.TransactionalCache
import com.keepit.common.db.{ Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.social._

class BasicUserRepo @Inject() (
    userRepo: UserRepo,
    basicUserCache: BasicUserUserIdCache,
    airbrake: AirbrakeNotifier) extends Logging {

  def load(userId: Id[User])(implicit session: RSession): BasicUser = {
    loadActive(userId) getOrElse loadInactive(userId)
  }

  def loadAll(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], BasicUser] = {
    if (userIds.isEmpty) {
      Map.empty
    } else if (userIds.size == 1) {
      val userId = userIds.head
      Map(userId -> load(userId))
    } else {
      val activeBasicUsers = loadAllActive(userIds)
      userIds.map { userId =>
        userId -> activeBasicUsers.getOrElse(userId, loadInactive(userId))
      }.toMap
    }
  }

  def loadActive(userId: Id[User])(implicit session: RSession): Option[BasicUser] = {
    implicit val objCache = TransactionalCache.getObjectCache(basicUserCache)
    basicUserCache.getOrElseOpt(BasicUserUserIdKey(userId)) {
      Some(userRepo.get(userId)).filter(_.state == UserStates.ACTIVE).map(BasicUser.fromUser)
    }
  }

  def loadAllActive(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], BasicUser] = {
    basicUserCache.bulkGetOrElseOpt(userIds map BasicUserUserIdKey) { keys =>
      userRepo.getAllUsers(keys.map(_.userId).toSeq).collect {
        case (userId, user) => BasicUserUserIdKey(userId) -> (if (user.state == UserStates.ACTIVE) Some(BasicUser.fromUser(user)) else None)
      }.toMap
    }.collect { case (k, Some(v)) => k.userId -> v }
  }

  private def loadInactive(userId: Id[User])(implicit session: RSession): BasicUser = {
    val user = userRepo.get(userId)
    val caller = new RuntimeException().getStackTrace.find(e => e.getClassName.contains("com.keepit") && !e.getClassName.contains("BasicUser")).map(l => l.getClassName + "." + l.getMethodName + "(" + l.getFileName + ":" + l.getLineNumber + ")")

    log.error(s"Loading BasicUser for inactive user. $user: $caller")
    toBasicUserSafely(user)
  }

  private def toBasicUserSafely(user: User): BasicUser = {
    BasicUser.fromUser(user.copy(primaryUsername = user.primaryUsername orElse Some(PrimaryUsername(Username(""), Username("")))))
  }
}
