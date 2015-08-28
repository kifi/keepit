package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.State
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.search.SearchServiceClient

import scala.concurrent.ExecutionContext

class UserCreationCommander @Inject() (
    db: Database,
    userRepo: UserRepo,
    handleCommander: HandleCommander,
    userValueRepo: UserValueRepo,
    searchClient: SearchServiceClient,
    libraryCommander: LibraryCommander,
    userExperimentRepo: UserExperimentRepo,
    implicit val executionContext: ExecutionContext) {

  def createUser(firstName: String, lastName: String, state: State[User]): User = {
    val newUser: User = db.readWrite(attempts = 3) { implicit session =>
      val user = userRepo.save(User(firstName = firstName, lastName = lastName, state = state))
      val userWithUsername = handleCommander.autoSetUsername(user) getOrElse {
        throw new Exception(s"COULD NOT CREATE USER [$firstName $lastName] SINCE WE DIDN'T FIND A USERNAME!!!")
      }
      userWithUsername
    }

    SafeFuture {
      db.readWrite(attempts = 3) { implicit session =>
        userValueRepo.setValue(newUser.id.get, UserValueName.AUTO_SHOW_GUIDE, true)
        userValueRepo.setValue(newUser.id.get, UserValueName.AUTO_SHOW_PERSONA, true) // todo, this shouldn't be true for all users, like when they were invited to a lib
        userValueRepo.setValue(newUser.id.get, UserValueName.EXT_SHOW_EXT_MSG_INTRO, true)
      }
      searchClient.warmUpUser(newUser.id.get)
      searchClient.updateUserIndex()
    }

    val userId = newUser.id.get
    libraryCommander.internSystemGeneratedLibraries(userId)
    if (userId.id % 2 == 0) { //for half of the users
      libraryCommander.createReadItLaterLibrary(userId)
      db.readWrite { implicit session =>
        userExperimentRepo.save(UserExperiment(userId = userId, experimentType = UserExperimentType.READ_IT_LATER))
      }
    }

    newUser
  }

}
