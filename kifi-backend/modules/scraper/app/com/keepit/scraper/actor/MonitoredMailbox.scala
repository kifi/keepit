package com.keepit.scraper.actor

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch._
import com.keepit.common.logging.Logging
import com.typesafe.config.Config

import scala.ref.WeakReference

// see http://doc.akka.io/docs/akka/snapshot/scala/mailboxes.html

trait MonitoredMessageQueueSemantics

class MonitoredMessageQueue extends MessageQueue with MonitoredMessageQueueSemantics with Logging {
  private final val queue = new ConcurrentLinkedQueue[Envelope]()

  def enqueue(receiver: ActorRef, handle: Envelope): Unit = {
    log.info(s"[enqueue(sz=$numberOfMessages)] receiver(path=${receiver.path} name=${receiver.path.name} elements=${receiver.path.elements.mkString(",")}) handle=$handle")
    receiver.path.name
    if (queue.size > MonitoredMessageQueue.Q_SIZE_THRESHOLD) {
      log.warn(s"[enqueue(sz=$numberOfMessages)] exceeded threshold(${MonitoredMessageQueue.Q_SIZE_THRESHOLD}!")
    }
    queue.offer(handle)
  }
  def dequeue(): Envelope = {
    val env = queue.poll
    log.info(s"[dequeue(sz=$numberOfMessages)] envelope=$env")
    env
  }
  def numberOfMessages: Int = queue.size
  def hasMessages: Boolean = {
    if (!queue.isEmpty) {
      log.info(s"[MonitoredMessageQueue(sz=$numberOfMessages)] queue=$this is NOT empty!")
    }
    !queue.isEmpty
  }
  def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    while (hasMessages) {
      deadLetters.enqueue(owner, dequeue())
    }
  }
}

object MonitoredMessageQueue {
  val Q_SIZE_THRESHOLD = 50 // tweak
}

class MonitoredMailbox(settings: ActorSystem.Settings, config: Config) extends MailboxType with ProducesMessageQueue[MonitoredMessageQueue] with Logging {
  log.info(s"[MonitoredMailbox.ctr] settings=$settings config=$config")

  def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue = {
    val q = new MonitoredMessageQueue()
    owner.foreach { actorRef =>
      MonitoredMailbox.mailboxes.put(actorRef, new WeakReference[MonitoredMessageQueue](q))
    }
    log.info(s"[MonitoredMailbox.create] q=$q owner=$owner system=$system")
    q
  }

}

object MonitoredMailbox {
  private[actor] var mailboxes = new ConcurrentHashMap[ActorRef, WeakReference[MonitoredMessageQueue]]()
  import scala.collection.JavaConversions._
  def aggregateSize: Int = {
    mailboxes.valuesIterator.foldLeft(0) { (a, c) => a + c.get.map(_.numberOfMessages).getOrElse(0) }
  }
  def idleAgentExists: Boolean = {
    mailboxes.valuesIterator.collectFirst { case wf if wf.get.exists(!_.hasMessages) => true } getOrElse false
  }
  def numEmptyMailboxes: Int = {
    mailboxes.values.filterNot(_.get.exists(_.hasMessages)).toSeq.length
  }
}