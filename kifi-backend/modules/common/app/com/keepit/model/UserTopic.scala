package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.search.topicModel.TopicModelGlobal
import java.io.{DataOutputStream, DataInputStream, ByteArrayInputStream, ByteArrayOutputStream}

case class UserTopic(
  id: Option[Id[UserTopic]] = None,
  userId: Id[User],
  topic: Array[Byte],     // hold int array, histogram over topics
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) extends Model[UserTopic] {
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[UserTopic]) = this.copy(id = Some(id))
}

class UserTopicByteArrayHelper {
  def toByteArray(arr: Array[Int]) = {
    assume(arr.length == TopicModelGlobal.numTopics, "topic array size not matching TopicModelGlobal.numTopics")
    val bs = new ByteArrayOutputStream(TopicModelGlobal.numTopics * 4)
    val os = new DataOutputStream(bs)
    arr.foreach{os.writeInt}
    os.close()
    val rv = bs.toByteArray()
    bs.close()
    rv
  }

  def toIntArray(arr: Array[Byte]) = {
    assume(arr.size == TopicModelGlobal.numTopics * 4, "topic array size not matching TopicModelGlobal.numTopics")
    val is = new DataInputStream(new ByteArrayInputStream(arr))
    val topic = (0 until TopicModelGlobal.numTopics).map{i => is.readInt()}
    is.close()
    topic
  }
}
