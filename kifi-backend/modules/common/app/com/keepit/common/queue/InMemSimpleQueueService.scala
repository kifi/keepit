package com.keepit.common.queue

import scala.collection.JavaConversions._
import java.util.concurrent.{ConcurrentLinkedQueue, ConcurrentHashMap}
import com.keepit.common.logging.Logging
import java.util.concurrent.atomic.AtomicInteger

class InMemSimpleQueueService extends SimpleQueueService with Logging {

  val base = "http://dev.ezkeep.com:9000/queues/"

  val name2Urls = new ConcurrentHashMap[String, String]
  val url2Queues = new ConcurrentHashMap[String, InMemSimpleQueue]

  override def getUrl(name: String):Option[String] = {
    val res = name2Urls.get(name)
    if (res == null) None else Some(res)
  }

  def getByUrl(url: String):Option[SimpleQueue] = {
    val res = url2Queues.get(url)
    log.info(s"[getByUrl($url)] res=$res")
    if (res == null) None else Some(res)
  }

  def list():Seq[String] = {
    url2Queues.keySet().toVector
  }

  def delete(url: String): Unit = {
    // todo(ray): name2Urls
    val q = url2Queues.remove(url)
    log.info(s"[delete] queue($url)=$q removed")
  }

  def create(name: String):String = {
    val url = s"$base$name"
    val prevUrl = name2Urls.putIfAbsent(name, url)
    val q = new InMemSimpleQueue(url, name)
    val prevQ = url2Queues.putIfAbsent(url, q)
    log.info(s"[create($name)] url=$url queue=$q (prevUrl=$prevUrl, prevQ=$prevQ)")
    url
  }

  override def toString = s"[InMemSimpleQueueService] #urls=${name2Urls.size}(${name2Urls.keySet()}) #queues=${url2Queues.size}(${url2Queues.keySet()})"

}

class InMemSimpleQueue(override val queueUrl:String, override val name:String) extends SimpleQueue with Logging {

  val q:ConcurrentLinkedQueue[SimpleQueueMessage] = new ConcurrentLinkedQueue[SimpleQueueMessage]
  val messages = new ConcurrentHashMap[Int, SimpleQueueMessage]

  val counter = new AtomicInteger(0)

  def delete(msgHandle: String): Unit = { // bad impl
    val iter = q.iterator
    for (m <- iter) {
      if (m.receiptHandle == msgHandle) {
        iter.remove
        log.info(s"[delete($msgHandle)] msg ($m) deleted")
      }
    }
  }

  def receive(): Seq[SimpleQueueMessage] = {
    val res = q.iterator.toSeq
    log.info(s"[receive] res=${res.mkString(",")}")
    res
  }

  def send(s: String): Unit = {
    val idx = counter.getAndIncrement
    val m = SimpleQueueMessage(System.currentTimeMillis.toString, idx.toString, idx.toString, "md5", s)
    q.add(m)
    log.info(s"[send($s)] msg ($m) sent")
  }

  override def toString = s"[InMemSimpleQueue($name,$queueUrl)] #messages=${q.size()} (${q.iterator().toSeq}})"
  
}