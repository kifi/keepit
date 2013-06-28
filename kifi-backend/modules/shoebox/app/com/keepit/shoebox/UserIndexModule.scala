package com.keepit.shoebox

import com.google.inject.{Provides, Singleton}
import com.keepit.shoebox.usersearch._
import com.keepit.common.db.slick._
import com.keepit.model._
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule

case class UserIndexModule() extends ScalaModule {

  def configure {}

  @Singleton
  @Provides
  def UserIndex(db: Database, userRepo: UserRepo): UserIndex = {
    val userIndex = new UserIndex()
    db.readOnly { implicit s =>
      userIndex.addUsers(userRepo.allExcluding(UserStates.INACTIVE))
    }
    userIndex
  }

}
