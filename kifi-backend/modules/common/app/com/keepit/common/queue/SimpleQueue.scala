package com.keepit.common.queue

case class SimpleQueueMessage(ts:String, id:String, receiptHandle:String, md5Body:String, body:String) {
  override def toString = s"SimpleQueueMessage(id=$id,body=${body},receiptHandle=${receiptHandle.take(20)})"
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
  def receive():Seq[SimpleQueueMessage]
  def delete(msgHandle:String):Unit
}

