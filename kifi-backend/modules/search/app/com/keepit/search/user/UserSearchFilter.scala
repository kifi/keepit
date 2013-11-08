package com.keepit.search.user

import com.keepit.search.IdFilterCompressor
import com.keepit.common.db.Id
import com.keepit.model.User
import com.google.inject.{Inject, Singleton}
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

abstract case class UserSearchFilter(
  userId: Option[Id[User]],
  context: Option[String]
) {
  val idFilter: Set[Long] = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
  val kifiFriendsFuture: Future[Set[Long]]
  def filterType(): UserSearchFilterType.Value
  def accept(id: Long): Boolean
  def getKifiFriends(): Set[Long] = Await.result(kifiFriendsFuture, 5 seconds)
}

object UserSearchFilterType extends Enumeration {
  val DEFAULT = Value("default")
  val FRIENDS_ONLY = Value("friends_only")                                            // kifi friends
  val NON_FRIENDS_ONLY = Value("non_friends_only")
  val NON_KIFI_NON_SOCIAL_FRIENDS_ONLY = Value("non_kifi_non_social_friends_only")    // kifi user not connected to me in any social network (kifi, facebook, linkedin, etc)
}

@Singleton
class UserSearchFilterFactory @Inject()(client: ShoeboxServiceClient) {

  private def getFriends(userId: Option[Id[User]]): Future[Set[Long]] = userId match {
    case None => Promise.successful(Set.empty[Long]).future
    case Some(uid) => client.getFriends(uid).map{uids => uids.map{_.id}}
  }

  // can omit userId, especially for to-be-signed-up users.
  def default(context: Option[String] = None) = new UserSearchFilter(userId = None, context){
    override val kifiFriendsFuture = getFriends(userId)
    override def filterType = UserSearchFilterType.DEFAULT
    override def accept(id: Long) = !idFilter.contains(id)
  }

  def friendsOnly(userId: Id[User], context: Option[String] = None) = new UserSearchFilter(Some(userId), context){
    override val kifiFriendsFuture = getFriends(userId)
    lazy val filteredFriends = getKifiFriends -- idFilter
    override def filterType = UserSearchFilterType.FRIENDS_ONLY
    override def accept(id: Long) = filteredFriends.contains(id)
  }

  def nonFriendsOnly(userId: Id[User], context: Option[String] = None) = new UserSearchFilter(Some(userId), context){
    override val kifiFriendsFuture = getFriends(userId)
    override def filterType = UserSearchFilterType.NON_FRIENDS_ONLY
    override def accept(id: Long) = !getKifiFriends.contains(id) && !idFilter.contains(id) && (userId.get.id != id)
  }

  def nonKifiNonSocialOnly(userId: Id[User], context: Option[String] = None) = new UserSearchFilter(Some(userId), context){
    override val kifiFriendsFuture = getFriends(userId)
    val socialFriendsOnKifi = client.getSocialFriendsOnKifi(userId.get).map{uids => uids.map{_.id}}
    lazy val allFriends = getKifiFriends ++ Await.result(socialFriendsOnKifi, 5 seconds)
    override def filterType = UserSearchFilterType.NON_KIFI_NON_SOCIAL_FRIENDS_ONLY
    override def accept(id: Long) = !allFriends.contains(id) && (userId.get.id != id)
  }
}
