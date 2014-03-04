package com.keepit.common.concurrent

import com.keepit.common.logging.Logging
import java.util.concurrent.Executors

object ExecutionContext extends Logging {
  val immediate = new scala.concurrent.ExecutionContext {
    override def execute(runnable: Runnable): Unit = {
      try {
        runnable.run()
      } catch {
        case t: Throwable => reportFailure(t)
      }
    }
    override def reportFailure(t: Throwable): Unit = { log.error("retry failure", t) }
    override def prepare(): scala.concurrent.ExecutionContext = this
  }

  val singleThread: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(1))

  val fjParallelism = { // todo(ray):remove play dependency
    import play.api.Play
    import play.api.Play.current
    (for {
      app <- Play.maybeApplication
      p <- Play.configuration.getInt("fork-join-pool.parallelism")
    } yield p) getOrElse 16
  }
  val fjPool = new scala.concurrent.forkjoin.ForkJoinPool(Runtime.getRuntime.availableProcessors * fjParallelism) // tweak
  val fj = scala.concurrent.ExecutionContext.fromExecutor(fjPool)

}
