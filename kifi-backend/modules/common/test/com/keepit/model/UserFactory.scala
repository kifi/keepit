package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ SequenceNumber, ExternalId, Id, State }
import com.keepit.common.mail.{ EmailAddress }
import org.apache.commons.lang3.RandomStringUtils.random
import org.joda.time.DateTime

object UserFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def user(): PartialUser = {
    new PartialUser(User(id = Some(Id[User](idx.incrementAndGet())), firstName = random(5), lastName = random(5))).withUsername(random(5))
  }

  def users(count: Int): Seq[PartialUser] = List.fill(count)(user())

  class PartialUser private[UserFactory] (user: User, _experiments: Seq[ExperimentType] = Seq.empty) {
    def withId(id: Id[User]) = new PartialUser(user.copy(id = Some(id)))
    def withId(id: Int) = new PartialUser(user.copy(id = Some(Id[User](id))))
    def withId(id: ExternalId[User]) = new PartialUser(user.copy(externalId = id))
    def withId(id: String) = new PartialUser(user.copy(externalId = ExternalId[User](id)))
    def withCreatedAt(createdAt: DateTime) = new PartialUser(user.copy(createdAt = createdAt))
    def withUpdatedAt(updatedAt: DateTime) = new PartialUser(user.copy(updatedAt = updatedAt))
    def withName(first: String, last: String) = new PartialUser(user.copy(firstName = first, lastName = last))
    def withUsername(name: String): PartialUser = new PartialUser(user.copy(primaryUsername = Some(PrimaryUsername(Username(name), Username(name)))))
    def withPictureName(name: String) = new PartialUser(user.copy(pictureName = Some(name)))
    def withState(state: State[User]) = new PartialUser(user.copy(state = state))
    def withEmailAddress(address: EmailAddress) = new PartialUser(user.copy(primaryEmail = Some(address)))
    def withEmailAddress(address: String): PartialUser = this.withEmailAddress(EmailAddress(address))
    def withExperiments(experiments: ExperimentType*) = new PartialUser(user, experiments ++ _experiments)
    def withSeq(seq: Int) = new PartialUser(user.copy(seq = SequenceNumber[User](seq)))
    def get: User = user
    def experiments: Seq[ExperimentType] = _experiments
  }

  implicit class PartialUserSeq(users: Seq[PartialUser]) {
    def get: Seq[User] = users.map(_.get)
  }

}
