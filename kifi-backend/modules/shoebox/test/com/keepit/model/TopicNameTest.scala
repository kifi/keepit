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


class TopicNameTest extends Specification with TestDBRunner{
  def setup()(implicit injector: Injector) = {
    val numTopics = 10
    val names = (0 until numTopics).map{ i => "topic%d".format(i)}
    val repo = inject[TopicNameRepoA]
    val topics = db.readWrite{ implicit s =>
      names.map{ name =>
        repo.save(TopicName(topicName = name))
      }
    }
    topics
  }

  "TopicNameRepo" should {
    "persist data" in {
      withDB() { implicit injector =>
        val repo = inject[TopicNameRepoA]
        val topics = setup()
        val numTopics = 10
        val names = (0 until numTopics).map{ i => "topic%d".format(i)}
        db.readOnly{ implicit s =>
          repo.getAllNames() === names
        }
      }
    }

    "be able to update name" in {
      withDB() { implicit injector =>
        val repo = inject[TopicNameRepoA]
        val topics = setup()
        val numTopics = 10
        val names = (0 until numTopics).map{ i => "topic%d".format(i)}
        db.readWrite{ implicit s =>
          repo.updateName(Id[TopicName](1), "math")
          repo.updateName(Id[TopicName](100), "whatever")   // Non-exist ID
        }

        db.readOnly{ implicit s =>
          repo.getName(Id[TopicName](1)) === Some("math")
          repo.getAllNames.drop(1) === names.drop(1)
          repo.getName(Id[TopicName](100)) === None
        }
      }
    }
  }

}
