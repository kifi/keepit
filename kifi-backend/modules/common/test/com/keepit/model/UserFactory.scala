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

  case class PartialUser private[UserFactory] (user: User, emailAddressOpt: Option[EmailAddress] = None, experiments: Seq[UserExperimentType] = Seq.empty) {
    def withId(id: Id[User]) = this.copy(user = user.copy(id = Some(id)))
    def withId(id: Int) = this.copy(user = user.copy(id = Some(Id[User](id))))
    def withId(id: ExternalId[User]) = this.copy(user = user.copy(externalId = id))
    def withId(id: String) = this.copy(user = user.copy(externalId = ExternalId[User](id)))
    def withCreatedAt(createdAt: DateTime) = this.copy(user = user.copy(createdAt = createdAt))
    def withUpdatedAt(updatedAt: DateTime) = this.copy(user = user.copy(updatedAt = updatedAt))
    def withName(first: String, last: String) = this.copy(user = user.copy(firstName = first, lastName = last))
    def withFullName(firstAndLast: (String, String)) = this.copy(user = user.copy(firstName = firstAndLast._1, lastName = firstAndLast._2))
    def withEmailAddress(emailAddress: String) = this.copy(emailAddressOpt = Some(EmailAddress(emailAddress)))
    def withUsername(name: String): PartialUser = this.copy(user = user.copy(primaryUsername = Some(PrimaryUsername(Username(name), Username(name)))))
    def withPictureName(name: String) = this.copy(user = user.copy(pictureName = Some(name)))
    def withState(state: State[User]) = this.copy(user = user.copy(state = state))
    def withExperiments(extraExperiments: UserExperimentType*) = this.copy(experiments = experiments ++ extraExperiments)
    def withSeq(seq: Int) = this.copy(user = user.copy(seq = SequenceNumber[User](seq)))
    def get: User = user
  }

  implicit class PartialUserSeq(users: Seq[PartialUser]) {
    def get: Seq[User] = users.map(_.get)
  }

}
