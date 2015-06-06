package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
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
          val user1 = UserFactory.user().withName("George", "Washington").withUsername("GDubs").saved
          val user2 = UserFactory.user().withName("Abe", "Lincoln").withUsername("VampireXSlayer").saved
          val user3 = UserFactory.user().withName("Ben", "Franklin").withUsername("Benji").saved
          val user4 = EmailAddress("unclesam@usa.gov")

          userValueRepo.getValue(user1.id.get, UserValues.recentInteractions).as[List[JsObject]].length === 0
          (user1, user2, user3, user4)
        }

        val interactionSeq = Seq(
          (UserRecipient(user2.id.get), UserInteraction.MESSAGE_USER),
          (UserRecipient(user3.id.get), UserInteraction.MESSAGE_USER),
          (EmailRecipient(user4), UserInteraction.MESSAGE_USER),
          (UserRecipient(user2.id.get), UserInteraction.MESSAGE_USER),
          (UserRecipient(user3.id.get), UserInteraction.MESSAGE_USER),
          (UserRecipient(user2.id.get), UserInteraction.MESSAGE_USER)
        )
        userInteractionCommander.addInteractions(user1.id.get, interactionSeq)

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
        val sortedScores = userInteractionCommander.getRecentInteractions(user1.id.get)
        val scoresOnly = sortedScores.map(_.score)
        scoresOnly === scoresOnly.sorted.reverse
        sortedScores.map(_.recipient).toList === List(UserRecipient(user2.id.get), UserRecipient(user3.id.get), EmailRecipient(user4))

        // test interaction array keeps X most recent interactions
        for (i <- 1 to UserInteraction.maximumInteractions) {
          userInteractionCommander.addInteractions(user1.id.get, Seq((UserRecipient(user2.id.get), UserInteraction.MESSAGE_USER)))
        }
        db.readOnlyMaster { implicit session =>
          userValueRepo.getValue(user1.id.get, UserValues.recentInteractions).as[List[JsObject]].size === UserInteraction.maximumInteractions
        }
      }
    }
  }
}
