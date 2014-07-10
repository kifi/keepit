package com.keepit.common.zookeeper

import com.keepit.common.strings._
import org.apache.zookeeper.CreateMode._
import org.apache.zookeeper.KeeperException
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{ Map => MutableMap }
import java.util.UUID

trait Lock {
  import LockManager._

  // places a lock request with the specified lock mode and returns this lock instance
  // throws LockRequestAlreadyPlacedException if a request was already placed
  def request(mode: LockMode): Lock

  // releases the acquired lock if any. this cancels the request not fulfilled yet.
  def release(): Unit

  // cancels the request. returns true if successfully canceled, otherwise false.
  def cancel(): Boolean

  // current lock mode, returns None is not granted
  def mode: Option[LockMode]

  // wait for the outstanding request to be granted
  def await(timeout: Duration): Unit

  // test if there is an outstanding request
  def isWaiting: Boolean
}

object LockManager {

  def lock(name: String): LockBuilder = new LockBuilder(name)

  sealed class LockMode(val name: String) {
    override def toString(): String = name
  }

  case object NullMode extends LockMode("null")
  case object SharedMode extends LockMode("s")
  case object ExclusiveMode extends LockMode("x")

  private[zookeeper] final val stringToLockMode = Map(
    NullMode.name -> NullMode,
    SharedMode.name -> SharedMode,
    ExclusiveMode.name -> ExclusiveMode
  )

  class LockFailureException(msg: String) extends Exception(msg)
  class LockRequestAlreadyPlacedException(msg: String) extends LockFailureException(msg)
  class LockRequestCanceledException(msg: String) extends LockFailureException(msg)
  class LockRequestNotExistException(msg: String) extends LockFailureException(msg)
  class LockRequestFailedException(msg: String) extends LockFailureException(msg)

  class LockBuilder(name: String, onGrantedHandler: Option[Lock => Unit], onBlockingHandler: Option[Lock => Unit]) {
    def this(name: String) = this(name, None, None)

    // returns a new LockBuilder instance replacing the onGranted handler
    def onGranted(handler: Lock => Unit): LockBuilder = new LockBuilder(name, Some(handler), onBlockingHandler)

    // returns a new LockBuilder instance replacing the onBlocking handler
    def onBlocking(handler: Lock => Unit): LockBuilder = new LockBuilder(name, onGrantedHandler, Some(handler))

    def build(implicit lockService: LockManager, executionContext: ExecutionContext): Lock = lockService.createLock(Node(name), onGrantedHandler, onBlockingHandler, executionContext)
  }
}

class LockManager(zkClient: ZooKeeperClient) {

  private[this] val lockQueues = new TrieMap[Node, LockQueue]()

  def onConnected(handler: ZooKeeperSession => Unit) = zkClient.onConnected(handler)

  private def getQueue(node: Node): LockQueue = {
    lockQueues.get(node) match {
      case Some(queue) => queue
      case None =>
        val newQueue = new LockQueue(node, zkClient)
        lockQueues.putIfAbsent(node, newQueue) match {
          case Some(queue) => queue.ensureStarted() // a race condition. ensure the queue is started
          case None => newQueue.ensureStarted()
        }
    }
  }

  def createLock(node: Node, onGrantedHandler: Option[Lock => Unit], onBlockingHandler: Option[Lock => Unit], executionContext: ExecutionContext): Lock = {
    new LockImpl(node, onGrantedHandler, onBlockingHandler, getQueue(node))(executionContext)
  }
}

class LockImpl(val node: Node, onGrantedHandler: Option[Lock => Unit], onBlockingHandler: Option[Lock => Unit], lockQueue: LockQueue)(implicit executionContext: ExecutionContext) extends Lock {
  import LockManager._

  val uuid = UUID.randomUUID.toString

  private[this] val mutex = new AnyRef
  private[this] var grantPromise: Promise[LockMode] = null // completed by the lock queue when granted
  private[this] var readyFuture: Future[LockMode] = null // completed when onGranted handler is done
  private[this] var onBlockingCalled = false

  def request(lockMode: LockMode): Lock = mutex.synchronized {
    if (grantPromise != null) throw new LockRequestAlreadyPlacedException(s"name=$node")

    try {
      grantPromise = Promise[LockMode]
      readyFuture = onGrantedHandler match {
        case Some(handler) => grantPromise.future.map(m => { Try { handler(this) }; m })
        case None => grantPromise.future
      }
      onBlockingCalled = false
      lockQueue.add(this, lockMode)
    } catch {
      case ex: Throwable =>
        grantPromise = null
        readyFuture = null
        throw ex
    }
    this
  }

  def release(): Unit = mutex.synchronized {
    abort(new LockRequestCanceledException(s"name=$node")) // abort if not granted yet
    grantPromise = null
    readyFuture = null
    lockQueue.remove(this)
  }

  def cancel(): Boolean = mutex.synchronized {
    if (abort(new LockRequestCanceledException(s"name=$node"))) {
      grantPromise = null
      readyFuture = null
      lockQueue.remove(this)
      true
    } else { // the request is already granted or canceled
      false
    }
  }

  def mode: Option[LockMode] = { // no need to synchronize
    val promise = grantPromise
    if (promise != null) {
      promise.future.value match {
        case Some(Success(mode)) => Some(mode)
        case Some(Failure(ex)) => throw ex
        case None => None
      }
    } else {
      throw new LockRequestNotExistException(s"name=$node")
    }
  }

  def await(timeout: Duration): Unit = { // do not synchronize
    val future = readyFuture
    if (future != null) {
      if (!future.isCompleted) Await.result(future, timeout)
    } else {
      throw new LockRequestNotExistException(s"name=$node")
    }
  }

  def isWaiting: Boolean = (grantPromise != null)

  private[zookeeper] def granted(lockMode: LockMode) = mutex.synchronized {
    if (isWaiting) {
      // try to complete the promise. onCompleted handler will be invoked thru a future chain
      grantPromise.trySuccess(lockMode)
    }
  }

  private[zookeeper] def abort(e: Throwable): Boolean = mutex.synchronized {
    if (isWaiting) grantPromise.tryFailure(e) else true
  }

  private[zookeeper] def blocking() = mutex.synchronized {
    if (!onBlockingCalled && readyFuture != null) {
      onBlockingCalled = true
      onBlockingHandler.map { handler => readyFuture.map { _ => Try { handler(this) } } } // onBlocking won't be executed until onCompleted is finished
    }
  }
}

class LockQueue(lockNode: Node, zkClient: ZooKeeperClient) {
  import LockManager._

  private[this] var initialized = false
  private[this] val registry: MutableMap[String, (LockImpl, Node)] = MutableMap()

  def ensureStarted(): LockQueue = synchronized {
    if (!initialized) {
      zkClient.session { zk =>
        // make sure we have a lock node in zk. we should not do this as a part of instance creation to avoid duplicate watches
        zk.get(lockNode).getOrElse { zk.create(lockNode) }
        zk.watchChildren(lockNode, queueWatcher)
      }
      initialized = true
    }
    this
  }

  def add(lock: LockImpl, mode: LockMode): Unit = synchronized {
    val uuid = lock.uuid

    if (registry.contains(uuid)) throw new LockRequestAlreadyPlacedException(s"name=lockNode")

    try {
      val node = zkClient.session { zk => zk.createChild(lockNode, s"${uuid}_${mode.name}_", null, EPHEMERAL_SEQUENTIAL) }
      registry += (uuid -> (lock, node))
    } catch {
      case ex: KeeperException.ConnectionLossException => registry += (uuid -> (lock, null)) // we may have created the request node successfully.
      case ex: InterruptedException => registry += (uuid -> (lock, null)) // we may have created the request node successfully.
    }
  }

  def remove(lock: LockImpl): Unit = remove(lock.uuid)

  private def remove(uuid: String): Unit = synchronized {
    registry.get(uuid).foreach {
      case (_, node) =>
        if (node != null) zkClient.session { zk => zk.delete(node) }
    }
    registry -= uuid
  }

  private def queueWatcher(reqNodes: Seq[Node]): Unit = {
    if (reqNodes.nonEmpty) {
      val queue = reqNodes.map(toEntry).sortBy(_.seq)

      syncRegistry(queue) // check consistency of the registry and the queue

      val mode = queue.head.mode
      val holders = mode match {
        case ExclusiveMode => queue.take(1)
        case SharedMode => queue.takeWhile(_.mode == SharedMode)
        case _ => throw new IllegalStateException(s"illegal mode: $mode")
      }
      val isBlocking = (holders.size < queue.size)
      holders.foreach { holder =>
        registry.get(holder.uuid).foreach {
          case (lock, node) =>
            if (holder.name == node.name) {
              if (isBlocking) lock.blocking() // this sets up the future chain. onBlocking handler is invoked after onGranted handler
              lock.granted(mode)
            } else {
              throw new IllegalStateException(s"inconsistent request node name: znode=${holder.name} registry=${node.name}")
            }
        }
      }
    }
  }

  private def syncRegistry(entries: Seq[Entry]) = synchronized {
    entries.foreach { entry =>
      registry.get(entry.uuid) match {
        case Some((lock, node)) =>
          if (node == null || node.name != entry.name) registry += (entry.uuid -> (lock, Node(lockNode, entry.name)))
        case None =>
      }
    }
  }

  private case class Entry(uuid: String, mode: LockMode, seq: Long, name: String)

  private def toEntry(requestNode: Node): Entry = {
    val Array(uuid, mode, seq) = requestNode.name.split("_")
    Entry(uuid, stringToLockMode(mode), seq.toLong, requestNode.name)
  }
}

