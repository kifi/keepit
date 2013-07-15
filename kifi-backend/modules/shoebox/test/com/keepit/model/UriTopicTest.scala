package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.time.zones.PT
import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import scala.Array.canBuildFrom
import com.keepit.test._
import com.keepit.learning.topicmodel.TopicModelGlobalTest

class UriTopicTest extends Specification with ShoeboxTestInjector {

  val numTopics = TopicModelGlobalTest.numTopics

  def genTopic(numTopics: Int, uriIdx: Int, default: Double = 0.1, nonDefault: Double = 0.8) = {
    val topic = (new Array[Double](numTopics)).map(_ + default)
    topic(uriIdx) = nonDefault
    topic
  }

  // uri i concentrates on topic i
  def setup()(implicit injector: Injector) = {
    val t = new DateTime(2013, 5, 20, 21, 59, 0, 0, PT)
    val numUris = 10
    val ids =  (0 until numUris).map{Id[NormalizedURI](_)}
    val topics = (0 until numUris).map{ genTopic(numTopics, _) }
    val helper = new UriTopicHelper
    val uriTopicRepo = inject[UriTopicRepoA]
    db.readWrite{ implicit s =>
      val uriTopics = (ids zip topics) map { x =>
        val assignedTopics = helper.assignTopics(x._2, numTopics)
        val uriTopic = UriTopic(uriId = x._1, topic = helper.toByteArray(x._2, numTopics), primaryTopic = assignedTopics._1, secondaryTopic = assignedTopics._2, createdAt = t)
        uriTopicRepo.save(uriTopic)
        uriTopic
      }
      uriTopics
    }
  }

  "uriTopicRepo" should {
    "persist data" in {
      withDb() { implicit injector =>
        val uriTopicRepo = inject[UriTopicRepoA]
        val uriTopics = setup()
        val numDocs = uriTopics.size
        val helper = new UriTopicHelper
          db.readOnly{ implicit s =>
          (0 until numDocs).foreach{ i =>
            val uriTopic = uriTopicRepo.getByUriId(Id[NormalizedURI](i)).get
            helper.toDoubleArray(uriTopic.topic, numTopics) === helper.toDoubleArray(uriTopics(i).topic, numTopics)
            uriTopic.primaryTopic === uriTopics(i).primaryTopic
            uriTopic.secondaryTopic === uriTopics(i).secondaryTopic
          }
        }
      }
    }

    "retrieve uris by topic" in {
      withDb() { implicit injector =>
        val uriTopicRepo = inject[UriTopicRepoA]
        val uriTopics = setup()
        val numDocs = uriTopics.size
        (0 until numDocs).foreach{ i =>
          db.readOnly{ implicit s =>
            uriTopicRepo.getUrisByTopic(i) === List(Id[NormalizedURI](i))
          }
        }
      }
    }

    "be able to delete all data" in {

        withDb() { implicit injector =>
        val uriTopicRepo = inject[UriTopicRepoA]
        val uriTopics = setup()
        val numDocs = uriTopics.size
        val helper = new UriTopicHelper
          db.readOnly{ implicit s =>
          (0 until numDocs).foreach{ i =>
            val uriTopic = uriTopicRepo.getByUriId(Id[NormalizedURI](i)).get
            helper.toDoubleArray(uriTopic.topic, numTopics) === helper.toDoubleArray(uriTopics(i).topic, numTopics)
            uriTopic.primaryTopic === uriTopics(i).primaryTopic
            uriTopic.secondaryTopic === uriTopics(i).secondaryTopic
          }
        }

        db.readWrite{ implicit s =>
          uriTopicRepo.deleteAll()
        } === numDocs
      }
    }

    "helper should correctly assignTopics" in {
      val N = numTopics
      val helper = new UriTopicHelper
      val t = new Array[Double](N)
      helper.assignTopics(t, N) === (None, None)
      t(3) = 0.8; t(5) = 0.2
      helper.assignTopics(t, N) === (Some(3), None)
      t(5) = 0.7
      helper.assignTopics(t, N) === (Some(3), Some(5))
      t(10) = 1.0
      helper.assignTopics(t, N) === (Some(10), Some(3))
      t(11) = 1.0
      helper.assignTopics(t, N) === (Some(10), Some(11))
    }

    "helper should correclty convert between doubleArray and ArrayByte" in {
      val N = numTopics
      val a = new Array[Double](N)
      val helper = new UriTopicHelper
      a(0) = 1.0; a(3) = 0.5; a(5) = 0.7;
      val b = helper.toByteArray(a, N)
      helper.toDoubleArray(b, N).toArray === a

      a(8) = 0.4
      helper.toDoubleArray(helper.toByteArray(a, N), N).toArray === a
    }
  }

}
