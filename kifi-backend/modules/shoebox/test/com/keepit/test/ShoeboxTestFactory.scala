package com.keepit.test

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.{ UserConnection, User, UserRepo, UserConnectionRepo }

@Singleton
class ShoeboxTestFactory @Inject() (userConnRepo: UserConnectionRepo, userRepo: UserRepo) {

  def createUsersWithConnections()(implicit rw: RWSession): Seq[User] = {
    val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Paul"))
    val user2 = userRepo.save(User(firstName = "Bryan", lastName = "Cranston"))
    val user3 = userRepo.save(User(firstName = "Anna", lastName = "Gunn"))
    val user4 = userRepo.save(User(firstName = "Dean", lastName = "Norris"))

    userConnRepo.save(UserConnection(user1 = user2.id.get, user2 = user3.id.get))
    userConnRepo.save(UserConnection(user1 = user2.id.get, user2 = user4.id.get))
    userConnRepo.save(UserConnection(user1 = user3.id.get, user2 = user4.id.get))
    userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user2.id.get))
    userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user3.id.get))

    Seq(user1, user2, user3, user4)
  }
}
