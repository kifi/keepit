package com.keepit.common.queue

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model._
import scala.collection.JavaConversions._
import com.keepit.common.performance._
import com.keepit.common.logging.Logging

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

class AmazonSQSQueue(override val queueUrl:String, override val name:String, client:AmazonSQSClient) extends SimpleQueue with Logging {

  def delete(msgHandle: String): Unit = {
    timing(s"SQS.delete($queueUrl) ${msgHandle.take(20)}") {
      client.deleteMessage(new DeleteMessageRequest(queueUrl, msgHandle))
    }
  }

  def receive(): Seq[SimpleQueueMessage] = {
    val messages = timing(s"SQS.receive($queueUrl)") {
      client.receiveMessage(new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(10).withVisibilityTimeout(300)).getMessages // todo(ray):from config
    }
    val res = messages.map { m =>
      SimpleQueueMessage(
        System.currentTimeMillis,
        m.getMessageId,
        m.getReceiptHandle,
        m.getMD5OfBody,
        m.getBody
      )
    }
    res
  }

  def send(s: String): Unit = {
    timing(s"SQS.send($queueUrl) $s") {
      client.sendMessage(new SendMessageRequest(queueUrl, s))
    }
  }
}
