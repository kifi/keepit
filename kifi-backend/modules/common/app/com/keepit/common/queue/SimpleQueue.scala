package com.keepit.common.queue

case class SQSMessage(id:String, receiptHandle:String, md5Body:String, body:String /* , attributes:Map[String, String] */) {
  override def toString = s"SQSMessage(id=$id,body=${body},receiptHandle=${receiptHandle.take(20)})"
}

trait SimpleQueueService {
  def create(name:String):String
  def delete(url:String):Unit
  def list():Seq[String]
  def getUrl(name:String):Option[String]
  def getByUrl(url:String):Option[SimpleQueue]
}

trait SimpleQueue {
  def name:String
  def queueUrl:String
  def send(s:String):Unit
  def receive():Seq[SQSMessage]
  def delete(msgHandle:String):Unit
}

