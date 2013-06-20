package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.time.zones.PT
import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import scala.Array.canBuildFrom
import play.api.Play.current
import com.keepit.test._
import com.keepit.inject._
import com.keepit.learning.topicmodel.TopicModelGlobal

class UriTopicTest extends Specification with TestDBRunner{
  def genTopic(numTopics: Int, uriIdx: Int, default: Double = 0.1, nonDefault: Double = 0.8) = {
    val topic = (new Array[Double](numTopics)).map(_ + default)
    topic(uriIdx) = nonDefault
    topic
  }

  def setup()(implicit injector: Injector) = {
    val t = new DateTime(2013, 5, 20, 21, 59, 0, 0, PT)
    val numTopics = TopicModelGlobal.numTopics
    val numUris = 10
    val ids =  (0 until numUris).map{Id[NormalizedURI](_)}
    val topics = (0 until numUris).map{ genTopic(numTopics, _) }
    val helper = new UriTopicHelper
    val uriTopicRepo = inject[UriTopicRepo]
    db.readWrite{ implicit s =>
      val uriTopics = (ids zip topics) map { x =>
        val assignedTopics = helper.assignTopics(x._2)
        val uriTopic = UriTopic(uriId = x._1, topic = helper.toByteArray(x._2), primaryTopic = assignedTopics._1, secondaryTopic = assignedTopics._2, createdAt = t)
        uriTopicRepo.save(uriTopic)
        uriTopic
      }
      uriTopics
    }
  }

  "uriTopicRepo" should {
    "persist data" in {
      withDB() { implicit injector =>
        val uriTopicRepo = inject[UriTopicRepo]
        val uriTopics = setup()
        val numDocs = uriTopics.size
        val helper = new UriTopicHelper
          db.readOnly{ implicit s =>
          (0 until numDocs).foreach{ i =>
            val uriTopic = uriTopicRepo.getByUriId(Id[NormalizedURI](i)).get
            helper.toDoubleArray(uriTopic.topic) === helper.toDoubleArray(uriTopics(i).topic)
            uriTopic.primaryTopic === uriTopics(i).primaryTopic
            uriTopic.secondaryTopic === uriTopics(i).secondaryTopic
          }
        }
      }
    }

    "helper should correctly assignTopics" in {
      val N = TopicModelGlobal.numTopics
      val helper = new UriTopicHelper
      val t = new Array[Double](N)
      helper.assignTopics(t) === (None, None)
      t(3) = 0.8; t(5) = 0.2
      helper.assignTopics(t) === (Some(3), None)
      t(5) = 0.7
      helper.assignTopics(t) === (Some(3), Some(5))
      t(10) = 1.0
      helper.assignTopics(t) === (Some(10), Some(3))
      t(11) = 1.0
      helper.assignTopics(t) === (Some(10), Some(11))
    }

    "helper should correclty convert between doublArray and ArrayByte" in {
      val N = TopicModelGlobal.numTopics
      val a = new Array[Double](N)
      val helper = new UriTopicHelper
      a(0) = 1.0; a(3) = 0.5; a(5) = 0.7;
      val b = helper.toByteArray(a)
      helper.toDoubleArray(b).toArray === a

      a(8) = 0.4
      helper.toDoubleArray(helper.toByteArray(a)).toArray === a
    }
  }

}
