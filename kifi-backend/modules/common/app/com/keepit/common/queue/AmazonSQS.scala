package com.keepit.common.queue

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model._
import scala.collection.JavaConversions._
import com.keepit.common.performance._
import com.keepit.common.logging.Logging

//@deprecated("In favor of FormattedSQSQueue, see SimpleQueueModule.scala", "Feb 19 2014")
class AmazonSQS(val client:AmazonSQSClient) extends SimpleQueueService with Logging {
  def create(name: String) = {
    client.createQueue(new CreateQueueRequest(name)).getQueueUrl
  }

  def delete(url: String): Unit = {
    client.deleteQueue(new DeleteQueueRequest(url))
  }

  def list(): Seq[String] = {
    val res = client.listQueues()
    res.getQueueUrls
  }

  def getUrl(name: String): Option[String] = {
    val qUrl = client.getQueueUrl(new GetQueueUrlRequest(name))
    if (qUrl == null) None else Some(qUrl.getQueueUrl)
  }

  def getByUrl(url: String): Option[SimpleQueue] = {
    val q = new AmazonSQSQueue(url, url, client) // name is incorrect
    Some(q)
  }
}


object AmazonSQSQueue {
  val SENT_TS = "SentTimestamp"
  val APPROX_FIRST_RECEIVE_TS = "ApproximateFirstReceiveTimestamp"
}

import AmazonSQSQueue._
//@deprecated("In favor of FormattedSQSQueue, see SimpleQueueModule.scala", "Feb 19 2014")
class AmazonSQSQueue(override val queueUrl:String, override val name:String, client:AmazonSQSClient) extends SimpleQueue with Logging {

  def delete(msgHandle: String): Unit = {
    timing(s"SQS.delete($queueUrl) ${msgHandle.take(20)}") {
      client.deleteMessage(new DeleteMessageRequest(queueUrl, msgHandle))
    }
  }

  def receive(): Seq[SimpleQueueMessage] = {
    val messages = timing(s"SQS.receive($queueUrl)") {
      client.receiveMessage(new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(10).withVisibilityTimeout(300).withAttributeNames(SENT_TS, APPROX_FIRST_RECEIVE_TS)).getMessages // todo(ray):from config
    }
    val res = messages.map { m =>
      val body = m.getBody
      val sentTS = m.getAttributes.get(SENT_TS)
      val approxFirstRecvTS = m.getAttributes.get(APPROX_FIRST_RECEIVE_TS)
      val sqm = SimpleQueueMessage(
        if (sentTS != null) sentTS else "",
        m.getMessageId,
        m.getReceiptHandle,
        m.getMD5OfBody,
        m.getBody
      )
      log.info(s"[SQS.receive($queueUrl)] body=$body sentTS=$sentTS approxFirstRecvTS=$approxFirstRecvTS")
      sqm
    }
    res
  }

  def send(s: String): Unit = {
    timing(s"SQS.send($queueUrl) $s") {
      client.sendMessage(new SendMessageRequest(queueUrl, s))
    }
  }
}
