package com.keepit.test

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ Username, UserConnection, User, UserRepo, UserConnectionRepo }

@Singleton
class ShoeboxTestFactory @Inject() (userConnRepo: UserConnectionRepo, userRepo: UserRepo) {

  def createUsers()(implicit rw: RWSession) = {
    (
      userRepo.save(User(firstName = "Aaron", lastName = "Paul", username = Username("test"), normalizedUsername = "test")),
      userRepo.save(User(firstName = "Bryan", lastName = "Cranston", username = Username("test2"), normalizedUsername = "test2")),
      userRepo.save(User(firstName = "Anna", lastName = "Gunn", primaryEmail = Some(EmailAddress("test@gmail.com")), username = Username("test3"), normalizedUsername = "test3")),
      userRepo.save(User(firstName = "Dean", lastName = "Norris", username = Username("test4"), normalizedUsername = "test4"))
    )
  }

  def createUsersWithConnections()(implicit rw: RWSession): Seq[User] = {
    val (user1, user2, user3, user4) = createUsers()
    userConnRepo.save(UserConnection(user1 = user2.id.get, user2 = user3.id.get))
    userConnRepo.save(UserConnection(user1 = user2.id.get, user2 = user4.id.get))
    userConnRepo.save(UserConnection(user1 = user3.id.get, user2 = user4.id.get))
    userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user2.id.get))
    userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user3.id.get))

    Seq(user1, user2, user3, user4)
  }
}
