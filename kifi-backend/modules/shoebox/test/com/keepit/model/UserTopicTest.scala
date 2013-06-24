package com.keepit.model
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.time.zones.PT
import org.specs2.mutable.Specification
import com.keepit.test.TestDBRunner
import com.google.inject.Injector
import com.keepit.common.db.Id
import scala.Array.canBuildFrom
import com.keepit.learning.topicmodel.TopicModelGlobal


class UserTopicTest extends Specification with TestDBRunner {

  def genTopic(numTopics: Int, userIdx: Int, default: Int = 1, personal: Int = 10) = {
      val topic = (new Array[Int](numTopics)).map(_ + default)
      topic(userIdx) = personal
      topic
  }

  def setup()(implicit injector: Injector) = {
    val t = new DateTime(2013, 5, 20, 21, 59, 0, 0, PT)
    val numTopics = TopicModelGlobal.numTopics
    val ids = (0 until numTopics).map{Id[User](_)}
    val userTopics = (0 until numTopics).map{ i =>
      genTopic(numTopics, i)
    }
    val userTopicRepo = inject[UserTopicRepo]
    val helper = new UserTopicByteArrayHelper
    db.readWrite { implicit s =>
      (ids zip userTopics) map { x =>
        val userTopic = UserTopic(userId = x._1, topic = helper.toByteArray(x._2), createdAt = t)
        userTopicRepo.save(userTopic)
      }

    }
    userTopics
  }

  "userTopicRepo" should {
    "correctly persist user topic" in {
      withDB() { implicit injector =>
        val userTopics = setup()
        val numTopics = TopicModelGlobal.numTopics
        val helper = new UserTopicByteArrayHelper
        val userTopicRepo = inject[UserTopicRepo]
        db.readOnly { implicit s =>
          (0 until numTopics).foreach {i =>
            val userId = Id[User](i)
            val userTopic = userTopicRepo.getByUserId(userId)
            val topic = userTopic match {
              case Some(userTopic) => userTopic.topic
              case None => helper.toByteArray(genTopic(numTopics, i, 1, 1))
            }
            helper.toIntArray(topic).toSeq === genTopic(numTopics, i).toSeq
          }
        }
      }

    }
  }

}