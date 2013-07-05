package com.keepit.model
import org.joda.time.DateTime
import com.keepit.common.time.zones.PT
import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import scala.Array.canBuildFrom
import com.keepit.learning.topicmodel.TopicModelGlobal
import play.api.libs.json._
import com.keepit.test._
import com.keepit.common.cache._



class UserTopicTest extends Specification with ShoeboxTestInjector {

  def genTopic(numTopics: Int, userIdx: Int, default: Int = 1, personal: Int = 10) = {
      val topic = (new Array[Int](numTopics)).map(_ + default)
      topic(userIdx) = personal
      topic
  }

  // user i concentrates on topic i
  def setup()(implicit injector: Injector) = {
    val t = new DateTime(2013, 5, 20, 21, 59, 0, 0, PT)
    val numTopics = TopicModelGlobal.numTopics
    val ids = (0 until numTopics).map{Id[User](_)}
    val userTopics = (0 until numTopics).map{ i =>
      genTopic(numTopics, i)
    }
    val userTopicRepo = inject[UserTopicRepo]
    val helper = new UserTopicByteArrayHelper
    val userTopicFromDb = db.readWrite { implicit s =>
      (ids zip userTopics) map { x =>
        val userTopic = UserTopic(userId = x._1, topic = helper.toByteArray(x._2), createdAt = t)
        userTopicRepo.save(userTopic)
      }

    }
    userTopicFromDb
  }

  "userTopicRepo" should {
    "correctly persist user topic and be able to delete all" in {
      withDb() { implicit injector =>
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

        db.readWrite{ implicit s =>
          userTopicRepo.deleteAll()
        } === userTopics.size
      }

    }
  }

  "userTopic Serializer " should {
    "work" in {
      val t1 = new DateTime(2013, 5, 20, 21, 59, 0, 0, PT)
      val t2 = new DateTime(2013, 5, 22, 21, 59, 0, 0, PT)
      val topic = new Array[Int](TopicModelGlobal.numTopics)
      topic(1) = 1; topic(5) = 5;
      val helper = new UserTopicByteArrayHelper
      val userTopic = new UserTopic(id = Some(Id[UserTopic](1)), userId = Id[User](2), topic = helper.toByteArray(topic), createdAt = t1, updatedAt = t2)
      import UserTopic.userTopicFormat
      val js = Json.toJson(userTopic)
      val recovered = Json.fromJson[UserTopic](js).get
      recovered.id === userTopic.id
      recovered.userId === userTopic.userId
      recovered.updatedAt === userTopic.updatedAt
      recovered.createdAt === userTopic.createdAt
      helper.toIntArray(recovered.topic) === helper.toIntArray(userTopic.topic)
    }
  }

  "userTopic cache" should {
    "work" in {
      withDb(ShoeboxCacheModule(HashMapMemoryCacheModule())) { implicit injector =>
        val userTopics = setup()
        val userTopicRepo = inject[UserTopicRepo]
        val helper = new UserTopicByteArrayHelper

        db.readOnly{ implicit s =>
          (0 until userTopics.size).foreach{ i =>
            val userTopic = userTopicRepo.getByUserId(Id[User](i)).get      // should in cache now
            helper.toIntArray(userTopic.topic) === helper.toIntArray(userTopics(i).topic)
          }
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnly { implicit s =>
            (0 until userTopics.size).foreach { i =>
              val userTopic = userTopicRepo.getByUserId(Id[User](i)).get
              helper.toIntArray(userTopic.topic) === helper.toIntArray(userTopics(i).topic)
            }
          }
        }

        // update repo
        db.readWrite{ implicit s =>
          (0 until userTopics.size).foreach{ i =>
            val userTopic = userTopicRepo.getByUserId(Id[User](i)).get
            val old = helper.toIntArray(userTopic.topic)
            old(i) *= 2     // score on topic i doubles
            userTopicRepo.save(userTopic.copy(topic = helper.toByteArray(old)))     // this should invalidate the cache
          }
        }

        db.readOnly{ implicit s =>
          (0 until userTopics.size).foreach{ i =>
            val userTopic = userTopicRepo.getByUserId(Id[User](i)).get
            helper.toIntArray(userTopic.topic) === {val x = helper.toIntArray(userTopics(i).topic); x(i) *= 2; x}
          }
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnly { implicit s =>
            (0 until userTopics.size).foreach { i =>
              val userTopic = userTopicRepo.getByUserId(Id[User](i)).get
              helper.toIntArray(userTopic.topic) === {val x = helper.toIntArray(userTopics(i).topic); x(i) *= 2; x}
            }
          }
        }
      }
    }
  }

}