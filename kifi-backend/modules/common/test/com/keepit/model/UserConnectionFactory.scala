package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ ExternalId, Id, State }
import org.apache.commons.lang3.RandomStringUtils.random

object UserConnectionFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def connect(c: Tuple2[User, User], clist: Tuple2[User, User]*): Seq[PartialUserConnection] = {
    val connection1 = connect().withUsers(c._1, c._2)
    val connections = clist.map(connect)
    connection1 +: connections
  }

  def connect(c: Tuple2[User, User]): PartialUserConnection = connect().withUsers(c._1, c._2)

  def connect(): PartialUserConnection = {
    new PartialUserConnection(UserConnection(
      id = Some(Id[UserConnection](idx.incrementAndGet())),
      user1 = Id[User](idx.incrementAndGet()),
      user2 = Id[User](idx.incrementAndGet())))
  }

  def connections(count: Int): Seq[PartialUserConnection] = List.fill(count)(connect())

  class PartialUserConnection private[UserConnectionFactory] (connect: UserConnection) {
    def withId(id: Id[UserConnection]) = new PartialUserConnection(connect.copy(id = Some(id)))
    def withId(id: Int) = new PartialUserConnection(connect.copy(id = Some(Id[UserConnection](id))))
    def withUsers(id1: Int, id2: Int) = new PartialUserConnection(connect.copy(user1 = Id[User](id1), user2 = Id[User](id2)))
    def withUsers(id1: Id[User], id2: Id[User]) = new PartialUserConnection(connect.copy(user1 = id1, user2 = id2))
    def withUsers(id1: User, id2: User) = new PartialUserConnection(connect.copy(user1 = id1.id.get, user2 = id2.id.get))
    def withState(state: State[UserConnection]) = new PartialUserConnection(connect.copy(state = state))
    def get: UserConnection = connect
  }

  implicit class PartialUserConnectionSeq(users: Seq[PartialUserConnection]) {
    def get: Seq[UserConnection] = users.map(_.get)
  }

}
