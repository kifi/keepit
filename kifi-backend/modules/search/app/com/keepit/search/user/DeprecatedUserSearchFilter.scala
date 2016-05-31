package com.keepit.search.user

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.util.IdFilterCompressor
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

abstract case class DeprecatedUserSearchFilter(
    userId: Option[Id[User]],
    context: Option[String]) {
  val idFilter: Set[Long] = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
  val kifiFriendsFuture: Future[Set[Long]]
  def filterType(): DeprecatedUserSearchFilterType.Value
  def accept(id: Long): Boolean
  def getKifiFriends(): Set[Long] = Await.result(kifiFriendsFuture, 5 seconds)
}

object DeprecatedUserSearchFilterType extends Enumeration {
  val DEFAULT = Value("default")
  val FRIENDS_ONLY = Value("friends_only") // kifi friends
  val NON_FRIENDS_ONLY = Value("non_friends_only")
}

@Singleton
class DeprecatedUserSearchFilterFactory @Inject() (client: ShoeboxServiceClient) {

  private def getFriends(userId: Option[Id[User]]): Future[Set[Long]] = userId match {
    case None => Promise.successful(Set.empty[Long]).future
    case Some(uid) => client.getFriends(uid).map { uids => uids.map { _.id } }
  }

  def default(userId: Option[Id[User]], context: Option[String] = None, excludeSelf: Boolean = false) = new DeprecatedUserSearchFilter(userId, context) {
    override val kifiFriendsFuture = getFriends(userId)
    override def filterType = DeprecatedUserSearchFilterType.DEFAULT
    override def accept(id: Long) = !idFilter.contains(id) && !(excludeSelf && userId.isDefined && userId.get.id == id)
  }

  def friendsOnly(userId: Id[User], context: Option[String] = None) = new DeprecatedUserSearchFilter(Some(userId), context) {
    override val kifiFriendsFuture = getFriends(userId)
    lazy val filteredFriends = getKifiFriends -- idFilter
    override def filterType = DeprecatedUserSearchFilterType.FRIENDS_ONLY
    override def accept(id: Long) = filteredFriends.contains(id)
  }

  def nonFriendsOnly(userId: Id[User], context: Option[String] = None) = new DeprecatedUserSearchFilter(Some(userId), context) {
    override val kifiFriendsFuture = getFriends(userId)
    override def filterType = DeprecatedUserSearchFilterType.NON_FRIENDS_ONLY
    override def accept(id: Long) = !getKifiFriends.contains(id) && !idFilter.contains(id) && (userId.get.id != id)
  }

}
