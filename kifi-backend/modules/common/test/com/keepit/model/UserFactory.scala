package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ ExternalId, Id, State }
import org.apache.commons.lang3.RandomStringUtils.random

object UserFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def user(): PartialUser = {
    new PartialUser(User(id = Some(Id[User](idx.incrementAndGet())), firstName = random(5), lastName = random(5), username = Username(random(5)), normalizedUsername = random(5)))
  }

  def users(count: Int): Seq[PartialUser] = List.fill(count)(user())

  class PartialUser private[UserFactory] (user: User) {
    def withId(id: Id[User]) = new PartialUser(user.copy(id = Some(id)))
    def withId(id: Int) = new PartialUser(user.copy(id = Some(Id[User](id))))
    def withId(id: ExternalId[User]) = new PartialUser(user.copy(externalId = id))
    def withId(id: String) = new PartialUser(user.copy(externalId = ExternalId[User](id)))
    def withName(first: String, last: String) = new PartialUser(user.copy(firstName = first, lastName = last))
    def withUsername(name: String) = new PartialUser(user.copy(username = Username(name)))
    def withUsername(name: Username) = new PartialUser(user.copy(username = name))
    def withPictureName(name: String) = new PartialUser(user.copy(pictureName = Some(name)))
    def withState(state: State[User]) = new PartialUser(user.copy(state = state))
    def get: User = user
  }

  implicit class PartialUserSeq(users: Seq[PartialUser]) {
    def get: Seq[User] = users.map(_.get)
  }

}
