package com.keepit.common.queue

import scala.collection.JavaConversions._
import java.util.concurrent.{ConcurrentLinkedQueue, ConcurrentHashMap}

class InMemSimpleQueueService extends SimpleQueueService {

  val base = "http://dev.ezkeep.com:9000/queues/"

  val urls = new ConcurrentHashMap[String, String]

  val queues = new ConcurrentHashMap[String, InMemSimpleQueue]


  override def getUrl(name: String):Option[String] = {
    val res = urls.get(name)
    if (res == null) None else Some(res)
  }

  def getByUrl(url: String):Option[SimpleQueue] = {
    val res = queues.get(url)
    if (res == null) None else Some(res)
  }

  def list():Seq[String] = {
    queues.keySet().toVector
  }

  def delete(url: String): Unit = {
    queues.remove(url)
  }

  def create(name: String):String = {
    val url = s"$base$name"
    urls.putIfAbsent(name, url)
    val q = new InMemSimpleQueue(url, name)
    queues.putIfAbsent(name, q)
    url
  }

}

class InMemSimpleQueue(override val queueUrl:String, override val name:String) extends SimpleQueue {

  val q:ConcurrentLinkedQueue[String] = new ConcurrentLinkedQueue[String]

  def delete(msgHandle: String): Unit = { } // todo

  def receive(): Seq[SQSMessage] = {
    q.iterator.toSeq.map(s => SQSMessage("1", "1", "md5", s)) // todo
  }

  def send(s: String): Unit = {
    q.add(s)
  }
  
}