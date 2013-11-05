package com.keepit.search.user

import com.keepit.search.IdFilterCompressor
import com.keepit.common.db.Id
import com.keepit.model.User
import com.google.inject.{Inject, Singleton}
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

abstract case class UserSearchFilter(
  userId: Option[Id[User]],
  context: Option[String]
) {
  val idFilter: Set[Long] = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
  val friends: Set[Long]
  def filterType(): UserSearchFilterType.Value
  def accept(id: Long): Boolean
}

object UserSearchFilterType extends Enumeration {
  val DEFAULT = Value("default")
  val FRIENDS_ONLY = Value("friends_only")
  val NON_FRIENDS_ONLY = Value("non_friends_only")
}

@Singleton
class UserSearchFilterFactory @Inject()(client: ShoeboxServiceClient) {

  private def getFriends(userId: Option[Id[User]]): Set[Long] = userId match {
    case None => Set.empty[Long]
    case Some(uid) => Await.result(client.getFriends(uid), 5 seconds).map{_.id}
  }

  // can omit userId, especially for to-be-signed-up users.
  def default(context: Option[String] = None) = new UserSearchFilter(userId = None, context){
    override val friends = getFriends(userId)
    override def filterType = UserSearchFilterType.DEFAULT
    override def accept(id: Long) = !idFilter.contains(id)
  }

  def friendsOnly(userId: Id[User], context: Option[String] = None) = new UserSearchFilter(Some(userId), context){
    override val friends = getFriends(userId)
    val filteredFriends = friends -- idFilter
    override def filterType = UserSearchFilterType.FRIENDS_ONLY
    override def accept(id: Long) = filteredFriends.contains(id)
  }

  def nonFriendsOnly(userId: Id[User], context: Option[String] = None) = new UserSearchFilter(Some(userId), context){
    override val friends = getFriends(userId)
    override def filterType = UserSearchFilterType.NON_FRIENDS_ONLY
    override def accept(id: Long) = !friends.contains(id) && !idFilter.contains(id) && (userId.get.id != id)
  }
}