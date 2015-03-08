package com.keepit.commanders

import com.keepit.abook.model.EmailAccountInfo
import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.graph.model.{ RelatedEntities, SociallyRelatedEntities }
import com.keepit.graph.{ FakeGraphServiceClientImpl, FakeGraphServiceModule, GraphServiceClient }
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class UserProfileCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeGraphServiceModule(),
    FakeSocialGraphModule()
  )

  "getConnectionsSortedByRelationship" in {
    withDb(modules: _*) { implicit injector =>
      val (owner, viewer, user1, user2, user3, user4, user5, user6, user7) = db.readWrite { implicit s =>
        val user1 = user().saved
        val user2 = user().saved
        val user3 = user().saved
        val user4 = user().saved
        val user5 = user().saved
        val user6 = user().saved
        val user7 = user().saved

        val owner = user().saved
        val viewer = user().saved

        connect(viewer -> owner).saved //mc

        connect(viewer -> user1).saved //mc
        connect(viewer -> user4).saved //mc - 0.1
        connect(viewer -> user5).saved
        connect(viewer -> user6).saved //mc

        connect(owner -> user1).saved //mc
        connect(owner -> user2).saved //   - 0.2
        connect(owner -> user3).saved //   - 0.3
        connect(owner -> user4).saved //mc - 0.1
        connect(owner -> user6).saved //mc
        connect(owner -> user7).saved
        (owner, viewer, user1, user2, user3, user4, user5, user6, user7)
      }

      val relationship = SociallyRelatedEntities(
        RelatedEntities[User, User](owner.id.get, Seq(user4.id.get -> .1, user5.id.get -> .4, user2.id.get -> .2, user3.id.get -> .3)),
        RelatedEntities[User, SocialUserInfo](owner.id.get, Seq.empty),
        RelatedEntities[User, SocialUserInfo](owner.id.get, Seq.empty),
        RelatedEntities[User, EmailAccountInfo](owner.id.get, Seq.empty)
      )

      Await.result(inject[GraphServiceClient].getSociallyRelatedEntities(viewer.id.get), Duration.Inf) === None
      inject[FakeGraphServiceClientImpl].setSociallyRelatedEntities(viewer.id.get, relationship)
      Await.result(inject[FakeGraphServiceClientImpl].getSociallyRelatedEntities(viewer.id.get), Duration.Inf).get === relationship
      Await.result(inject[GraphServiceClient].getSociallyRelatedEntities(viewer.id.get), Duration.Inf).get === relationship
      val commander = inject[UserProfileCommander]
      val connections = Await.result(commander.getConnectionsSortedByRelationship(viewer.id.get, owner.id.get), Duration.Inf)

      connections.map(_.userId) === Seq(viewer, user4, user1, user6, user3, user2, user7).map(_.id.get)
      connections.map(_.connected) === Seq(true, true, true, true, false, false, false)

      val selfConnections = Await.result(commander.getConnectionsSortedByRelationship(owner.id.get, owner.id.get), Duration.Inf)

      selfConnections.map(_.userId) === Seq(user4, viewer, user3, user1, user7, user6, user2).map(_.id.get)
      selfConnections.map(_.connected) === Seq(true, true, true, true, true, true, true)

      val repo = inject[UserConnectionRepo]

      db.readWrite { implicit s =>
        repo.getConnectedUsers(viewer.id.get).contains(owner.id.get) === true
        repo.getConnectedUsers(owner.id.get).contains(viewer.id.get) === true
        val connections = repo.getConnectedUsersForUsers(Set(viewer.id.get, owner.id.get))
        connections(viewer.id.get).contains(owner.id.get) === true
        connections(owner.id.get).contains(viewer.id.get) === true
      }

      db.readWrite { implicit s =>
        repo.getConnectedUsers(viewer.id.get).contains(user1.id.get) === true
        repo.getConnectedUsers(user1.id.get).contains(viewer.id.get) === true
        val connections = repo.getConnectedUsersForUsers(Set(viewer.id.get, user1.id.get))
        connections(viewer.id.get).contains(user1.id.get) === true
        connections(user1.id.get).contains(viewer.id.get) === true
      }

      db.readWrite { implicit s =>
        repo.unfriendConnections(owner.id.get, Set(viewer.id.get)) === 1
        repo.unfriendConnections(viewer.id.get, Set(user1.id.get)) === 1
      }

      db.readWrite { implicit s =>
        repo.getConnectedUsers(viewer.id.get).contains(owner.id.get) === false
        repo.getConnectedUsers(owner.id.get).contains(viewer.id.get) === false
        val connections = repo.getConnectedUsersForUsers(Set(viewer.id.get, owner.id.get))
        connections(viewer.id.get).contains(owner.id.get) === false
        connections(owner.id.get).contains(viewer.id.get) === false
      }

      db.readWrite { implicit s =>
        repo.getConnectedUsers(viewer.id.get).contains(user1.id.get) === false
        repo.getConnectedUsers(user1.id.get).contains(viewer.id.get) === false
        val connections = repo.getConnectedUsersForUsers(Set(viewer.id.get, user1.id.get))
        connections(viewer.id.get).contains(user1.id.get) === false
        connections(user1.id.get).contains(viewer.id.get) === false
      }

      val connections2 = Await.result(commander.getConnectionsSortedByRelationship(viewer.id.get, owner.id.get), Duration.Inf)

      connections2.map(_.userId) === Seq(user4, user6, user3, user2, user1, user7).map(_.id.get)
      connections2.map(_.connected) === Seq(true, true, false, false, false, false)

      val selfConnections2 = Await.result(commander.getConnectionsSortedByRelationship(owner.id.get, owner.id.get), Duration.Inf)

      selfConnections2.map(_.userId) === Seq(user4, user3, user1, user7, user6, user2).map(_.id.get)
      selfConnections2.map(_.connected) === Seq(true, true, true, true, true, true)

    }

  }

}
