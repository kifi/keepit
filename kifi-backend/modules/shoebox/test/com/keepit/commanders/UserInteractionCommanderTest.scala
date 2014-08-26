package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ UserValues, Username, User, UserValueRepo }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.JsObject

class UserInteractionCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq()

  "UserInteractionCommander" should {
    "calculate interaction score" in {
      withDb(modules: _*) { implicit injector =>
        val userInteractionCommander = inject[UserInteractionCommander]
        val userValueRepo = inject[UserValueRepo]
        val (user1, user2, user3, user4) = db.readWrite { implicit session =>
          val user1 = userRepo.save(User(firstName = "George", lastName = "Washington", username = Some(Username("GDubs"))))
          val user2 = userRepo.save(User(firstName = "Abe", lastName = "Lincoln", username = Some(Username("VampireXSlayer"))))
          val user3 = userRepo.save(User(firstName = "Ben", lastName = "Franklin", username = Some(Username("Benji"))))
          val user4 = EmailAddress("unclesam@usa.gov")

          userValueRepo.getValue(user1.id.get, UserValues.recentInteractions).as[List[JsObject]].length === 0
          (user1, user2, user3, user4)
        }

        userInteractionCommander.addInteraction(user1.id.get, Left(user2.id.get), UserInteraction.MESSAGE_USER)
        userInteractionCommander.addInteraction(user1.id.get, Left(user3.id.get), UserInteraction.MESSAGE_USER)
        userInteractionCommander.addInteraction(user1.id.get, Right(user4), UserInteraction.MESSAGE_USER)
        userInteractionCommander.addInteraction(user1.id.get, Left(user2.id.get), UserInteraction.MESSAGE_USER)
        userInteractionCommander.addInteraction(user1.id.get, Left(user3.id.get), UserInteraction.MESSAGE_USER)
        userInteractionCommander.addInteraction(user1.id.get, Left(user2.id.get), UserInteraction.MESSAGE_USER)

        def createList(objs: List[JsObject]) = {
          objs.map { o =>
            val idOpt = (o \ "user").asOpt[Id[User]]
            idOpt match {
              case Some(id) => id
              case None => (o \ "email").as[String]
            }
          }
        }

        db.readOnlyMaster { implicit session =>
          val list = userValueRepo.getValue(user1.id.get, UserValues.recentInteractions).as[List[JsObject]]
          createList(list) === List(user2.id.get, user3.id.get, user2.id.get, user4.address, user3.id.get, user2.id.get)
          userValueRepo.count === 1
        }

        // test interaction scores are sorted in decreasing order
        val sortedScores = userInteractionCommander.getInteractionScores(user1.id.get)
        val scoresOnly = sortedScores.map(_.score)
        scoresOnly === scoresOnly.sorted.reverse
        sortedScores.map(_.entity).toList === List(Left(user2.id.get), Left(user3.id.get), Right(user4))

        // test interaction array keeps X most recent interactions
        for (i <- 1 to UserInteraction.maximumInteractions) {
          userInteractionCommander.addInteraction(user1.id.get, Left(user2.id.get), UserInteraction.MESSAGE_USER)
        }
        db.readOnlyMaster { implicit session =>
          userValueRepo.getValue(user1.id.get, UserValues.recentInteractions).as[List[JsObject]].size === UserInteraction.maximumInteractions
        }
      }
    }
  }
}
