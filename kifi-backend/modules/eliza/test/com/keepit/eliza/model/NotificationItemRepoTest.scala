package com.keepit.eliza.model

import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.db.Id
import com.keepit.eliza.model.NotificationItem
import com.keepit.notify.model.DepressedRobotGrumble
import com.keepit.test.ElizaTestInjector
import org.specs2.mutable.Specification

import scala.util.Random

class NotificationItemRepoTest extends Specification with ElizaTestInjector {

  val modules = Seq(
    ElizaCacheModule()
  )

  "NotificationItem" should {

    "correctly compute external ids" in {
      withDb(modules: _*) { implicit injector =>
        val items = List(
          NotificationItem(id = Some(Id(1)), notificationId = Id(1), kind = DepressedRobotGrumble, event = null),
          NotificationItem(id = Some(Id(2)), notificationId = Id(1), kind = DepressedRobotGrumble, event = null),
          NotificationItem(id = Some(Id(3)), notificationId = Id(1), kind = DepressedRobotGrumble, event = null)
        )

        NotificationItem.externalIdFromItems(items.toSet) === NotificationItem.externalIdFromItems(Random.shuffle(items).toSet)

        val itemsWithMore =
          NotificationItem(id = Some(Id(4)), notificationId = Id(1), kind = DepressedRobotGrumble, event = null) +: items

        NotificationItem.externalIdFromItems(items.toSet) !== NotificationItem.externalIdFromItems(itemsWithMore.toSet)
      }
    }

  }

}
